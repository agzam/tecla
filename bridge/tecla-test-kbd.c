/*
 * tecla-test-kbd - uinput keyboard for tests. Prints its device path, then
 * injects "<keycode> <value>" lines from stdin (1 = press, 0 = release).
 * --mouse adds an idle pointer and prints its path on a second line.
 * Neither has a phys path: a tecla-bridge serving this desktop skips them.
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libevdev/libevdev.h>
#include <libevdev/libevdev-uinput.h>
#include <linux/input.h>

static struct libevdev_uinput *uidev = NULL;
static struct libevdev_uinput *umouse = NULL;

static void cleanup(void) {
    if (uidev) {
        libevdev_uinput_destroy(uidev);
        uidev = NULL;
    }
    if (umouse) {
        libevdev_uinput_destroy(umouse);
        umouse = NULL;
    }
}

static volatile int running = 1;

static void signal_handler(int sig) {
    (void)sig;
    running = 0;
}

/* The bridge creates its virtual mouse only after it grabs a mouse */
static int create_mouse(void) {
    struct libevdev *dev = libevdev_new();
    libevdev_set_name(dev, "tecla-test-mouse");
    libevdev_set_id_bustype(dev, BUS_USB);
    libevdev_enable_event_type(dev, EV_REL);
    libevdev_enable_event_code(dev, EV_REL, REL_X, NULL);
    libevdev_enable_event_code(dev, EV_REL, REL_Y, NULL);
    libevdev_enable_event_type(dev, EV_KEY);
    libevdev_enable_event_code(dev, EV_KEY, BTN_LEFT, NULL);

    int rc = libevdev_uinput_create_from_device(
        dev, LIBEVDEV_UINPUT_OPEN_MANAGED, &umouse);
    libevdev_free(dev);
    return rc;
}

int main(int argc, char **argv) {
    int with_mouse = argc > 1 && strcmp(argv[1], "--mouse") == 0;

    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);
    atexit(cleanup);

    struct libevdev *dev = libevdev_new();
    libevdev_set_name(dev, "tecla-test-keyboard");
    libevdev_set_id_bustype(dev, BUS_USB);

    /* Enable key event type */
    libevdev_enable_event_type(dev, EV_KEY);

    /* Add all standard keys */
    for (int k = KEY_ESC; k <= KEY_F12; k++)
        libevdev_enable_event_code(dev, EV_KEY, k, NULL);
    for (int k = KEY_HOME; k <= KEY_DELETE; k++)
        libevdev_enable_event_code(dev, EV_KEY, k, NULL);
    /* Modifiers */
    libevdev_enable_event_code(dev, EV_KEY, KEY_LEFTCTRL, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_RIGHTCTRL, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_LEFTSHIFT, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_RIGHTSHIFT, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_LEFTALT, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_RIGHTALT, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_LEFTMETA, NULL);
    libevdev_enable_event_code(dev, EV_KEY, KEY_RIGHTMETA, NULL);

    /* LEDs so it passes is_real_keyboard check */
    libevdev_enable_event_type(dev, EV_LED);
    libevdev_enable_event_code(dev, EV_LED, LED_CAPSL, NULL);
    libevdev_enable_event_code(dev, EV_LED, LED_NUML, NULL);

    /* SYN */
    libevdev_enable_event_type(dev, EV_SYN);

    /* Repeat */
    libevdev_enable_event_type(dev, EV_REP);

    int rc = libevdev_uinput_create_from_device(
        dev, LIBEVDEV_UINPUT_OPEN_MANAGED, &uidev);
    libevdev_free(dev);

    if (rc < 0) {
        fprintf(stderr, "Failed to create uinput: %s\n", strerror(-rc));
        return 1;
    }

    if (with_mouse && (rc = create_mouse()) < 0) {
        fprintf(stderr, "Failed to create mouse: %s\n", strerror(-rc));
        return 1;
    }

    const char *devnode = libevdev_uinput_get_devnode(uidev);

    /* Print device paths to stdout so the test harness can find them */
    printf("%s\n", devnode);
    if (umouse)
        printf("%s\n", libevdev_uinput_get_devnode(umouse));
    fflush(stdout);

    fprintf(stderr, "test-kbd: ready at %s\n", devnode);

    /* Read commands from stdin */
    char line[128];
    while (running && fgets(line, sizeof(line), stdin)) {
        int code, value;
        if (sscanf(line, "%d %d", &code, &value) == 2) {
            libevdev_uinput_write_event(uidev, EV_KEY, code, value);
            libevdev_uinput_write_event(uidev, EV_SYN, SYN_REPORT, 0);
        }
    }

    fprintf(stderr, "test-kbd: exiting\n");
    return 0;
}
