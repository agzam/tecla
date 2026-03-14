/*
 * tecla-bridge - evdev/uinput keyboard bridge with socket control
 *
 * Grabs a keyboard via evdev, creates a virtual keyboard via uinput.
 * A Babashka process connects via Unix socket to configure remaps,
 * set a modal trigger key, and handle modal key events.
 *
 * Two modes:
 *   PASSTHROUGH - keys forwarded directly (with remap table applied)
 *   MODAL       - non-modifier keys sent to BB, BB emits via commands
 *
 * Usage:
 *   ./tecla-bridge "Adv360"                  # match device by name
 *   ./tecla-bridge /dev/input/event24        # direct device path
 *   ./tecla-bridge --list                    # list available devices
 *   ./tecla-bridge --timeout 60 --debug      # safety timeout + logging
 *   ./tecla-bridge --socket /tmp/tecla.sock  # custom socket path
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>

#include <libevdev/libevdev.h>
#include <libevdev/libevdev-uinput.h>
#include <linux/input.h>

/* ------------------------------------------------------------------ */
/* Globals                                                            */
/* ------------------------------------------------------------------ */

static volatile int running = 1;
static struct libevdev *dev = NULL;
static struct libevdev_uinput *uidev = NULL;
static int listen_fd = -1;
static int client_fd = -1;
static int debug = 0;
static char socket_path[108] = ""; /* matches sockaddr_un.sun_path */

/* Bridge mode */
enum { MODE_PASSTHROUGH, MODE_MODAL };
static int mode = MODE_PASSTHROUGH;

/* Modifier bitmask tracking (physical state) */
enum { MOD_CTRL = 1, MOD_SHIFT = 2, MOD_ALT = 4, MOD_META = 8 };
static int mod_state = 0;

/* Trigger key combo */
static int trigger_mod = 0;
static int trigger_key = 0;

/* Remap table */
#define MAX_REMAPS 128

static struct remap_entry {
    int from_mod, from_key;
    int to_mod, to_key;
    int active; /* 1 if this remap's key is currently held */
} remaps[MAX_REMAPS];
static int remap_count = 0;

/* ------------------------------------------------------------------ */
/* Helpers                                                            */
/* ------------------------------------------------------------------ */

static int is_modifier(int code) {
    return code == KEY_LEFTCTRL  || code == KEY_RIGHTCTRL
        || code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT
        || code == KEY_LEFTALT   || code == KEY_RIGHTALT
        || code == KEY_LEFTMETA  || code == KEY_RIGHTMETA;
}

static int mod_bit(int code) {
    switch (code) {
    case KEY_LEFTCTRL:  case KEY_RIGHTCTRL:  return MOD_CTRL;
    case KEY_LEFTSHIFT: case KEY_RIGHTSHIFT: return MOD_SHIFT;
    case KEY_LEFTALT:   case KEY_RIGHTALT:   return MOD_ALT;
    case KEY_LEFTMETA:  case KEY_RIGHTMETA:  return MOD_META;
    default: return 0;
    }
}

/* Map a modifier bitmask to a "left" keycode for emitting */
static int mod_to_keycode(int bit) {
    switch (bit) {
    case MOD_CTRL:  return KEY_LEFTCTRL;
    case MOD_SHIFT: return KEY_LEFTSHIFT;
    case MOD_ALT:   return KEY_LEFTALT;
    case MOD_META:  return KEY_LEFTMETA;
    default: return 0;
    }
}

static void emit_key(int code, int value) {
    libevdev_uinput_write_event(uidev, EV_KEY, code, value);
    libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
}

/* Release modifier keys on uinput for each bit set in mask */
static void release_mods(int mask) {
    for (int b = 1; b <= MOD_META; b <<= 1) {
        if (mask & b) emit_key(mod_to_keycode(b), 0);
    }
}

/* Press modifier keys on uinput for each bit set in mask */
static void press_mods(int mask) {
    for (int b = 1; b <= MOD_META; b <<= 1) {
        if (mask & b) emit_key(mod_to_keycode(b), 1);
    }
}

static void send_to_client(const char *fmt, ...) {
    if (client_fd < 0) return;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) write(client_fd, buf, n);
}

