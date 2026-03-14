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
   127 :compose})

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
