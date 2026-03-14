/*
 * tecla-bridge - thin evdev/uinput I/O bridge
 *
 * Grabs ALL keyboards, creates a single virtual keyboard.
 * Forwards key events to a Babashka client via Unix socket.
 * When no client is connected, auto-passthroughs everything.
 *
 * Protocol:
 *   Bridge -> Client:  HELLO\n  |  DEVNODE <path>\n  |  EV <code> <value>\n
 *   Client -> Bridge:  EM <code> <value>\n
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include <libevdev/libevdev.h>
#include <libevdev/libevdev-uinput.h>
#include <linux/input.h>

#define MAX_DEVICES 16

static volatile int running = 1;
static dev_t uinput_devnum = 0; /* our virtual device, skip during scan */

static struct {
    struct libevdev *dev;
    int fd;
    char name[128];
} devices[MAX_DEVICES];
static int device_count = 0;

static struct libevdev_uinput *uidev = NULL;
static int listen_fd = -1;
static int client_fd = -1;
static char socket_path[108] = "";

static void cleanup(void) {
    if (client_fd >= 0) { close(client_fd); client_fd = -1; }
    if (listen_fd >= 0) { close(listen_fd); listen_fd = -1; }
    if (socket_path[0]) unlink(socket_path);
    if (uidev) { libevdev_uinput_destroy(uidev); uidev = NULL; }
    for (int i = 0; i < device_count; i++) {
        if (devices[i].dev) {
            libevdev_grab(devices[i].dev, LIBEVDEV_UNGRAB);
            libevdev_free(devices[i].dev);
            devices[i].dev = NULL;
        }
        if (devices[i].fd >= 0) { close(devices[i].fd); devices[i].fd = -1; }
    }
}

static void signal_handler(int sig) { (void)sig; running = 0; }

static void send_to_client(const char *fmt, ...) {
    if (client_fd < 0) return;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) write(client_fd, buf, n);
}

/* ---- Device detection ---- */

static int is_keyboard(struct libevdev *d) {
    /* Skip virtual devices (uinput-created: Espanso, our own output, etc.) */
    if (libevdev_get_id_bustype(d) == BUS_VIRTUAL)
        return 0;
    return libevdev_has_event_type(d, EV_KEY)
        && libevdev_has_event_code(d, EV_KEY, KEY_A)
        && libevdev_has_event_code(d, EV_KEY, KEY_Z)
        && libevdev_has_event_code(d, EV_KEY, KEY_LEFTSHIFT)
        && libevdev_has_event_code(d, EV_KEY, KEY_LEFTCTRL);
}

static void list_devices(void) {
    char path[64];
    printf("Available input devices:\n\n");
    for (int i = 0; i < 64; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        struct libevdev *d = NULL;
        if (libevdev_new_from_fd(fd, &d) == 0) {
            printf("  %s: %s%s\n", path, libevdev_get_name(d),
                   is_keyboard(d) ? " [keyboard]" : "");
            libevdev_free(d);
        }
        close(fd);
    }
}

/* Remove a dead device */
static void remove_device(int idx) {
    fprintf(stderr, "  lost: %s\n", devices[idx].name);
    if (devices[idx].dev) {
        libevdev_grab(devices[idx].dev, LIBEVDEV_UNGRAB);
        libevdev_free(devices[idx].dev);
    }
    if (devices[idx].fd >= 0) close(devices[idx].fd);
    /* Shift remaining devices down */
    for (int j = idx; j < device_count - 1; j++)
        devices[j] = devices[j + 1];
    device_count--;
    memset(&devices[device_count], 0, sizeof(devices[0]));
    devices[device_count].fd = -1;
}

/* Check if we already grabbed a device at this path */
static int already_grabbed(int event_num) {
    char path[64];
    snprintf(path, sizeof(path), "/dev/input/event%d", event_num);
    for (int i = 0; i < device_count; i++) {
        /* Compare by fd's device/inode via path */
        struct stat st1, st2;
        if (fstat(devices[i].fd, &st1) == 0 && stat(path, &st2) == 0)
            if (st1.st_dev == st2.st_dev && st1.st_ino == st2.st_ino)
                return 1;
    }
    return 0;
}

