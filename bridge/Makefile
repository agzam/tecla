CC ?= gcc
CFLAGS = -Wall -Wextra -O2
LDFLAGS = $(shell pkg-config --libs libevdev)
INCLUDES = $(shell pkg-config --cflags libevdev)

all: tecla-bridge tecla-test-kbd

tecla-bridge: tecla-bridge.c
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)

tecla-test-kbd: tecla-test-kbd.c
	$(CC) $(CFLAGS) $(INCLUDES) -o $@ $< $(LDFLAGS)

clean:
	rm -f tecla-bridge tecla-test-kbd

.PHONY: all clean
