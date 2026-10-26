#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Test-only preload: affect only the explicitly selected temporary state path. */
static int matches(int fd, int directory)
{
    const char *state = getenv("CATEE_TEST_STATE_FILE");
    if (!state) return 0;
    char target[PATH_MAX], link[64], actual[PATH_MAX];
    if (snprintf(target, sizeof(target), "%s%s", state, directory ? "" : ".tmp") >= (int)sizeof(target)) return 0;
    if (directory)
    {
        char *slash = strrchr(target, '/');
        if (!slash) return 0;
        *slash = '\0';
    }
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t size = readlink(link, actual, sizeof(actual) - 1);
    if (size < 0) return 0;
    actual[size] = '\0';
    return strcmp(actual, target) == 0;
}

static int mode(const char *name)
{
    const char *selected = getenv("CATEE_TEST_PERSISTENCE_FAULT");
    return selected && strcmp(selected, name) == 0;
}

ssize_t write(int fd, const void *buffer, size_t count)
{
    static ssize_t (*real_write)(int, const void *, size_t);
    static int partial;
    if (!real_write) real_write = dlsym(RTLD_NEXT, "write");
    if (mode("partial_write") && matches(fd, 0))
    {
        if (partial) { errno = EIO; return -1; }
        partial = 1;
        return real_write(fd, buffer, count > 31 ? 31 : count);
    }
    return real_write(fd, buffer, count);
}

int fsync(int fd)
{
    static int (*real_fsync)(int);
    if (!real_fsync) real_fsync = dlsym(RTLD_NEXT, "fsync");
    int file = matches(fd, 0), directory = matches(fd, 1);
    if ((file && mode("file_fsync")) || (directory && mode("directory_fsync")))
    { errno = EIO; return -1; }
    int result = real_fsync(fd);
    if (result == 0 && ((file && mode("crash_after_file_fsync")) ||
                        (directory && mode("crash_after_directory_fsync")))) _exit(86);
    return result;
}

int close(int fd)
{
    static int (*real_close)(int);
    if (!real_close) real_close = dlsym(RTLD_NEXT, "close");
    int fail = (mode("file_close") && matches(fd, 0)) ||
               (mode("directory_close") && matches(fd, 1));
    int result = real_close(fd);
    if (fail && result == 0) { errno = EIO; return -1; }
    return result;
}

int rename(const char *old_path, const char *new_path)
{
    static int (*real_rename)(const char *, const char *);
    if (!real_rename) real_rename = dlsym(RTLD_NEXT, "rename");
    const char *state = getenv("CATEE_TEST_STATE_FILE");
    int selected = state && strcmp(new_path, state) == 0;
    if (selected && mode("rename")) { errno = EIO; return -1; }
    int result = real_rename(old_path, new_path);
    if (selected && result == 0 && mode("crash_after_rename")) _exit(86);
    return result;
}