/* Scan for keyboards, grab any new ones. Returns total device count. */
static int grab_keyboards(void) {
    char path[64];
    for (int i = 0; i < 64 && device_count < MAX_DEVICES; i++) {
        if (already_grabbed(i)) continue;

        snprintf(path, sizeof(path), "/dev/input/event%d", i);

        /* Skip our own virtual device */
        if (uinput_devnum) {
            struct stat st;
            if (stat(path, &st) == 0 && st.st_rdev == uinput_devnum)
                continue;
        }

        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;

        struct libevdev *d = NULL;
        if (libevdev_new_from_fd(fd, &d) < 0) { close(fd); continue; }

        if (!is_keyboard(d)) {
            libevdev_free(d);
            close(fd);
            continue;
        }

        int rc = libevdev_grab(d, LIBEVDEV_GRAB);
        if (rc < 0) {
            fprintf(stderr, "  skip %s (grab failed)\n", libevdev_get_name(d));
            libevdev_free(d);
            close(fd);
            continue;
        }

        const char *name = libevdev_get_name(d);
        devices[device_count].dev = d;
        devices[device_count].fd = fd;
        snprintf(devices[device_count].name, 128, "%s", name ? name : "?");
        fprintf(stderr, "  grabbed: %s (%s)\n", devices[device_count].name, path);
        device_count++;
    }
    return device_count;
}

/* ---- Socket ---- */

static int setup_socket(const char *path) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) { fprintf(stderr, "socket: %s\n", strerror(errno)); return -1; }
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "bind: %s\n", strerror(errno)); close(fd); return -1;
    }
    if (listen(fd, 1) < 0) {
        fprintf(stderr, "listen: %s\n", strerror(errno)); close(fd); return -1;
    }
    return fd;
}

/* ---- Client commands ---- */

static char line_buf[512];
static int line_buf_len = 0;

static void process_command(char *line) {
    if (strncmp(line, "EM ", 3) == 0) {
        int code, value;
        if (sscanf(line + 3, "%d %d", &code, &value) == 2) {
            libevdev_uinput_write_event(uidev, EV_KEY, code, value);
            libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
        }
    }
}

static void process_client_data(void) {
    char buf[512];
    ssize_t n = read(client_fd, buf, sizeof(buf));
    if (n <= 0) {
        fprintf(stderr, "Client disconnected\n");
        close(client_fd); client_fd = -1;
        line_buf_len = 0;
        return;
    }
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == '\n') {
            line_buf[line_buf_len] = '\0';
            if (line_buf_len > 0) process_command(line_buf);
            line_buf_len = 0;
        } else if (line_buf_len < (int)sizeof(line_buf) - 1)
            line_buf[line_buf_len++] = buf[i];
    }
}

/* ---- Main ---- */

