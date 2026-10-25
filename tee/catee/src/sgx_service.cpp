#include "Enclave_u.h"
#include "sgx_urts.h"

#include <cerrno>
#include <charconv>
#include <chrono>
#include <csignal>
#include <cstring>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

/* ---------- Configuration and Service State ---------- */

constexpr size_t MAX_REQUEST_SIZE = 544;
constexpr int REQUEST_TIMEOUT_SECONDS = 1;
constexpr int RESPONSE_TIMEOUT_SECONDS = 1;
constexpr int LISTENER_POLL_MS = 250;
constexpr int LISTEN_BACKLOG = 16;

enum RequestType
{
    SGX_MSG_UNKNOWN,
    SGX_MSG_CHECK_WORKTIME,
    SGX_MSG_GRANT_ADMIN_PRIV,
    SGX_MSG_REVOKE_ADMIN_PRIV,
    SGX_MSG_CHECK_ADMIN_PRIV,
    SGX_MSG_BEGIN_ROLE_DELETE,
    SGX_MSG_FINISH_ROLE_DELETE
};

sgx_enclave_id_t global_eid = 0;
int server_fd = -1;
int client_fd = -1;
int state_lock_fd = -1;
std::string socket_path;
std::string state_path;
struct stat socket_identity{};
bool socket_bound = false;
volatile std::sig_atomic_t stopping = 0;

/* ---------- Helpers ---------- */

void stop(int)
{
    stopping = 1;
}

void close_client()
{
    if (client_fd >= 0)
    {
        close(client_fd);
        client_fd = -1;
    }
}

void cleanup()
{
    close_client();
    if (server_fd >= 0)
    {
        close(server_fd);
        server_fd = -1;
    }
    if (global_eid != 0)
    {
        sgx_destroy_enclave(global_eid);
        global_eid = 0;
    }
    if (state_lock_fd >= 0)
    {
        close(state_lock_fd);
        state_lock_fd = -1;
    }
    // Remove only the socket created by this service.
    struct stat current{};
    if (socket_bound && lstat(socket_path.c_str(), &current) == 0
        && S_ISSOCK(current.st_mode) && current.st_dev == socket_identity.st_dev
        && current.st_ino == socket_identity.st_ino)
        unlink(socket_path.c_str());
    socket_bound = false;
}

void check_sgx(sgx_status_t status, const char *operation)
{
    if (status != SGX_SUCCESS)
    {
        std::cerr << operation << " failed: 0x" << std::hex << status << std::dec << '\n';
        throw std::runtime_error("SGX operation failed");
    }
}

void validate_socket_path(const std::string& path)
{
    sockaddr_un address{};
    if (path.empty() || path.front() != '/' || path.size() >= sizeof(address.sun_path))
        throw std::runtime_error("Use a short absolute socket path");

    struct stat parent{};
    auto directory = std::filesystem::path(path).parent_path();
    if (lstat(directory.c_str(), &parent) != 0 || !S_ISDIR(parent.st_mode)
        || parent.st_uid != geteuid() || (parent.st_mode & 0077) != 0)
        throw std::runtime_error("Socket parent must be an owned private directory (0700)");
}

bool is_authorized_client(int fd)
{
    ucred peer{};
    socklen_t length = sizeof(peer);
    return getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) == 0
           && peer.uid == geteuid();
}

/* ---------- Sealing and State File I/O ---------- */

std::vector<uint8_t> sealed_buffer()
{
    uint32_t size = 0;
    int result = -1;
    check_sgx(enclave_get_sealed_size(global_eid, &result, &size), "get sealed size");
    if (result != 0 || size == 0 || size > 65536)
        throw std::runtime_error("Invalid sealed state size");
    return std::vector<uint8_t>(size);
}

void transfer_state(int fd, std::vector<uint8_t>& bytes, bool writing)
{
    size_t offset = 0;
    while (offset < bytes.size())
    {
        ssize_t count = writing ? write(fd, bytes.data() + offset, bytes.size() - offset)
                                : read(fd, bytes.data() + offset, bytes.size() - offset);
        if (count < 0 && errno == EINTR) continue;
        if (count <= 0) throw std::runtime_error("Incomplete state file I/O");
        offset += static_cast<size_t>(count);
    }
}

