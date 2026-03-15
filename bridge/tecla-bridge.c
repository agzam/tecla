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
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>

#include <libevdev/libevdev.h>
#include <libevdev/libevdev-uinput.h>
#include <linux/input.h>

#define MAX_DEVICES 16
#define VKBD_NAME "tecla-virtual-keyboard"

static volatile int running = 1;
static int debug = 0;
static int allow_virtual = 0;   /* --allow-virtual: skip phys check (for tests) */
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

/* ---- Key state de-duplication across multiple devices ---- */
static char key_state[KEY_MAX + 1];   /* physical key state: 0=up, 1=down */

/* ---- Fast-path passthrough state ---- */

#define MAX_BINDINGS 256

static struct { int mod_mask; int keycode; } fp_bindings[MAX_BINDINGS];
static int fp_binding_count = 0;
static int fp_intercept_mod_mask = 0;  /* union of mod bits from all bindings */
static int fp_mod_state = 0;           /* current modifier state tracked here */
static char fp_sent[KEY_MAX + 1];      /* per-keycode: was last press sent to client? */
static int fp_active = 0;             /* 1 = passthrough mode, 0 = intercept all */

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

static int send_to_client(const char *fmt, ...) {
    if (client_fd < 0) return -1;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return -1;

    int written = 0;
    while (written < n) {
        ssize_t w = write(client_fd, buf + written, n - written);
        if (w > 0) {
            written += w;
        } else if (w < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                /* Brief wait; keep short to avoid stalling the event loop */
                struct pollfd pfd = { .fd = client_fd, .events = POLLOUT };
                if (poll(&pfd, 1, 5) <= 0) {
                    fprintf(stderr, "Client write timeout, dropping\n");
                    return -1;
                }
                continue;
            }
            fprintf(stderr, "Client write error: %s\n", strerror(errno));
            close(client_fd); client_fd = -1;
            return -1;
        }
    }
    return 0;
}

/* ---- Fast-path helpers ---- */

static int fp_mod_bit(int code) {
    switch (code) {
        case KEY_LEFTCTRL:  case KEY_RIGHTCTRL:  return 1;
        case KEY_LEFTSHIFT: case KEY_RIGHTSHIFT: return 2;
        case KEY_LEFTALT:   case KEY_RIGHTALT:   return 4;
        case KEY_LEFTMETA:  case KEY_RIGHTMETA:  return 8;
        default: return 0;
    }
}

static int fp_has_nomods_binding(int keycode) {
    for (int i = 0; i < fp_binding_count; i++)
        if (fp_bindings[i].mod_mask == 0 && fp_bindings[i].keycode == keycode)
            return 1;
    return 0;
}

static void fp_emit(int code, int value) {
    libevdev_uinput_write_event(uidev, EV_KEY, code, value);
    libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
}

static void fp_reset(void) {
    fp_binding_count = 0;
    fp_intercept_mod_mask = 0;
    fp_mod_state = 0;
    fp_active = 0;
    memset(fp_sent, 0, sizeof(fp_sent));
}

static void fp_release_stuck(void) {
    int any = 0;
    for (int i = 0; i <= KEY_MAX; i++) {
        if (fp_sent[i]) {
            libevdev_uinput_write_event(uidev, EV_KEY, i, 0);
            fp_sent[i] = 0;
            any = 1;
        }
    }
    if (any)
        libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
}

