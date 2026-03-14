/*
 * tecla-test-kbd - virtual keyboard for testing
 *
 * Creates a uinput device that looks like a real keyboard.
 * Prints the device path to stdout, then reads lines from stdin:
 *   <keycode> <value>\n
 * and injects the corresponding key events.
 *
 * value: 1 = press, 0 = release
 *
 * Exits when stdin closes or on SIGTERM.
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

static void cleanup(void) {
    if (uidev) {
        libevdev_uinput_destroy(uidev);
        uidev = NULL;
    }
}

static volatile int running = 1;

static void signal_handler(int sig) {
    (void)sig;
    running = 0;
}

int main(void) {
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

    const char *devnode = libevdev_uinput_get_devnode(uidev);

    /* Print device path to stdout so the test harness can find it */
    printf("%s\n", devnode);
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
