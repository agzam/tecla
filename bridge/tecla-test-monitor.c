/*
 * tecla-test-monitor - grabs a device and prints its EV_KEY events to stdout
 * as "CODE VALUE" lines, so tests can check what the bridge's virtual
 * keyboard emits. The grab keeps that output away from a running compositor.
 * Usage: tecla-test-monitor /dev/input/event25
 */

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <libevdev/libevdev.h>
#include <linux/input.h>

static volatile int running = 1;

static void signal_handler(int sig) {
    (void)sig;
    running = 0;
}

int main(int argc, char **argv) {
    if (argc < 2) {
        fprintf(stderr, "Usage: %s /dev/input/eventN\n", argv[0]);
        return 1;
    }

    signal(SIGTERM, signal_handler);
    signal(SIGINT, signal_handler);

    int fd = open(argv[1], O_RDONLY | O_NONBLOCK);
    if (fd < 0) {
        fprintf(stderr, "Cannot open %s: %s\n", argv[1], strerror(errno));
        return 1;
    }

    struct libevdev *dev = NULL;
    int rc = libevdev_new_from_fd(fd, &dev);
    if (rc < 0) {
        fprintf(stderr, "Failed to init evdev: %s\n", strerror(-rc));
        close(fd);
        return 1;
    }

    rc = libevdev_grab(dev, LIBEVDEV_GRAB);
    if (rc < 0) {
        fprintf(stderr, "Cannot grab %s: %s\n", argv[1], strerror(-rc));
        libevdev_free(dev);
        close(fd);
        return 1;
    }

    fprintf(stderr, "monitor: watching %s (%s)\n",
            argv[1], libevdev_get_name(dev));

    /* Signal readiness to parent process */
    printf("READY\n");
    fflush(stdout);

    struct input_event ev;
    struct pollfd pfd = { .fd = fd, .events = POLLIN };

    while (running) {
        int prc = poll(&pfd, 1, 500);
        if (prc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (prc == 0) continue;

        do {
            rc = libevdev_next_event(dev, LIBEVDEV_READ_FLAG_NORMAL, &ev);
            if (rc == -ENODEV) running = 0; /* device removed */
            if (rc < 0) break;

            if (ev.type == EV_KEY) {
                printf("%d %d\n", ev.code, ev.value);
                fflush(stdout);
            }
        } while (rc == 0);
    }

    libevdev_free(dev);
    close(fd);
    return 0;
}