static void fp_route_key(int code, int value) {
    if (!fp_active) {
        /* Intercept-all mode: everything to client, but track state */
        int rc = send_to_client("EV %d %d\n", code, value);
        int mbit = fp_mod_bit(code);
        if (mbit) {
            if (value) fp_mod_state |= mbit;
            else       fp_mod_state &= ~mbit;
        }
        if (value == 1) {
            fp_sent[code] = 1;
        } else if (value == 0) {
            /* If send failed for a release, emit directly to prevent
             * the key getting stuck in the pressed state. */
            if (rc < 0) fp_emit(code, 0);
            fp_sent[code] = 0;
        }
        return;
    }

    /* Fast-path passthrough mode */
    int mbit = fp_mod_bit(code);
    int to_client = 0;

    if (value == 1) { /* press */
        if (mbit) {
            fp_mod_state |= mbit;
            to_client = (mbit & fp_intercept_mod_mask) != 0;
        } else {
            to_client = ((fp_mod_state & fp_intercept_mod_mask) != 0)
                     || fp_has_nomods_binding(code);
        }
        fp_sent[code] = to_client;
        if (to_client) send_to_client("EV %d %d\n", code, value);
        else           fp_emit(code, value);
    } else { /* release */
        if (mbit) fp_mod_state &= ~mbit;
        if (fp_sent[code]) {
            if (send_to_client("EV %d %d\n", code, value) < 0)
                fp_emit(code, 0);   /* safety release */
            fp_sent[code] = 0;
        } else {
            fp_emit(code, value);
        }
    }
}

/* ---- Device detection ---- */

static int is_keyboard(struct libevdev *d) {
    /* Skip any uinput / virtual device: they have no physical bus path.
     * This catches our own device, Espanso, ydotool, etc. regardless
     * of what bus type they claim (many copy BUS_USB from the source). */
    if (!allow_virtual) {
        const char *phys = libevdev_get_phys(d);
        if (!phys || phys[0] == '\0')
            return 0;
    }

    /* Belt-and-suspenders: also skip by name and declared bus type */
    const char *name = libevdev_get_name(d);
    if (name && strcmp(name, VKBD_NAME) == 0)
        return 0;
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
    } else if (strncmp(line, "BIND ", 5) == 0) {
        int mod_mask, keycode;
        if (sscanf(line + 5, "%d %d", &mod_mask, &keycode) == 2
            && fp_binding_count < MAX_BINDINGS) {
            fp_bindings[fp_binding_count].mod_mask = mod_mask;
            fp_bindings[fp_binding_count].keycode = keycode;
            fp_binding_count++;
            fp_intercept_mod_mask |= mod_mask;
        }
    } else if (strcmp(line, "PASSTHROUGH") == 0) {
        fp_active = 1;
    } else if (strcmp(line, "INTERCEPT") == 0) {
        fp_active = 0;
    } else if (strcmp(line, "CLEAR") == 0) {
        fp_binding_count = 0;
        fp_intercept_mod_mask = 0;
        fp_active = 0;
    }
}

