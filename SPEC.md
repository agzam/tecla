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
- Be configurable with plain Clojure data (maps, keywords, vectors)
- Support live development via nREPL (connect from Emacs/CIDER, modify keymaps
  and behavior at runtime)

## Non-goals

- Graphical overlay/menu display (may be added later via external process, e.g.,
  eww, rofi)
- Mouse remapping
- Hardware-level key remapping (firmware, QMK, etc.)

## Architecture

### Input interception layer

Tecla operates below the compositor by intercepting at the Linux evdev/uinput
level:

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

Two processes:

1. **tecla-bridge** (C) - grabs the physical keyboard via libevdev, creates a
   virtual keyboard via uinput. In passthrough mode, forwards events directly
   (zero Babashka involvement, sub-microsecond). In modal mode, sends key events
   to Babashka over a Unix socket and emits whatever Babashka sends back.

2. **tecla** (Babashka) - connects to the bridge, configures remap tables and
   the modal trigger key, runs the FSM when modal is active, provides nREPL for
   live development.

The bridge requires access to `/dev/input/` and `/dev/uinput` - either as root
or via `input` + `uinput` group membership. The Babashka process runs
unprivileged.

### FSM engine

The core state machine manages modal navigation. States and transitions:

```
                ┌──────────────────────────┐
                │                          │
                v                          │
  ┌───────┐  trigger  ┌────────┐  escape  │
  │ :idle │ --------> │ :modal │ ---------─┘
  └───────┘           └────────┘
                        │    ^
                  key   │    │ :parent
                  with  │    │
                  :keys │    │
                        v    │
                      ┌────────┐
                      │ :modal │  (child - recursive)
                      └────────┘
```

States:
- `:idle` - passthrough mode: remaps are applied, all other keys forwarded as-is
- `:modal` - a modal menu is active: keys are consumed and matched against the
  current keymap level

Transitions:
- `trigger` (e.g., Super+Space) - `:idle` -> `:modal` (root menu)
- `key with :keys` (submenu) - `:modal` -> `:modal` (push child menu onto stack)
- `key with :action` (leaf) - `:modal` -> `:idle` (execute action, exit)
- `key with :action + :transient`- `:modal` -> `:modal` (execute, stay in menu)
- `escape / Ctrl+g` - `:modal` -> `:modal` (pop to parent) or -> `:idle` (if root)
- `unbound key` - swallowed (no effect while modal is active)

The FSM context holds:
- `:menu-stack` - vector of keymap levels (for :parent navigation)
- `:pressed-keys` - currently held physical keys (for proper release handling)
- `:timestamp` - for timeout-based transitions (future: tap vs hold)

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

Actions are what happen when a leaf key is pressed. An action can be:

- A function (called directly)
- A keyword (looked up in an action registry)
- A string (executed as a shell command)
- A vector `[:key mods key]` (synthesize a key event via uinput)

Examples:
```clojure
;; function
:action (fn [] (shell "emacs"))

;; keyword (registered action)
:action :wm/focus-left

;; shell command
:action "rofi -show drun"

;; synthesize key
:action [:key [:ctrl] :c]
```

## Configuration format

Configuration is a plain Clojure map. The top-level structure:

```clojure
{:device "AT Translated Set 2 keyboard"  ; or :auto to detect
 ;; :compositor :hyprland                  ; adapter selection (deferred)

 ;; the key combo that opens the root modal
 :trigger {:mods [:super] :key :space}

 ;; always-active key remaps (applied in :idle state)
 :remaps
 {[:super :c] [:ctrl :c]
  [:super :v] [:ctrl :v]
  [:super :a] [:ctrl :a]
  [:super :z] [:ctrl :z]
  [:super :x] [:ctrl :x]
  [:super :s] [:ctrl :s]
  [:super :w] [:ctrl :w]
  [:super :t] [:ctrl :t]
  [:super :f] [:ctrl :f]}

 ;; root modal keymap
 :root
 {:a {:desc "Apps"
      :keys {:e {:desc "Emacs"    :action "emacsclient -c"}
             :b {:desc "Browser"  :action "brave"}
             :t {:desc "Terminal" :action "kitty"}}}

  :w {:desc "Windows"
      :keys {:h {:desc "←" :action :wm/focus-left}
             :l {:desc "→" :action :wm/focus-right}
             :j {:desc "↓" :action :wm/focus-down}
             :k {:desc "↑" :action :wm/focus-up}
             :, {:desc "Resize"
                 :transient true
                 :keys {:h {:desc "shrink-x" :action :wm/shrink-x}
                        :l {:desc "grow-x"   :action :wm/grow-x}}}}}

  :b {:desc "Buffers/Tabs"
      :keys {:n {:desc "Next" :action [:key [:ctrl] :tab]}
             :p {:desc "Prev" :action [:key [:ctrl :shift] :tab]}}}}

 ;; per-application keymaps
 ;; trigger: Super+, (comma) by default, configurable per app
 :apps
 {"brave-browser"
  {:trigger {:mods [:super] :key :comma}
   :keys {:o {:desc "Open in..."
              :keys {:e {:desc "Editor" :action :browser/open-in-editor}}}
          :t {:desc "Translate page" :action :browser/translate}}}

  "emacs"
  {:trigger {:mods [:super] :key :comma}
   :keys {:r {:desc "Reload config" :action "emacsclient -e '(reload-init)'"}}}}}
```