/* ------------------------------------------------------------------ */
/* Cleanup                                                            */
/* ------------------------------------------------------------------ */

static void cleanup(void) {
    if (client_fd >= 0) { close(client_fd); client_fd = -1; }
    if (listen_fd >= 0) { close(listen_fd); listen_fd = -1; }
    if (socket_path[0]) unlink(socket_path);
    if (uidev) {
        libevdev_uinput_destroy(uidev);
        uidev = NULL;
    }
    if (dev) {
        libevdev_grab(dev, LIBEVDEV_UNGRAB);
        int fd = libevdev_get_fd(dev);
        libevdev_free(dev);
        dev = NULL;
        if (fd >= 0) close(fd);
    }
}

static void signal_handler(int sig) {
    (void)sig;
    running = 0;
}

/* ------------------------------------------------------------------ */
/* Device detection                                                   */
/* ------------------------------------------------------------------ */

static int is_real_keyboard(struct libevdev *d) {
    return libevdev_has_event_type(d, EV_KEY)
        && libevdev_has_event_code(d, EV_KEY, KEY_A)
        && libevdev_has_event_code(d, EV_KEY, KEY_Z)
        && libevdev_has_event_code(d, EV_KEY, KEY_LEFTSHIFT)
        && libevdev_has_event_code(d, EV_KEY, KEY_LEFTCTRL)
        && libevdev_has_event_type(d, EV_LED)
        && libevdev_has_event_code(d, EV_LED, LED_CAPSL);
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
                   is_real_keyboard(d) ? " [keyboard]" : "");
            libevdev_free(d);
        }
        close(fd);
    }
}

static int find_keyboard(const char *name) {
    if (name && name[0] == '/') {
        int fd = open(name, O_RDONLY | O_NONBLOCK);
        if (fd < 0) {
            fprintf(stderr, "Cannot open %s: %s\n", name, strerror(errno));
            return -1;
        }
        fprintf(stderr, "Using device: %s\n", name);
        return fd;
    }
    char path[64];
    for (int i = 0; i < 64; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        struct libevdev *d = NULL;
        if (libevdev_new_from_fd(fd, &d) < 0) { close(fd); continue; }
        const char *dev_name = libevdev_get_name(d);
        int match = is_real_keyboard(d)
            && (name == NULL || (dev_name && strstr(dev_name, name)));
        if (match) {
            fprintf(stderr, "Found: %s (%s)\n", dev_name ? dev_name : "?", path);
            libevdev_free(d);
            return fd;
        }
        libevdev_free(d);
        close(fd);
    }
    return -1;
}

/* ------------------------------------------------------------------ */
/* Socket setup                                                       */
/* ------------------------------------------------------------------ */

static int setup_socket(const char *path) {
    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    snprintf(addr.sun_path, sizeof(addr.sun_path), "%s", path);

    unlink(path); /* remove stale socket */

    int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (fd < 0) {
        fprintf(stderr, "socket: %s\n", strerror(errno));
        return -1;
    }
    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        fprintf(stderr, "bind %s: %s\n", path, strerror(errno));
        close(fd);
        return -1;
    }
    if (listen(fd, 1) < 0) {
        fprintf(stderr, "listen: %s\n", strerror(errno));
        close(fd);
        return -1;
    }
    fprintf(stderr, "Listening on %s\n", path);
    return fd;
}

/* ------------------------------------------------------------------ */
/* Remap logic                                                        */
/* ------------------------------------------------------------------ */

/*
 * Try to apply a remap. Returns 1 if handled, 0 for passthrough.
 *
 * On key press: if (mod_state, keycode) matches a remap entry,
 * release the source modifiers, press the target modifiers + key.
 *
 * On key release: if a remap was active for this key,
 * release the target key + modifiers, restore source modifiers.
 */
