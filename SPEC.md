# Tecla - Specification

A modal keyboard toolkit for Linux. Compositor-agnostic, evdev-based key
dispatcher with a finite state machine allowing nested keymaps, app-local
bindings, and global key remapping. Two-process architecture: a C bridge for
low-latency evdev/uinput I/O, and Babashka (Clojure) for FSM logic, config,
and REPL-driven development via nREPL.

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

- macOS support (spacehammer covers that; the core FSM/keymap logic is naturally
  portable, but the I/O layer - evdev/uinput vs CGEventTap/DriverKit - shares
  zero code, so cross-platform is not worth the complexity)
- Graphical overlay/menu display (may be added later via external process, e.g.,
  eww, rofi)
- Mouse remapping
- Hardware-level key remapping (firmware, QMK, etc.)

## Architecture

### Two-process design

```
Physical KB
  -> /dev/input/eventN
  -> [C bridge: evdev grab + read]
  -> Unix socket
  -> [Babashka: FSM engine]
  -> Unix socket
  -> [C bridge: uinput write]
  -> virtual keyboard
  -> compositor
```

1. **tecla-bridge** (C) - grabs the physical keyboard via libevdev, creates a
   virtual keyboard via uinput. Operates in two modes:
   - **Passthrough** - forwards events directly with remap table applied. Zero
     Babashka involvement, sub-microsecond latency.
   - **Modal** - non-modifier keys are sent to Babashka as `KEY` messages over
     the Unix socket. Modifier keys are still forwarded to uinput. Babashka
     sends `EMIT`/`PASSTHROUGH` commands back.

2. **tecla** (Babashka) - connects to the bridge, configures remap tables and
   the modal trigger key via socket commands, runs the FSM when modal is active,
   provides nREPL for live development.

The bridge requires access to `/dev/input/` and `/dev/uinput` - either as root
or via `input` + `uinput` group membership. The Babashka process runs
unprivileged.

### Socket protocol

Newline-delimited text messages over a Unix domain socket. Default path:
`$XDG_RUNTIME_DIR/tecla-bridge.sock` (fallback: `/tmp/tecla-bridge.sock`).

Bridge -> Babashka:
- `HELLO <device-name>` - sent on client connection
- `TRIGGER` - modal trigger key combo detected, bridge entered modal mode
- `KEY <code> <value>` - key event in modal mode (code is evdev keycode,
  value is 1=press, 0=release)

Babashka -> Bridge:
- `SET_TRIGGER <mod_bits> <keycode>` - configure the trigger combo
- `ADD_REMAP <from_mod> <from_key> <to_mod> <to_key>` - add a remap entry
- `CLEAR_REMAPS` - remove all remap entries
- `EMIT <keycode> <value>` - emit a key event to the virtual keyboard
- `SYN` - emit a SYN_REPORT event
- `PASSTHROUGH` - exit modal mode, return to passthrough

Modifier bitmask: 1=CTRL, 2=SHIFT, 4=ALT, 8=META/SUPER.

### FSM engine

The state machine lives in Babashka (`tecla.fsm`), implemented as pure
functions with no I/O.

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
- `:idle` - bridge is in passthrough mode
- `:modal` - a modal menu is active, keys matched against current keymap level

Transitions:
- `trigger` (e.g., Super+Space) - `:idle` -> `:modal` (root menu)
- `key with :keys` (submenu) - `:modal` -> `:modal` (push child onto stack)
- `key with :action` (leaf) - `:modal` -> `:idle` (execute action, exit)
- `key with :action` in `:transient` submenu - `:modal` -> `:modal` (execute,
  stay)
- `escape` - `:modal` -> `:modal` (pop to parent) or -> `:idle` (if at root)
- `unbound key` - swallowed (no effect while modal is active)

FSM state shape:
```clojure
{:mode       :idle | :modal
 :menu-stack [keymap ...]   ; stack of keymap levels for parent navigation
 :config     {...}          ; full tecla config map
 :effect     nil | effect}  ; side effect to execute (consumed by core)
```

Effects (returned by FSM, executed by `tecla.core`):
- `{:type :enter-modal}` - bridge enters modal (handled automatically)
- `{:type :exit-modal}` - send PASSTHROUGH to bridge
- `{:type :action :action ...}` - execute action, stay in modal (transient)
- `{:type :action-and-exit :action ...}` - execute action, then exit modal