### Keymap entry shapes

A key entry is a map. It is either a leaf (has `:action`) or a submenu (has
`:keys`):

```clojure
;; Leaf - executes action, exits modal (unless :transient)
{:desc "Description"        ; required - shown in menu display
 :action <action>           ; required - see Action system
 :transient true}           ; optional - stay in current modal after action

;; Submenu - opens a nested modal level
{:desc "Description"        ; required
 :keys {<key> <entry> ...}} ; required - nested keymap
```

The key in a keymap is a keyword corresponding to the key name: `:a`, `:b`,
`:space`, `:tab`, `:return`, `:1`, `:f1`, etc.

For key chords (key + modifier) as map keys, use a vector: `[:ctrl :a]`,
`[:shift :tab]`.

## Module structure

```
tecla/
├── bridge/
│   ├── tecla-bridge.c         ; C bridge: evdev/uinput + socket
│   └── Makefile
├── src/
│   └── tecla/
│       ├── core.clj           ; entry point, config loading, nREPL start
│       ├── fsm.clj            ; state machine engine (pure functions)
│       ├── keymap.clj         ; keymap parsing, lookup, stack operations
│       ├── bridge.clj         ; Unix socket communication with C bridge
│       ├── remap.clj          ; always-on remap logic (idle state)
│       ├── action.clj         ; action dispatch (shell, key synth, fn call)
│       ├── keys.clj           ; keycode <-> keyword mapping tables
│       └── adapters/          ; DEFERRED - for per-app keymaps
│           └── ...
├── config.example.clj         ; example user configuration
├── bb.edn                     ; Babashka project config
├── SPEC.md
├── README.md
└── LICENSE
```

## Runtime lifecycle

1. Load config from `~/.config/tecla/config.jank` (or specified path)
2. Start nREPL server on a configured port
3. Open evdev device, grab exclusive access
4. Create uinput virtual device
5. Enter main loop:
   - Read `input_event` from evdev
   - If key event: pass to FSM
   - FSM returns: `{:emit [events...] :state new-state :effect side-effect}`
   - Write emitted events to uinput
   - Execute any side effects (run action, update menu display)
6. On SIGTERM/SIGINT: release evdev grab, destroy uinput device, exit

## Key handling details

### Modifier passthrough in modal state

When a modal is active, modifier keys (Shift, Ctrl, Alt, Super) are still
forwarded to the virtual device. This allows modal keys to be combined with
modifiers if the keymap supports it, and prevents stuck modifiers on exit.

### Key repeat

Key repeat events (`value == 2` in `input_event`) are forwarded in `:idle`
state and swallowed in `:modal` state (unless the current action is
`:transient` and `:repeatable`).

### Device hot-plug

When configured with `:device :auto`, tecla monitors `/dev/input/` for new
devices matching keyboard capabilities and automatically grabs them. This
handles USB keyboard connect/disconnect.

## Development workflow

1. Start tecla: `sudo tecla` (or via systemd service with appropriate
   permissions)
2. Connect to nREPL from Emacs: `M-x cider-connect` -> localhost:7888
3. Modify keymaps live:
   ```clojure
   ;; in REPL
   (tecla.core/update-config!
     assoc-in [:root :x] {:desc "Test" :action "notify-send 'hello'"})
   ```
4. Changes take effect immediately - no restart needed
5. Iterate on FSM logic, add new actions, test compositor adapters - all from
   the REPL

## Security considerations

- Tecla requires elevated access to input devices - this is inherent to
  evdev-level interception
- The process should run as a dedicated user in `input` + `uinput` groups,
  not as root when possible
- `PrivateNetwork=true` in the systemd unit to prevent network access
- Config file should be owned by the user, not world-writable
- nREPL should bind to localhost only
