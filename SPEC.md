# Tecla - Specification

A modal keyboard toolkit for Linux. Compositor-agnostic, evdev-based key
dispatcher with a finite state machine allowing nested keymaps, transient
submenus, and global key remapping. Two-process architecture: a C bridge for
low-latency evdev/uinput I/O, and Babashka (Clojure) for all keyboard logic,
FSM, config, and REPL-driven development via nREPL.

## Goals

- Work on any Linux distribution, any Wayland compositor (Gnome, Hyprland,
  Sway, Niri, KDE, etc.), and X11
- Provide nested modal menus activated by a trigger key (e.g., Super+Space)
- Support global key remaps (e.g., Super+C -> Ctrl+C for Mac-like bindings)
- Support per-application keymaps that activate based on focused window
- Be configurable with plain Clojure data (EDN maps, keywords, vectors)
- Support live development via nREPL (connect from Emacs/CIDER, modify keymaps
  and behavior at runtime)

## Non-goals

- macOS support (spacehammer covers that; the I/O layer shares zero code)
- Graphical overlay/menu display beyond the built-in GTK4 which-key HUD
- Mouse remapping
- Hardware-level key remapping (firmware, QMK, etc.)

## Architecture

### Two-process design

```
Physical KBs (all)
  -> /dev/input/event*
  -> [C bridge: evdev grab ALL + read]
  -> Unix socket (EV lines)
  -> [Babashka: remap + FSM + modifier tracking]
  -> Unix socket (EM lines)
  -> [C bridge: uinput write]
  -> virtual keyboard
  -> compositor
```

1. **tecla-bridge** (C, ~200 lines) - a dumb I/O pipe. Grabs every physical
   keyboard via libevdev, creates a single virtual keyboard via uinput. When a
   client is connected, all EV_KEY events are forwarded as `EV` messages over
   the Unix socket; the bridge emits nothing to uinput on its own. When no
   client is connected, events are passed through directly. The bridge has no
   remap tables, no modal mode, no keyboard logic whatsoever.

2. **tecla** (Babashka) - connects to the bridge, receives every raw key
   event, and decides what to emit. Handles modifier tracking, modifier
   buffering, remap matching, trigger detection, and the modal FSM. Sends `EM`
   commands back to the bridge to synthesize output. Provides nREPL for live
   development.

The bridge requires access to `/dev/input/` and `/dev/uinput` - either as root
or via `input` + `uinput` group membership. The Babashka process runs
unprivileged.

### Socket protocol

Newline-delimited text over a Unix domain socket.
Default: `$XDG_RUNTIME_DIR/tecla-bridge.sock`.

Bridge -> Client:
- `HELLO` - sent on connection
- `DEVNODE <path>` - the virtual keyboard device node
- `EV <code> <value>` - raw key event (code = evdev keycode, value = 1 press /
  0 release; repeats are dropped)

Client -> Bridge:
- `EM <code> <value>` - emit key event to virtual keyboard

That is the entire protocol.

### FSM engine

Pure functions in `tecla.fsm`, no I/O.

```
                ┌──────────────────────────┐
                │                          │
                v                          │
  ┌───────┐  trigger  ┌────────┐  escape  │
  │ :idle │ --------> │ :modal │ ---------─┘
  └───────┘           └────────┘
                        │    ^
                  key   │    │ escape
                  with  │    │
                  :keys │    │
                        v    │
                      ┌────────┐
                      │ :modal │  (child - recursive)
                      └────────┘
```

States:
- `:idle` - remaps applied by Babashka
- `:modal` - keys matched against current keymap level

FSM state shape:
```clojure
{:mode       :idle | :modal
 :menu-stack [keymap ...]
 :config     {...}
 :effect     nil | effect}
```

Effects (returned by FSM, executed by `tecla.core`):
- `{:type :enter-modal}`
- `{:type :exit-modal}`
- `{:type :action :action ...}` - transient, stay in modal
- `{:type :action-and-exit :action ...}` - execute and exit

### Keyboard state (tecla.core)

All keyboard logic is in Babashka. The `kbd-state` atom:

```clojure
{:mod-state 0          ; physical modifier bitmask
 :buffered-mods 0      ; mods pressed but not yet emitted
 :consumed-mods 0      ; mods consumed by remap/trigger
 :active-remaps {}     ; key-code -> remap entry for release tracking
 :remap-mods 0}        ; union of all modifier bits used in remaps + trigger
```

Modifier buffering: remap-eligible modifiers are held until the next
non-modifier key reveals whether a remap matches. If match: source mod consumed,
target mod+key emitted. If no match: buffered mods flushed, key passed through.

### Compositor adapter - DEFERRED

Needed only for per-app keymaps. Planned adapters: Hyprland, Sway, Gnome, KDE,
Niri, generic.

### Action system

Dispatched by `tecla.core/execute-action`:
- String: shell command via `sh -c`
- Function: called directly
- Keyword: action registry (not yet implemented)
- Vector `[:key mods key]`: synthesize key combo via EM commands

## Configuration

EDN file at `~/.config/tecla/config.edn`. If the file does not exist on first
run, it is copied from `config.example.edn`.

