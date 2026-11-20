#ifndef THINKOS_BACKLIGHT_LEASE_H
#define THINKOS_BACKLIGHT_LEASE_H

/* Only the board node selected by the daemon is ever read or written. Virtual
 * time and a regular file can drive the same lease logic in host tests. */
#define DARK_LEASE_US 20000000LL
struct dark_lease {
    const char *node;
    int saved;
    int64_t deadline;
};

static int dark_read(const char *node)
{
    char buf[24];
    int fd = open(node, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (n <= 0 || n >= (ssize_t)sizeof buf - 1) return -1;
    buf[n] = 0;
    char *end;
    long v = strtol(buf, &end, 10);
    if (end == buf || (*end != 0 && strcmp(end, "\n")) || v < 0 || v > 65535) return -1;
    return (int)v;
}

static int dark_write(const char *node, int value)
{
    char buf[24];
    int n = snprintf(buf, sizeof buf, "%d\n", value);
    int fd = open(node, O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t written = write(fd, buf, (size_t)n);
    close(fd);
    return written == n;
}

static int dark_end(struct dark_lease *lease)
{
    if (!lease->deadline) return 1;
    int current = dark_read(lease->node);
    if (current < 0) return 0;
    if (current == 0 && !dark_write(lease->node, lease->saved)) return 0;
    lease->deadline = 0;
    return 1;
}

static void dark_expire(struct dark_lease *lease, int64_t now)
{
    if (lease->deadline && now >= lease->deadline) dark_end(lease);
}

static int dark_begin(struct dark_lease *lease, int64_t now)
{
    if (lease->deadline) return 0;
    int current = dark_read(lease->node);
    if (current <= 0) return 0;
    lease->saved = current;
    lease->deadline = now + DARK_LEASE_US;
    if (dark_write(lease->node, 0) && dark_read(lease->node) == 0) return 1;
    dark_end(lease);
    return 0;
}

static int dark_keep(struct dark_lease *lease, int64_t now)
{
    if (!lease->deadline || now >= lease->deadline) return 0;
    if (dark_read(lease->node) != 0) { dark_end(lease); return 0; }
    lease->deadline = now + DARK_LEASE_US;
    return 1;
}
#endif