static int try_remap(struct input_event *ev) {
    if (ev->type != EV_KEY) return 0;

    /* Key press - check for matching remap */
    if (ev->value == 1) {
        for (int i = 0; i < remap_count; i++) {
            if (remaps[i].from_key == ev->code
                && remaps[i].from_mod == mod_state) {
                release_mods(remaps[i].from_mod);
                press_mods(remaps[i].to_mod);
                emit_key(remaps[i].to_key, 1);
                remaps[i].active = 1;
                if (debug)
                    fprintf(stderr, "REMAP: mod=%d key=%d -> mod=%d key=%d\n",
                            remaps[i].from_mod, remaps[i].from_key,
                            remaps[i].to_mod, remaps[i].to_key);
                return 1;
            }
        }
        return 0;
    }

    /* Key release - check for active remap on this key */
    if (ev->value == 0) {
        for (int i = 0; i < remap_count; i++) {
            if (remaps[i].active && remaps[i].from_key == ev->code) {
                emit_key(remaps[i].to_key, 0);
                release_mods(remaps[i].to_mod);
                /* Restore source modifiers that are still physically held */
                press_mods(remaps[i].from_mod & mod_state);
                remaps[i].active = 0;
                return 1;
            }
        }
    }

    return 0;
}

/* ------------------------------------------------------------------ */
/* Command processing (from BB client)                                */
/* ------------------------------------------------------------------ */

static void process_command(char *line) {
    if (debug)
        fprintf(stderr, "CMD: %s\n", line);

    if (strncmp(line, "SET_TRIGGER ", 12) == 0) {
        int m, k;
        if (sscanf(line + 12, "%d %d", &m, &k) == 2) {
            trigger_mod = m;
            trigger_key = k;
            fprintf(stderr, "Trigger set: mod=%d key=%d\n", m, k);
        }
    }
    else if (strncmp(line, "ADD_REMAP ", 10) == 0) {
        int fm, fk, tm, tk;
        if (sscanf(line + 10, "%d %d %d %d", &fm, &fk, &tm, &tk) == 4) {
            if (remap_count < MAX_REMAPS) {
                remaps[remap_count] = (struct remap_entry){
                    .from_mod = fm, .from_key = fk,
                    .to_mod = tm, .to_key = tk,
                    .active = 0
                };
                remap_count++;
                fprintf(stderr, "Remap added: mod=%d key=%d -> mod=%d key=%d\n",
                        fm, fk, tm, tk);
            }
        }
    }
    else if (strcmp(line, "CLEAR_REMAPS") == 0) {
        remap_count = 0;
        fprintf(stderr, "Remaps cleared\n");
    }
    else if (strncmp(line, "EMIT ", 5) == 0) {
        int code, value;
        if (sscanf(line + 5, "%d %d", &code, &value) == 2) {
            libevdev_uinput_write_event(uidev, EV_KEY, code, value);
            libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
        }
    }
    else if (strcmp(line, "SYN") == 0) {
        libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
    }
    else if (strcmp(line, "PASSTHROUGH") == 0) {
        mode = MODE_PASSTHROUGH;
        /* Release all modifiers, re-press what's physically held */
        release_mods(MOD_CTRL | MOD_SHIFT | MOD_ALT | MOD_META);
        press_mods(mod_state);
        fprintf(stderr, "Mode: PASSTHROUGH\n");
    }
}

/* Line buffer for reading from client */
static char line_buf[512];
static int line_buf_len = 0;

static void process_client_data(void) {
    char buf[512];
    ssize_t n = read(client_fd, buf, sizeof(buf));
    if (n <= 0) {
        fprintf(stderr, "Client disconnected\n");
        close(client_fd);
        client_fd = -1;
        mode = MODE_PASSTHROUGH;
        line_buf_len = 0;
        return;
    }
    for (ssize_t i = 0; i < n; i++) {
        if (buf[i] == '\n') {
            line_buf[line_buf_len] = '\0';
            if (line_buf_len > 0)
                process_command(line_buf);
            line_buf_len = 0;
        } else if (line_buf_len < (int)sizeof(line_buf) - 1) {
            line_buf[line_buf_len++] = buf[i];
        }
    }
}

/* ------------------------------------------------------------------ */
/* Event processing                                                   */
/* ------------------------------------------------------------------ */