The bridge grabs all keyboards automatically - no `:device` key needed.

```clojure
{:global-keymap
 {;; Remaps (active in passthrough mode)
  [:super :c] [:ctrl :c]
  [:super :v] [:ctrl :v]

  ;; Modal trigger + menu tree
  [:super :space]
  {:desc "Main"
   :keys
   {:a {:desc "Apps"
        :keys {:e {:desc "Emacs"
                   :action (wm/focus-or-launch {:class "Emacs" :command "emacs"})}
               [:shift :b] {:desc "Brave Private"
                            :action (wm/focus-or-launch
                                     {:class "initialTitle:New Private Tab.*"
                                      :command "brave --incognito"})}}}
    :w {:desc "Windows"
        :keys {:h {:desc "Left" :action (wm/focus-left)}}}}}}}
```

Keymap entries: leaf `{:desc "..." :action ...}` or submenu
`{:desc "..." :keys {...}}`. Keys are keywords (`:a`, `:space`) or
modifier+key vectors (`[:shift :b]`, `[:ctrl :shift :x]`).

`wm/focus-or-launch` accepts a map with `:class` and optional `:command`
(defaults to `:class`). Legacy positional syntax also works:
`(wm/focus-or-launch "kitty")` or `(wm/focus-or-launch "kitty" "kitty -T foo")`.

## Module structure

```
tecla/
├── bridge/
│   ├── tecla-bridge.c          ; C bridge: grab all KBs, EV/EM socket I/O
│   ├── tecla-test-kbd.c        ; virtual keyboard for tests
│   ├── tecla-test-monitor.c    ; output observer for tests
│   └── Makefile
├── hud/
│   └── tecla-hud-gtk.js        ; GTK4 which-key overlay (gjs)
├── src/tecla/
│   ├── core.clj                ; entry point, all keyboard logic, nREPL
│   ├── fsm.clj                 ; modal state machine (pure functions)
│   ├── bridge.clj              ; socket client, bridge process management
│   ├── keys.clj                ; keycode <-> keyword tables
│   ├── hud.clj                 ; which-key HUD poller (drives GTK process)
│   ├── wm.clj                  ; compositor-agnostic WM multimethod
│   └── wm/
│       ├── hyprland.clj        ; Hyprland backend (IPC socket + cheshire)
│       └── gnome.clj           ; GNOME backend (window-calls extension)
├── test/
│   ├── fsm_test.clj            ; FSM unit tests
│   ├── core_test.clj           ; key processing + modal modifier tests
│   ├── hud_test.clj            ; HUD poller tests
│   └── bridge_test.clj         ; bridge integration tests
├── config.example.edn
├── bb.edn
└── SPEC.md
```

## Runtime lifecycle

1. `bb run` builds bridge, copies config.example.edn if needed, detects
   compositor, starts bridge process, connects socket, starts HUD (GTK4),
   starts nREPL (port 7890)
2. Bridge scans and grabs all keyboards, creates virtual keyboard, listens on
   socket, watches /dev/input via inotify for hot-plug
3. Every key event: bridge sends `EV` -> Babashka processes (remap/modal/
   passthrough) -> sends `EM` commands back
4. Shutdown: bridge releases grabs, destroys uinput; Babashka disconnects, stops
   nREPL, kills HUD

## Testing

- `bb test:fsm` - pure FSM tests, instant, no hardware
- `bb test:bridge` - integration tests with virtual keyboard + monitor (needs
  uinput access)
- CI: GitHub Actions runs both

## Known issues

### Rapid-fire key repeats

When Tecla is active, holding a key can produce repeats at a different rate than
the physical keyboard's configured rate/delay - often much faster, making normal
typing difficult.

**Analysis (from studying keyd, kanata, evremap):**

Both keyd and kanata do all event processing in a single process with no IPC.
keyd enables EV_REP on its virtual keyboard; kanata (like Tecla) disables it
and drops value=2, relying on the compositor for repeat. Both work fine - the
common factor is that the event loop is never blocked.

Tecla's bridge had a 3-second periodic rescan (`grab_keyboards()`) that
iterated all 64 /dev/input/event* paths with open/stat/grab syscalls. This
blocked the main event loop, causing kernel events to pile up in evdev buffers
and burst-deliver when the scan completed. This is the likely cause of the
intermittent "dozens of characters from a single tap" issue.

Forwarding value=2 events for passthrough keys was tried but made the burst
problem worse - piled-up autorepeat events from the physical keyboard's kernel
timer would all be forwarded during the burst.

**Current approach:**

- Drop value=2 events (autorepeat) from all physical keyboards. The
  compositor handles repeat for the virtual keyboard in userspace.
- EV_REP is disabled on the virtual keyboard to avoid double-repeat.
- EVIOCSREP ioctl zeroes repeat params as belt-and-suspenders.
- Device hot-plug uses inotify on /dev/input instead of blocking periodic
  rescan. The main event loop is never stalled by device enumeration.

## Not yet implemented

- Per-app keymaps (compositor adapter detects focused window, merges local keymap)
- Action keyword registry
- Ctrl+g as modal escape
- systemd unit file
