#include "Enclave_u.h"
#include "sgx_urts.h"

#include <cerrno>
#include <charconv>
#include <chrono>
#include <csignal>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include <string>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

namespace {
volatile std::sig_atomic_t stopping = 0;
void stop(int) { stopping = 1; }

struct Fd {
    int value;
    explicit Fd(int fd) : value(fd) {
        if (fd < 0) throw std::runtime_error(std::strerror(errno));
    }
    ~Fd() { close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
};

struct Enclave {
    sgx_enclave_id_t id = 0;
    explicit Enclave(const char *path) {
        sgx_status_t status = sgx_create_enclave(path, SGX_DEBUG_FLAG, nullptr, nullptr, &id, nullptr);
        if (status != SGX_SUCCESS) {
            std::cerr << "sgx_create_enclave failed: 0x" << std::hex << status << std::dec << '\n';
            throw std::runtime_error("Cannot create hardware enclave");
        }
    }
    ~Enclave() { sgx_destroy_enclave(id); }
};

// Only remove the socket this process actually bound, never an existing file.
struct SocketPath {
    std::string path;
    struct stat identity{};
    explicit SocketPath(std::string p) : path(std::move(p)) {
        if (lstat(path.c_str(), &identity) != 0)
            throw std::runtime_error("Cannot inspect bound socket");
    }
    ~SocketPath() {
        struct stat current{};
        if (lstat(path.c_str(), &current) == 0 && S_ISSOCK(current.st_mode)
            && current.st_dev == identity.st_dev && current.st_ino == identity.st_ino)
            unlink(path.c_str());
    }
};

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
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
    std::string line;
    while (!stopping && line.size() < 64) {
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

// Returns false only if the enclave failed, so the listener stops serving decisions.
bool handle(int fd, sgx_enclave_id_t enclave)
{
    std::string request;
    try { request = read_request(fd); }
    catch (const std::exception&) { reply(fd, "ERROR invalid_request\n"); return true; }

    if (request.compare(0, 6, "CHECK ") != 0 || request.size() <= 6) {
        reply(fd, "ERROR invalid_request\n");
        return true;
    }
    int minute = 0;
    const char *end = request.data() + request.size();
    auto parsed = std::from_chars(request.data() + 6, end, minute);
    if (parsed.ec != std::errc() || parsed.ptr != end) {
        reply(fd, "ERROR invalid_request\n");
        return true;
    }

    int result = -1, allowed = 0, debug = 0;
    sgx_status_t status = check_worktime(enclave, &result, minute, &allowed, &debug);
    if (status != SGX_SUCCESS || (result != 0 && result != 1)) {
        std::cerr << "ECALL failed: transport=0x" << std::hex << status
                  << std::dec << ", application=" << result << '\n';
        reply(fd, "ERROR enclave_failure\n");
        return false;
    }
    if (result == 1) reply(fd, "ERROR invalid_time\n");
    else if (debug != 1 || (allowed != 0 && allowed != 1)) {
        reply(fd, "ERROR unexpected_enclave_result\n");
        return false;
    }
    else reply(fd, allowed ? "ALLOW HW DEBUG\n" : "DENY HW DEBUG\n");
    return true;
}
}

int main(int argc, char **argv)
{
    if (argc != 3) {
        std::cerr << "Usage: catee-service SOCKET_PATH ENCLAVE_PATH\n";
        return 1;
    }
    try {
        std::string path(argv[1]);
        sockaddr_un address{};
        if (path.empty() || path.front() != '/' || path.size() >= sizeof(address.sun_path))
            throw std::runtime_error("Use a short absolute socket path");
        struct stat parent{};
        auto directory = std::filesystem::path(path).parent_path();
        if (lstat(directory.c_str(), &parent) != 0 || !S_ISDIR(parent.st_mode)
            || parent.st_uid != geteuid() || (parent.st_mode & 0077) != 0)
            throw std::runtime_error("Socket parent must be an owned private directory (0700)");

        Enclave enclave(argv[2]);
        Fd listener(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
        address.sun_family = AF_UNIX;
        std::memcpy(address.sun_path, path.c_str(), path.size() + 1);
        // Never unlink before bind: an existing socket/file must make startup fail.
        umask(0077);
        if (bind(listener.value, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0)
            throw std::runtime_error(std::string("Cannot bind socket: ") + std::strerror(errno));
        SocketPath cleanup(path);
        if (listen(listener.value, 16) != 0)
            throw std::runtime_error("Cannot listen on socket");
        struct sigaction action{};
        action.sa_handler = stop;
        sigemptyset(&action.sa_mask);
        if (sigaction(SIGTERM, &action, nullptr) != 0 || sigaction(SIGINT, &action, nullptr) != 0)
            throw std::runtime_error("Cannot install shutdown handlers");
        std::cout << "CATEE first slice: HW, DEBUG enclave; listening on " << path << std::endl;
        bool healthy = true;
        while (!stopping && healthy) {
            // A bounded poll also handles a signal arriving just before waiting.
            pollfd incoming{listener.value, POLLIN, 0};
            int ready = poll(&incoming, 1, 250);
            if (ready < 0 && errno == EINTR) continue;
            if (ready < 0) throw std::runtime_error("Cannot poll listener");
            if (stopping) break;
            if (ready == 0) continue;
            if (!(incoming.revents & POLLIN))
                throw std::runtime_error("Listener failed");
            int accepted = accept4(listener.value, nullptr, nullptr, SOCK_CLOEXEC);
            if (accepted < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK))
                continue;
            Fd client(accepted);
            timeval timeout{1, 0};
            if (setsockopt(client.value, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) != 0)
                throw std::runtime_error("Cannot set response timeout");
            ucred peer{};
            socklen_t length = sizeof(peer);
            if (getsockopt(client.value, SOL_SOCKET, SO_PEERCRED, &peer, &length) != 0
                || peer.uid != geteuid()) {
                reply(client.value, "ERROR unauthorized_peer\n");
                continue;
            }
            healthy = handle(client.value, enclave.id);
        }
        return healthy ? 0 : 1;
    }
    catch (const std::exception& error) {
        std::cerr << "CATEE: " << error.what() << '\n';
        return 1;
    }
}