static void process_client_data(void) {
    char buf[512];
    ssize_t n = read(client_fd, buf, sizeof(buf));
    if (n <= 0) {
        fprintf(stderr, "Client disconnected\n");
        close(client_fd); client_fd = -1;
        line_buf_len = 0;
        fp_release_stuck();
        fp_reset();
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
        else if (strcmp(argv[i], "--debug") == 0) debug = 1;
        else if (strcmp(argv[i], "--allow-virtual") == 0) allow_virtual = 1;
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

    /* Give the virtual keyboard a recognisable name so is_keyboard()
     * can skip it during rescan.  libevdev_uinput_create_from_device
     * copies the source device's input_id (including bus type), so
     * the BUS_VIRTUAL check alone is not enough. */
    const char *orig_name = libevdev_get_name(devices[0].dev);
    libevdev_set_name(devices[0].dev, VKBD_NAME);

    /* Disable autorepeat on virtual keyboard.
     * On Wayland the compositor handles repeat via its own timer;
     * kernel-level EV_REP on the virtual device just adds a second
     * source of repeats that races with the round-trip latency. */
    int had_rep = libevdev_has_event_type(devices[0].dev, EV_REP);
    if (had_rep)
        libevdev_disable_event_type(devices[0].dev, EV_REP);

    int rc = libevdev_uinput_create_from_device(
        devices[0].dev, LIBEVDEV_UINPUT_OPEN_MANAGED, &uidev);

    /* Restore the grabbed device's original name and repeat cap */
    if (orig_name)
        libevdev_set_name(devices[0].dev, orig_name);
    if (had_rep)
        libevdev_enable_event_type(devices[0].dev, EV_REP);

    if (rc < 0) {
        fprintf(stderr, "uinput: %s\n", strerror(-rc));
        return 1;
    }
    const char *vdev = libevdev_uinput_get_devnode(uidev);
    fprintf(stderr, "Virtual: %s\n", vdev);

    /* Belt-and-suspenders: force kernel autorepeat off on the virtual
     * device.  Some kernel versions re-enable EV_REP for keyboard-like
     * uinput devices even if we didn't request it.  Setting both
     * delay and period to 0 disables the kernel repeat timer. */
    { int vfd = open(vdev, O_WRONLY);
      if (vfd >= 0) {
          int rep[2] = {0, 0};
          if (ioctl(vfd, EVIOCSREP, rep) < 0)
              fprintf(stderr, "  EVIOCSREP: %s (non-fatal)\n", strerror(errno));
          else
              fprintf(stderr, "  Kernel autorepeat disabled on virtual kbd\n");
          close(vfd);
      }
    }

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
                if (client_fd >= 0) {
                    fp_release_stuck();
                    close(client_fd);
                }
                client_fd = nfd;
                fcntl(client_fd, F_SETFL,
                      fcntl(client_fd, F_GETFL) | O_NONBLOCK);
                line_buf_len = 0;
                fp_reset();
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
                        if (ev.type != EV_KEY) {
                            if (client_fd < 0)
                                libevdev_uinput_write_event(
                                    uidev, ev.type, ev.code, ev.value);
                        } else if (ev.value == 2) {
                            /* skip autorepeat during sync */
                        } else if ((ev.value == 1 && key_state[ev.code]) ||
                                   (ev.value == 0 && !key_state[ev.code])) {
                            /* skip duplicate */
                        } else {
                            key_state[ev.code] = (ev.value == 1);
                            if (client_fd >= 0)
                                fp_route_key(ev.code, ev.value);
                            else
                                libevdev_uinput_write_event(
                                    uidev, ev.type, ev.code, ev.value);
                        }
                        rc = libevdev_next_event(devices[d].dev,
                                                 LIBEVDEV_READ_FLAG_SYNC, &ev);
                    }
                    continue;
                }
                if (rc < 0 && rc != -EAGAIN) {
                    remove_device(d);
                    d--;
                    break;
                }
                if (rc < 0) break;
                if (ev.type != EV_KEY) {
                    /* Non-key events: only pass through when no client
                     * (client mode uses fp_emit/process_command which
                     *  generate their own SYN_REPORT) */
                    if (client_fd < 0)
                        libevdev_uinput_write_event(
                            uidev, ev.type, ev.code, ev.value);
                    continue;
                }
                if (ev.value == 2) continue; /* drop autorepeat */

                /* De-duplicate: if multiple devices report the same
                 * physical key, only the first event gets through. */
                if (ev.value == 1 && key_state[ev.code]) {
                    if (debug)
                        fprintf(stderr, "  DUP dev%d key %d press\n",
                                d, ev.code);
                    continue;
                }
                if (ev.value == 0 && !key_state[ev.code]) {
                    if (debug)
                        fprintf(stderr, "  DUP dev%d key %d release\n",
                                d, ev.code);
                    continue;
                }
                key_state[ev.code] = (ev.value == 1);

                if (debug)
                    fprintf(stderr, "  dev%d: key %d %s -> %s\n",
                            d, ev.code, ev.value ? "DN" : "UP",
                            (client_fd < 0) ? "passthru" :
                            (fp_active ? "fastpath" : "client"));

                if (client_fd >= 0) {
                    fp_route_key(ev.code, ev.value);
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