int main(int argc, char **argv) {
    const char *sock_path = NULL;
    int timeout_sec = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--list") == 0) { list_devices(); return 0; }
        else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc)
            timeout_sec = atoi(argv[++i]);
        else if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc)
            sock_path = argv[++i];
    }

    if (!sock_path) {
        const char *rt = getenv("XDG_RUNTIME_DIR");
        snprintf(socket_path, sizeof(socket_path), "%s/tecla-bridge.sock",
                 rt ? rt : "/tmp");
    } else {
        snprintf(socket_path, sizeof(socket_path), "%s", sock_path);
    }

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    atexit(cleanup);

    fprintf(stderr, "Scanning keyboards...\n");
    if (grab_keyboards() == 0) {
        fprintf(stderr, "No keyboards found. Run with --list.\n");
        return 1;
    }
    fprintf(stderr, "Grabbed %d keyboard(s)\n", device_count);

    /* Create uinput from first device (copies its capabilities) */
    int rc = libevdev_uinput_create_from_device(
        devices[0].dev, LIBEVDEV_UINPUT_OPEN_MANAGED, &uidev);
    if (rc < 0) {
        fprintf(stderr, "uinput: %s\n", strerror(-rc));
        return 1;
    }
    const char *vdev = libevdev_uinput_get_devnode(uidev);
    fprintf(stderr, "Virtual: %s\n", vdev);

    /* Remember our own device so we don't grab it during rescan */
    { struct stat st;
      if (stat(vdev, &st) == 0) uinput_devnum = st.st_rdev; }

    /* Clear stale modifiers */
    static const int mods[] = { KEY_LEFTCTRL, KEY_RIGHTCTRL, KEY_LEFTSHIFT,
        KEY_RIGHTSHIFT, KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA };
    for (int i = 0; i < 8; i++)
        libevdev_uinput_write_event(uidev, EV_KEY, mods[i], 0);
    libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);

    listen_fd = setup_socket(socket_path);
    if (listen_fd < 0) return 1;
    fprintf(stderr, "Socket: %s\nReady.\n", socket_path);

    time_t start = time(NULL);
    time_t last_scan = time(NULL);
    struct input_event ev;

    while (running) {
        if (timeout_sec > 0 && (time(NULL) - start) >= timeout_sec) break;

        /* Rescan for new keyboards every 3 seconds */
        time_t now = time(NULL);
        if (now - last_scan >= 3) {
            int before = device_count;
            grab_keyboards();
            if (device_count > before)
                fprintf(stderr, "Now %d keyboard(s)\n", device_count);
            last_scan = now;
        }

        /* Build poll set: all keyboards + listen socket + client */
        struct pollfd pfds[MAX_DEVICES + 2];
        int nfds = 0;

        for (int i = 0; i < device_count; i++)
            pfds[nfds++] = (struct pollfd){
                .fd = devices[i].fd, .events = POLLIN };

        int listen_idx = nfds;
        pfds[nfds++] = (struct pollfd){ .fd = listen_fd, .events = POLLIN };

        int client_idx = -1;
        if (client_fd >= 0) {
            client_idx = nfds;
            pfds[nfds++] = (struct pollfd){ .fd = client_fd, .events = POLLIN };
        }

        if (poll(pfds, nfds, 1000) < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* Accept connection */
        if (pfds[listen_idx].revents & POLLIN) {
            int nfd = accept(listen_fd, NULL, NULL);
            if (nfd >= 0) {
                if (client_fd >= 0) close(client_fd);
                client_fd = nfd;
                fcntl(client_fd, F_SETFL,
                      fcntl(client_fd, F_GETFL) | O_NONBLOCK);
                line_buf_len = 0;
                fprintf(stderr, "Client connected\n");
                send_to_client("HELLO\n");
                send_to_client("DEVNODE %s\n",
                               libevdev_uinput_get_devnode(uidev));
            }
        }

        /* Client data */
        if (client_idx >= 0 && (pfds[client_idx].revents & POLLIN))
            process_client_data();

        /* Keyboard events from ALL devices */
        for (int d = 0; d < device_count; d++) {
            /* Device disconnected */
            if (pfds[d].revents & (POLLHUP | POLLERR)) {
                remove_device(d);
                d--; /* re-check this index after shift */
                continue;
            }

            if (!(pfds[d].revents & POLLIN)) continue;

            do {
                rc = libevdev_next_event(devices[d].dev,
                                         LIBEVDEV_READ_FLAG_NORMAL, &ev);
                if (rc == LIBEVDEV_READ_STATUS_SYNC) {
                    while (rc == LIBEVDEV_READ_STATUS_SYNC) {
                        libevdev_uinput_write_event(
                            uidev, ev.type, ev.code, ev.value);
                        rc = libevdev_next_event(devices[d].dev,
                                                 LIBEVDEV_READ_FLAG_SYNC, &ev);
                    }
                    continue;
                }
                if (rc < 0 && rc != -EAGAIN) {
                    /* Read error - device likely disconnected */
                    remove_device(d);
                    d--;
                    break;
                }
                if (rc < 0) break;
                if (ev.type == EV_KEY && ev.value == 2) continue;

                if (ev.type == EV_KEY && client_fd >= 0) {
                    send_to_client("EV %d %d\n", ev.code, ev.value);
                } else {
                    libevdev_uinput_write_event(
                        uidev, ev.type, ev.code, ev.value);
                }
            } while (rc == 0);
        }
    }

    fprintf(stderr, "\nShutting down...\n");
    return 0;
}