### Compositor adapter (focused window detection) - DEFERRED

The only compositor-specific concern is knowing which application is focused.
This is needed exclusively for per-app keymaps (`:apps` config section). All
other features (modal menus, global remaps, nested navigation) work purely at
the evdev level with zero compositor awareness.

When implemented, this will be a pluggable adapter - a multimethod dispatching
on a `:compositor` config key. Each adapter runs in a separate thread, watches
for focus changes, and updates an atom in the FSM context.

Planned adapters:
- `:hyprland` - `hyprctl activewindow` or socket2 event stream
- `:sway` - `swaymsg -t subscribe '["window"]'`
- `:gnome` - DBus `org.gnome.Shell` interface
- `:kde` - DBus KWin scripting
- `:niri` - `niri msg -j focused-window` or event stream
- `:generic` - poll-based fallback using `/proc` + window hints

### Action system

Actions are what happen when a leaf key is pressed. Dispatched by
`tecla.core/execute-action` multimethod. An action can be:

- A string - executed as a shell command via `sh -c`
- A function - called directly
- A keyword - looked up in an action registry (NOT YET IMPLEMENTED)
- A vector `[:key mods key]` - synthesize a key combo via bridge EMIT commands

Examples:
```clojure
:action "rofi -show drun"          ; shell command
:action (fn [] (shell "emacs"))    ; function (only in code, not EDN config)
:action :wm/focus-left             ; keyword (registry - planned)
:action [:key [:ctrl] :c]          ; synthesize Ctrl+C
```

## Configuration format

Configuration is a plain EDN file. Default path:
`~/.config/tecla/config.edn`.

Top-level structure:

```clojure
{:device "Adv360"                           ; device name substring or path
 ;; :compositor :hyprland                    ; adapter selection (deferred)

 ;; the key combo that opens the root modal
 :trigger {:mods [:super] :key :space}

 ;; always-active key remaps (applied in passthrough mode by the C bridge)
 :remaps
 {[:super :c] [:ctrl :c]
  [:super :v] [:ctrl :v]
  [:super :a] [:ctrl :a]}

 ;; root modal keymap
 :root
 {:a {:desc "Apps"
      :keys {:e {:desc "Emacs"    :action "emacsclient -c"}
             :b {:desc "Browser"  :action "brave"}
             :t {:desc "Terminal" :action "kitty"}}}

  :w {:desc "Windows"
      :keys {:h {:desc "Left"  :action "hyprctl dispatch movefocus l"}
             :l {:desc "Right" :action "hyprctl dispatch movefocus r"}
             :comma {:desc "Resize"
                     :transient true
                     :keys {:h {:desc "shrink" :action "resize -20 0"}
                            :l {:desc "grow"   :action "resize 20 0"}}}}}

  :b {:desc "Buffers/Tabs"
      :keys {:n {:desc "Next" :action [:key [:ctrl] :tab]}
             :p {:desc "Prev" :action [:key [:ctrl :shift] :tab]}}}}

 ;; per-application keymaps (requires compositor adapter - DEFERRED)
 ;; :apps
 ;; {"brave-browser"
 ;;  {:trigger {:mods [:super] :key :comma}
 ;;   :keys {:o {:desc "Open in..." :keys {:e {:desc "Editor" :action ...}}}}}}
 }
```

### Keymap entry shapes

A key entry is a map. It is either a leaf (has `:action`) or a submenu (has
`:keys`):

```clojure
;; Leaf - executes action, exits modal (unless inside a :transient submenu)
{:desc "Description"        ; shown in menu display
 :action <action>}          ; see Action system

;; Submenu - opens a nested modal level
{:desc "Description"
 :transient true            ; optional - actions within stay in modal
 :keys {<key> <entry> ...}} ; nested keymap
```

Keys in a keymap are keywords matching `tecla.keys/key->code`:
`:a`, `:b`, `:space`, `:tab`, `:return`, `:comma`, `:dot`, `:f1`, etc.

## Module structure

