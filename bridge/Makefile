CC ?= gcc
CFLAGS = -Wall -Wextra -O2
LDFLAGS = $(shell pkg-config --libs libevdev)
INCLUDES = $(shell pkg-config --cflags libevdev)

all: tecla-bridge tecla-test-kbd tecla-test-monitor

tecla-bridge: tecla-bridge.c
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)
	chmod 755 $@

tecla-test-kbd: tecla-test-kbd.c
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)
	chmod 755 $@

tecla-test-monitor: tecla-test-monitor.c
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)
	chmod 755 $@

clean:
	rm -f tecla-bridge tecla-test-kbd tecla-test-monitor

.PHONY: all clean