void reseal_admin(bool initialize = false)
{
    auto bytes = sealed_buffer();
    int result = -1;
    check_sgx(enclave_seal_admin(global_eid, &result, bytes.data(), bytes.size()), "seal admin state");
    if (result != 0) throw std::runtime_error("Cannot seal admin state");
    std::string destination = initialize ? state_path : state_path + ".tmp";
    int fd = open(destination.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) throw std::runtime_error("Cannot exclusively create state file");
    try
    {
        transfer_state(fd, bytes, true);
        if (fsync(fd) != 0) throw std::runtime_error("Cannot sync state file");
        int closed = close(fd);
        fd = -1;
        if (closed != 0) throw std::runtime_error("Cannot close state file");
        if (!initialize && rename(destination.c_str(), state_path.c_str()) != 0)
            throw std::runtime_error("Cannot replace state file");
        auto parent = std::filesystem::path(state_path).parent_path();
        fd = open(parent.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (fd < 0 || fsync(fd) != 0) throw std::runtime_error("Cannot sync state directory");
        closed = close(fd);
        fd = -1;
        if (closed != 0) throw std::runtime_error("Cannot close state directory");
    }
    catch (...)
    {
        if (fd >= 0) close(fd);
        // Preserve failed/partial files for explicit recovery; never reset state silently.
        throw;
    }
}

void load_admin_state()
{
    std::vector<uint8_t> bytes;
    int fd = open(state_path.c_str(), O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) throw std::runtime_error("Cannot open sealed state; initialize explicitly for a new instance");
    try
    {
        struct stat info{};
        if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_uid != geteuid()
            || (info.st_mode & 0077) != 0 || info.st_size <= 0 || info.st_size > 65536)
            throw std::runtime_error("Invalid sealed state file");
        bytes.resize(static_cast<size_t>(info.st_size));
        transfer_state(fd, bytes, false);
    }
    catch (...)
    {
        close(fd);
        throw;
    }
    if (close(fd) != 0) throw std::runtime_error("Cannot close sealed state file");
    int result = -1;
    check_sgx(enclave_unseal_admin(global_eid, &result, bytes.data(), bytes.size()), "unseal admin state");
    if (result != 0) throw std::runtime_error("Sealed admin state rejected");
}

/* ---------- Request and Response I/O ---------- */

bool reply(int fd, const std::string& text)
{
    size_t sent = 0;
    while (sent < text.size()) {
        ssize_t n = send(fd, text.data() + sent, text.size() - sent, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR && !stopping) continue;
        if (n <= 0) return false;
        sent += static_cast<size_t>(n);
    }
    return true;
}

std::string read_request(int fd)
{
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(REQUEST_TIMEOUT_SECONDS);
    std::string line;
    while (!stopping && line.size() < MAX_REQUEST_SIZE) {
        auto remaining = std::chrono::duration_cast<std::chrono::milliseconds>(
            deadline - std::chrono::steady_clock::now()).count();
        if (remaining <= 0) break;
        pollfd input{fd, POLLIN, 0};
        int ready = poll(&input, 1, static_cast<int>(remaining));
        if (ready < 0 && errno == EINTR) continue;
        if (ready <= 0) break;
        char ch;
        ssize_t n = recv(fd, &ch, 1, 0);
        if (n < 0 && errno == EINTR) continue;
        if (n != 1) break;
        if (ch == '\n') return line;
        line.push_back(ch);
    }
    throw std::runtime_error("Incomplete, oversized, or timed-out request");
}

bool parse_role(const std::string& hex, char (&role)[256])
{
    if (hex.empty() || hex.size() > 510 || hex.size() % 2 != 0) return false;
    for (size_t i = 0; i < hex.size() / 2; ++i)
    {
        unsigned byte = 0;
        const char *begin = hex.data() + i * 2;
        auto parsed = std::from_chars(begin, begin + 2, byte, 16);
        if (parsed.ec != std::errc() || parsed.ptr != begin + 2 || byte == 0) return false;
        role[i] = static_cast<char>(byte);
    }
    role[hex.size() / 2] = '\0';
    return true;
}

bool parse_admin_request(const std::string& request, char (&role)[256], uint32_t& mask)
{
    size_t first = request.find(' '), last = request.rfind(' ');
    if (first == std::string::npos || first == last
        || !parse_role(request.substr(first + 1, last - first - 1), role)) return false;
    const char *end = request.data() + request.size();
    auto parsed = std::from_chars(request.data() + last + 1, end, mask);
    return parsed.ec == std::errc() && parsed.ptr == end && mask != 0;
}

/* ---------- Main ---------- */