static void process_event(struct input_event *ev) {
    /* Skip key repeats - virtual keyboard generates its own */
    if (ev->type == EV_KEY && ev->value == 2)
        return;

    /* Track physical modifier state (before any remap logic) */
    if (ev->type == EV_KEY) {
        int b = mod_bit(ev->code);
        if (b) {
            if (ev->value == 1) mod_state |= b;
            else if (ev->value == 0) mod_state &= ~b;
        }
    }

    if (debug && ev->type == EV_KEY) {
        const char *name = libevdev_event_code_get_name(EV_KEY, ev->code);
        fprintf(stderr, "%-20s %s  mods=0x%x mode=%s\n",
                name ? name : "?",
                ev->value == 1 ? "DN" : "UP",
                mod_state,
                mode == MODE_MODAL ? "MODAL" : "PASS");
    }

    /* ---------- PASSTHROUGH mode ---------- */
    if (mode == MODE_PASSTHROUGH) {
        /* Check for trigger */
        if (ev->type == EV_KEY && ev->value == 1
            && trigger_key && ev->code == trigger_key
            && (mod_state & ~mod_bit(ev->code)) == trigger_mod) {
            /* Enter modal mode */
            mode = MODE_MODAL;
            /* Release trigger modifiers on uinput */
            release_mods(trigger_mod);
            fprintf(stderr, "Mode: MODAL\n");
            send_to_client("TRIGGER\n");
            return; /* swallow trigger key */
        }

        /* Try remaps, fall through to passthrough */
        if (ev->type == EV_KEY && try_remap(ev))
            return;

        /* Forward to uinput */
        libevdev_uinput_write_event(uidev, ev->type, ev->code, ev->value);
        return;
    }

    /* ---------- MODAL mode ---------- */
    /* Forward modifier keys to uinput (prevents stuck modifiers) */
    if (ev->type == EV_KEY && is_modifier(ev->code)) {
        libevdev_uinput_write_event(uidev, ev->type, ev->code, ev->value);
        return;
    }

    /* Forward non-key events (SYN, MSC, etc.) */
    if (ev->type != EV_KEY) {
        libevdev_uinput_write_event(uidev, ev->type, ev->code, ev->value);
        return;
    }

    /* Non-modifier key in modal: send to BB, don't forward to uinput */
    send_to_client("KEY %d %d\n", ev->code, ev->value);
}

/* ------------------------------------------------------------------ */
/* Main                                                               */
/* ------------------------------------------------------------------ */