```
tecla/
├── bridge/
│   ├── tecla-bridge.c         ; C bridge: evdev/uinput + socket server
│   ├── tecla-test-kbd.c       ; virtual keyboard for integration testing
│   ├── Makefile
│   └── .clangd                ; LSP config for editor
├── src/
│   └── tecla/
│       ├── core.clj           ; entry point, config, nREPL, effect dispatch
│       ├── fsm.clj            ; state machine engine (pure functions)
│       ├── bridge.clj         ; socket client, bridge process management
│       └── keys.clj           ; keycode <-> keyword mapping tables
├── test/
│   ├── fsm_test.clj           ; FSM unit tests (pure, no I/O)
│   └── bridge_test.clj        ; bridge integration tests (virtual keyboard)
├── .github/
│   └── workflows/
│       └── test.yml           ; CI: FSM tests + bridge integration tests
├── config.example.edn         ; example user configuration
├── bb.edn                     ; Babashka project config
└── SPEC.md
```

## Runtime lifecycle

1. Start tecla-bridge (C):
   - Find keyboard device by name or path
   - Grab exclusive access via libevdev
   - Create virtual keyboard via uinput
   - Release stale modifier state on virtual device
   - Listen on Unix domain socket

2. Start tecla (Babashka):
   - Load config from `~/.config/tecla/config.edn`
   - Start bridge process (if not already running)
   - Connect to bridge socket, start reader thread
   - Send `SET_TRIGGER` and `ADD_REMAP` commands from config
   - Start nREPL server (default port 7888)
   - Block on event loop

3. Runtime:
   - Bridge passthrough mode: all keys forwarded with remap table applied
   - When trigger combo detected: bridge enters modal, sends `TRIGGER` to BB
   - BB FSM processes key events, sends `EMIT`/`PASSTHROUGH` back
   - Actions executed as side effects (shell commands, key synthesis)

4. Shutdown:
   - Bridge: SIGTERM/SIGINT -> release evdev grab, destroy uinput, close socket
   - BB: disconnect from socket, stop nREPL

## Key handling details

### Modifier passthrough in modal state

When a modal is active, modifier keys (Shift, Ctrl, Alt, Super) are still
forwarded to the virtual device by the C bridge. Non-modifier keys are sent to
Babashka as KEY messages. This prevents stuck modifiers on modal exit.

### Key repeat

Key repeat events (`value == 2` in `input_event`) are dropped unconditionally
by the C bridge. The virtual keyboard generates its own repeats from
press/release events, matching the system's configured repeat rate.

### Remap mechanics

Remaps are a lookup table in the C bridge, configured via `ADD_REMAP` commands.
When a matching key+modifier combo is detected:

1. Release the source modifier(s) on uinput
2. Press the target modifier(s)
3. Press the target key
4. On key release: reverse the modifier swap, restore physical state

### Device hot-plug - NOT YET IMPLEMENTED

When implemented, `:device :auto` will monitor `/dev/input/` for new devices
matching keyboard capabilities and automatically grab them.

## Development workflow

1. Build: `make -C bridge`
2. Start: `bb -m tecla.core/start!` (or via a wrapper script)
3. Connect nREPL from Emacs: `M-x cider-connect` -> localhost:7888
4. Modify keymaps live:
   ```clojure
   (tecla.core/update-config!
     assoc-in [:root :x] {:desc "Test" :action "notify-send 'hello'"})
   ```
5. Changes take effect immediately - no restart needed

## Testing

- `bb test/fsm_test.clj` - FSM unit tests (pure functions, instant, no
  hardware)
- `bb test/bridge_test.clj` - integration tests (starts a virtual keyboard via
  `tecla-test-kbd`, starts the bridge, exercises socket protocol)
- CI: GitHub Actions runs both test suites. Bridge tests need `sudo` for
  uinput access.

## Security considerations

- The bridge process requires elevated access to input devices - inherent to
  evdev-level interception
- Run the bridge as a dedicated user in `input` + `uinput` groups when possible
- nREPL binds to localhost only (127.0.0.1)
- Config file should be owned by the user, not world-writable

## Not yet implemented

- Compositor adapters for per-app keymaps
- Action keyword registry
- Device hot-plug
- Ctrl+g as modal escape (currently only Escape key)
- Graphical which-key overlay
- systemd unit file
- Wrapper script / CLI entry point