int main(int argc, char **argv)
{
    if ((argc != 4 && argc != 5) || (argc == 5 && std::string(argv[4]) != "--init"))
    {
        std::cerr << "Usage: catee-service SOCKET_PATH ENCLAVE_PATH STATE_FILE [--init]\n";
        return 1;
    }
    if (std::atexit(cleanup) != 0)
    {
        std::cerr << "Cannot register service cleanup\n";
        return 1;
    }

    try
    {
        socket_path = argv[1];
        validate_socket_path(socket_path);
        state_path = argv[3];
        validate_socket_path(state_path);
        if (std::filesystem::path(state_path).lexically_normal() == std::filesystem::path(socket_path).lexically_normal())
            throw std::runtime_error("State file and socket paths must differ");
        state_lock_fd = open((state_path + ".lock").c_str(), O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
        struct stat lock_info{};
        if (state_lock_fd < 0 || fstat(state_lock_fd, &lock_info) != 0 || !S_ISREG(lock_info.st_mode)
            || lock_info.st_uid != geteuid() || (lock_info.st_mode & 0077) != 0
            || flock(state_lock_fd, LOCK_EX | LOCK_NB) != 0)
            throw std::runtime_error("Cannot exclusively lock sealed state");

        /* Enclave initialization */
        sgx_status_t ret = sgx_create_enclave(argv[2], SGX_DEBUG_FLAG, nullptr, nullptr, &global_eid, nullptr);
        check_sgx(ret, "sgx_create_enclave");
        if (argc == 5) reseal_admin(true);
        else load_admin_state();

        /* Socket setup */
        server_fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
        if (server_fd < 0)
            throw std::runtime_error("Cannot create listener");
        sockaddr_un address{};
        address.sun_family = AF_UNIX;
        std::memcpy(address.sun_path, socket_path.c_str(), socket_path.size() + 1);
        umask(0077);
        // An existing socket or file must make startup fail.
        if (bind(server_fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0)
            throw std::runtime_error(std::string("Cannot bind socket: ") + std::strerror(errno));
        if (lstat(socket_path.c_str(), &socket_identity) != 0)
            throw std::runtime_error("Cannot inspect bound socket");
        socket_bound = true;
        if (listen(server_fd, LISTEN_BACKLOG) != 0)
            throw std::runtime_error("Cannot listen on socket");

        /* Shutdown handlers */
        struct sigaction action{};
        action.sa_handler = stop;
        sigemptyset(&action.sa_mask);
        if (sigaction(SIGTERM, &action, nullptr) != 0 || sigaction(SIGINT, &action, nullptr) != 0)
            throw std::runtime_error("Cannot install shutdown handlers");
        std::cout << "CATEE first slice: HW, DEBUG enclave; listening on " << socket_path << std::endl;

        /* Client loop and request dispatch */
        bool healthy = true;
        while (!stopping && healthy)
        {
            pollfd incoming{server_fd, POLLIN, 0};
            int ready = poll(&incoming, 1, LISTENER_POLL_MS);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) throw std::runtime_error("Cannot poll listener");
            if (stopping) break;
            if (ready == 0) continue;
            if (!(incoming.revents & POLLIN))
                throw std::runtime_error("Listener failed");

            client_fd = accept4(server_fd, nullptr, nullptr, SOCK_CLOEXEC);
            if (client_fd < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
                continue;
            if (client_fd < 0)
                throw std::runtime_error("Cannot accept client");
            timeval timeout{RESPONSE_TIMEOUT_SECONDS, 0};
            if (setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) != 0)
                throw std::runtime_error("Cannot set response timeout");
            if (!is_authorized_client(client_fd))
            {
                reply(client_fd, "ERROR unauthorized_peer\n");
                close_client();
                continue;
            }

            std::string request;
            try { request = read_request(client_fd); }
            catch (const std::exception&)
            {
                reply(client_fd, "ERROR invalid_request\n");
                close_client();
                continue;
            }
            RequestType type = request.compare(0, 6, "CHECK ") == 0
                               ? SGX_MSG_CHECK_WORKTIME : SGX_MSG_UNKNOWN;
            if (request.compare(0, 17, "GRANT_ADMIN_PRIV ") == 0) type = SGX_MSG_GRANT_ADMIN_PRIV;
            if (request.compare(0, 18, "REVOKE_ADMIN_PRIV ") == 0) type = SGX_MSG_REVOKE_ADMIN_PRIV;
            if (request.compare(0, 17, "CHECK_ADMIN_PRIV ") == 0) type = SGX_MSG_CHECK_ADMIN_PRIV;
            if (request.compare(0, 18, "BEGIN_ROLE_DELETE ") == 0) type = SGX_MSG_BEGIN_ROLE_DELETE;
            if (request.compare(0, 19, "FINISH_ROLE_DELETE ") == 0) type = SGX_MSG_FINISH_ROLE_DELETE;
            switch (type)
            {
                case SGX_MSG_CHECK_WORKTIME:
                {
                    int minute = 0;
                    const char *end = request.data() + request.size();
                    auto parsed = std::from_chars(request.data() + 6, end, minute);
                    if (parsed.ec != std::errc() || parsed.ptr != end)
                    {
                        reply(client_fd, "ERROR invalid_request\n");
                        break;
                    }

                    int result = -1, allowed = 0, debug = 0;
                    ret = check_worktime(global_eid, &result, minute, &allowed, &debug);
                    if (ret != SGX_SUCCESS || (result != 0 && result != 1))
                    {
                        std::cerr << "ECALL failed: transport=0x" << std::hex << ret
                                  << std::dec << ", application=" << result << '\n';
                        reply(client_fd, "ERROR enclave_failure\n");
                        healthy = false;
                    }
                    else if (result == 1)
                        reply(client_fd, "ERROR invalid_time\n");
                    else if (debug != 1 || (allowed != 0 && allowed != 1))
                    {
                        reply(client_fd, "ERROR unexpected_enclave_result\n");
                        healthy = false;
                    }
                    else
                        reply(client_fd, allowed ? "ALLOW HW DEBUG\n" : "DENY HW DEBUG\n");
                    break;
                }
                case SGX_MSG_BEGIN_ROLE_DELETE:
                case SGX_MSG_FINISH_ROLE_DELETE:
                case SGX_MSG_GRANT_ADMIN_PRIV:
                case SGX_MSG_REVOKE_ADMIN_PRIV:
                case SGX_MSG_CHECK_ADMIN_PRIV:
                {
                    char role[256]{};
                    uint32_t mask = 0;
                    bool lifecycle = type == SGX_MSG_BEGIN_ROLE_DELETE || type == SGX_MSG_FINISH_ROLE_DELETE;
                    if (lifecycle ? !parse_role(request.substr(request.find(' ') + 1), role)
                                  : !parse_admin_request(request, role, mask))
                    {
                        reply(client_fd, "ERROR invalid_request\n");
                        break;
                    }
                    int result = -1, allowed = 0;
                    if (type == SGX_MSG_BEGIN_ROLE_DELETE)
                        ret = enclave_begin_role_delete(global_eid, &result, role);
                    else if (type == SGX_MSG_FINISH_ROLE_DELETE)
                        ret = enclave_finish_role_delete(global_eid, &result, role);
                    else if (type == SGX_MSG_GRANT_ADMIN_PRIV)
                        ret = enclave_grant_admin_priv(global_eid, &result, role, mask);
                    else if (type == SGX_MSG_REVOKE_ADMIN_PRIV)
                        ret = enclave_revoke_admin_priv(global_eid, &result, role, mask);
                    else
                        ret = enclave_check_admin_priv(global_eid, &result, role, mask, &allowed);
                    if (ret != SGX_SUCCESS || (result != 0 && result != 1)
                        || (allowed != 0 && allowed != 1))
                    {
                        reply(client_fd, "ERROR enclave_failure\n");
                        healthy = false;
                    }
                    else if (result != 0)
                        reply(client_fd, "ERROR admin_operation_failed\n");
                    else if (type == SGX_MSG_CHECK_ADMIN_PRIV)
                        reply(client_fd, allowed ? "ALLOW HW DEBUG\n" : "DENY HW DEBUG\n");
                    else
                    {
                        try
                        {
                            reseal_admin();
                            reply(client_fd, "OK HW DEBUG\n");
                        }
                        catch (const std::exception& error)
                        {
                            std::cerr << "State persistence failed: " << error.what() << '\n';
                            reply(client_fd, "ERROR persistence_failure\n");
                            healthy = false;
                        }
                    }
                    break;
                }
                default:
                    reply(client_fd, "ERROR invalid_request\n");
                    break;
            }
            close_client();
        }
        return healthy ? 0 : 1;
    }
    catch (const std::exception& error)
    {
        std::cerr << "CATEE: " << error.what() << '\n';
        return 1;
    }
}