int main(int argc, char **argv) {
    const char *device_name = NULL;
    const char *sock_path = NULL;
    int timeout_sec = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--list") == 0) { list_devices(); return 0; }
        else if (strcmp(argv[i], "--timeout") == 0 && i + 1 < argc)
            timeout_sec = atoi(argv[++i]);
        else if (strcmp(argv[i], "--debug") == 0)
            debug = 1;
        else if (strcmp(argv[i], "--socket") == 0 && i + 1 < argc)
            sock_path = argv[++i];
        else if (argv[i][0] != '-')
            device_name = argv[i];
    }

    /* Default socket path */
    if (!sock_path) {
        const char *runtime = getenv("XDG_RUNTIME_DIR");
        if (runtime)
            snprintf(socket_path, sizeof(socket_path), "%s/tecla-bridge.sock",
                     runtime);
        else
            snprintf(socket_path, sizeof(socket_path), "/tmp/tecla-bridge.sock");
    } else {
        strncpy(socket_path, sock_path, sizeof(socket_path) - 1);
    }

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);
    atexit(cleanup);

    /* Open keyboard */
    int fd = find_keyboard(device_name);
    if (fd < 0) {
        fprintf(stderr, "No keyboard found%s%s\n",
                device_name ? " matching: " : "",
                device_name ? device_name : "");
        fprintf(stderr, "Run with --list to see available devices.\n");
        return 1;
    }

    int rc = libevdev_new_from_fd(fd, &dev);
    if (rc < 0) {
        fprintf(stderr, "Failed to init evdev: %s\n", strerror(-rc));
        close(fd);
        return 1;
    }
    fprintf(stderr, "Device: %s\n", libevdev_get_name(dev));

    rc = libevdev_grab(dev, LIBEVDEV_GRAB);
    if (rc < 0) {
        fprintf(stderr, "Failed to grab: %s\n", strerror(-rc));
        return 1;
    }
    fprintf(stderr, "Grabbed exclusively\n");

    rc = libevdev_uinput_create_from_device(
        dev, LIBEVDEV_UINPUT_OPEN_MANAGED, &uidev);
    if (rc < 0) {
        fprintf(stderr, "Failed to create uinput device: %s\n", strerror(-rc));
        return 1;
    }
    fprintf(stderr, "Virtual device: %s\n",
            libevdev_uinput_get_devnode(uidev));

    /* Clear stale modifier state */
    static const int mod_keys[] = {
        KEY_LEFTCTRL, KEY_RIGHTCTRL, KEY_LEFTSHIFT, KEY_RIGHTSHIFT,
        KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA
    };
    for (int i = 0; i < (int)(sizeof(mod_keys) / sizeof(mod_keys[0])); i++)
        libevdev_uinput_write_event(uidev, EV_KEY, mod_keys[i], 0);
    libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);

    /* Setup socket */
    listen_fd = setup_socket(socket_path);
    if (listen_fd < 0) return 1;

    if (timeout_sec > 0)
        fprintf(stderr, "Auto-exit in %d seconds\n", timeout_sec);
    fprintf(stderr, "Ready. Waiting for client on %s\n", socket_path);

    /* Main loop */
    time_t start = time(NULL);
    struct input_event ev;

    while (running) {
        if (timeout_sec > 0 && (time(NULL) - start) >= timeout_sec) {
            fprintf(stderr, "\nTimeout reached.\n");
            break;
        }

        /* Build poll set: evdev + listen socket + client (if connected) */
        struct pollfd pfds[3];
        int nfds = 0;

        pfds[nfds++] = (struct pollfd){
            .fd = libevdev_get_fd(dev), .events = POLLIN };
        pfds[nfds++] = (struct pollfd){
            .fd = listen_fd, .events = POLLIN };
        int client_pfd_idx = -1;
        if (client_fd >= 0) {
            client_pfd_idx = nfds;
            pfds[nfds++] = (struct pollfd){
                .fd = client_fd, .events = POLLIN };
        }

        int prc = poll(pfds, nfds, 1000);
        if (prc < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "Poll error: %s\n", strerror(errno));
            break;
        }
        if (prc == 0) continue;

        /* Accept new client connection */
        if (pfds[1].revents & POLLIN) {
            int new_fd = accept(listen_fd, NULL, NULL);
            if (new_fd >= 0) {
                if (client_fd >= 0) {
                    fprintf(stderr, "Replacing existing client\n");
                    close(client_fd);
                }
                client_fd = new_fd;
                /* Set non-blocking */
                fcntl(client_fd, F_SETFL,
                      fcntl(client_fd, F_GETFL) | O_NONBLOCK);
                mode = MODE_PASSTHROUGH;
                remap_count = 0;
                trigger_key = 0;
                trigger_mod = 0;
                line_buf_len = 0;
                fprintf(stderr, "Client connected\n");
                send_to_client("HELLO %s\n", libevdev_get_name(dev));
            }
        }

        /* Read commands from client */
        if (client_pfd_idx >= 0 && (pfds[client_pfd_idx].revents & POLLIN))
            process_client_data();

        /* Read keyboard events */
        if (pfds[0].revents & POLLIN) {
            do {
                rc = libevdev_next_event(dev, LIBEVDEV_READ_FLAG_NORMAL, &ev);
                if (rc == LIBEVDEV_READ_STATUS_SYNC) {
                    while (rc == LIBEVDEV_READ_STATUS_SYNC) {
                        libevdev_uinput_write_event(
                            uidev, ev.type, ev.code, ev.value);
                        rc = libevdev_next_event(
                            dev, LIBEVDEV_READ_FLAG_SYNC, &ev);
                    }
                    continue;
                }
                if (rc < 0) break;
                process_event(&ev);
            } while (rc == 0);

            if (rc < 0 && rc != -EAGAIN) {
                fprintf(stderr, "Read error: %s\n", strerror(-rc));
                break;
            }
        }
    }

    fprintf(stderr, "\nShutting down...\n");
    return 0;
}
