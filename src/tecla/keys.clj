(ns tecla.keys
  "Bidirectional mapping between Linux evdev keycodes and keywords.")

;; From linux/input-event-codes.h - standard keys only.
;; Extend as needed.

(def code->key
  {1   :esc
   2   :1,   3  :2,   4  :3,   5  :4,   6  :5
   7   :6,   8  :7,   9  :8,  10  :9,  11  :0
   12  :minus,      13 :equal
   14  :backspace,  15 :tab
   16  :q,  17 :w,  18 :e,  19 :r,  20 :t
   21  :y,  22 :u,  23 :i,  24 :o,  25 :p
   26  :left-bracket, 27 :right-bracket
   28  :return
   29  :left-ctrl
   30  :a,  31 :s,  32 :d,  33 :f,  34 :g
   35  :h,  36 :j,  37 :k,  38 :l
   39  :semicolon,  40 :apostrophe, 41 :grave
   42  :left-shift
   43  :backslash
   44  :z,  45 :x,  46 :c,  47 :v,  48 :b
   49  :n,  50 :m
   51  :comma,  52 :dot,  53 :slash
   54  :right-shift
   55  :kp-asterisk
   56  :left-alt
   57  :space
   58  :capslock
   59  :f1,  60 :f2,  61 :f3,  62 :f4,  63 :f5
   64  :f6,  65 :f7,  66 :f8,  67 :f9,  68 :f10
   87  :f11, 88 :f12
   96  :kp-enter
   97  :right-ctrl
   100 :right-alt
   102 :home, 103 :up,    104 :pageup
   105 :left, 106 :right
   107 :end,  108 :down,  109 :pagedown
   110 :insert, 111 :delete
   113 :mute, 114 :volume-down, 115 :volume-up
   125 :left-meta
   126 :right-meta
   127 :compose
   ;; Mouse buttons (from linux/input-event-codes.h BTN_* defines)
   272 :mouse-left
   273 :mouse-right
   274 :mouse-middle})

(def key->code
  (into {} (map (fn [[c k]] [k c])) code->key))

;; Modifier keywords -> bitmask (bridge protocol)
(def mod-bits
  {:ctrl  1
   :shift 2
   :alt   4
   :meta  8
   :super 8})

(def modifier-keys
  #{:left-ctrl :right-ctrl
    :left-shift :right-shift
    :left-alt :right-alt
    :left-meta :right-meta})

(defn modifier?
  "Is this keyword a modifier key?"
  [k]
  (contains? modifier-keys k))

(defn key->mod-bit
  "Return the modifier bitmask for a modifier keyword, or 0."
  [k]
  (case k
    (:left-ctrl :right-ctrl)   (:ctrl mod-bits)
    (:left-shift :right-shift) (:shift mod-bits)
    (:left-alt :right-alt)     (:alt mod-bits)
    (:left-meta :right-meta)   (:meta mod-bits)
    0))

(defn mods->bits
  "Convert a collection of modifier keywords like [:super :ctrl] to bitmask."
  [mods]
  (reduce (fn [acc m] (bit-or acc (get mod-bits m 0))) 0 mods))

(def ^:private bit->mod-sorted
  "Ordered pairs so bits->mods always produces [:ctrl :shift :alt :meta]."
  [[1 :ctrl] [2 :shift] [4 :alt] [8 :meta]])

(defn bits->mods
  "Convert a modifier bitmask to a seq of modifier keywords in canonical
   order (ctrl, shift, alt, meta). Returns nil when the bitmask is zero."
  [bits]
  (when-not (zero? bits)
    (keep (fn [[b m]] (when-not (zero? (bit-and bits b)) m))
          bit->mod-sorted)))

(def modifier-codes
  "Set of keycodes that are modifiers."
  #{29 97     ;; KEY_LEFTCTRL KEY_RIGHTCTRL
    42 54     ;; KEY_LEFTSHIFT KEY_RIGHTSHIFT
    56 100    ;; KEY_LEFTALT KEY_RIGHTALT
    125 126}) ;; KEY_LEFTMETA KEY_RIGHTMETA

(def mouse-button-codes
  "Set of evdev keycodes that are mouse buttons."
  #{272    ;; BTN_LEFT
    273    ;; BTN_RIGHT
    274})  ;; BTN_MIDDLE

(defn mouse-button?
  "Is this evdev keycode a mouse button?"
  [code]
  (contains? mouse-button-codes code))

(defn modifier-code?
  "Is this evdev keycode a modifier?"
  [code]
  (contains? modifier-codes code))

(defn code->mod-bit
  "Return the modifier bitmask for a keycode, or 0."
  [code]
  (case (int code)
    (29 97)   (:ctrl mod-bits)
    (42 54)   (:shift mod-bits)
    (56 100)  (:alt mod-bits)
    (125 126) (:meta mod-bits)
    0))
