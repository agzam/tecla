#!/usr/bin/env bb

(require '[tecla.fsm :as fsm]
         '[tecla.keys :as keys]
         '[tecla.core :as core]
         '[tecla.bridge :as bridge])

(def test-results (atom {:passed 0 :failed 0 :errors []}))
(defn pass! [n] (swap! test-results update :passed inc) (println (str "  \u2713 " n)))
(defn fail! [n msg]
  (swap! test-results #(-> % (update :failed inc) (update :errors conj {:test n :msg msg})))
  (println (str "  \u2717 " n ": " msg)))
(defmacro deftest [name & body]
  `(try ~@body (pass! ~(str name))
        (catch Exception e# (fail! ~(str name) (.getMessage e#)))))
(defn assert= [expected actual msg]
  (when (not= expected actual)
    (throw (ex-info (str msg " - expected: " (pr-str expected)
                         ", got: " (pr-str actual)) {}))))

;; ---- Mock bridge ----

(def emitted (atom []))
(defn mock-emit! [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when code (swap! emitted conj [code value]))))
(defn emitted? [c v] (some (fn [[a b]] (and (= a c) (= b v))) @emitted))
(defn not-emitted? [c v] (not (emitted? c v)))
(defn clear! [] (reset! emitted []))
(alter-var-root #'bridge/emit! (constantly mock-emit!))

(def SUPER 125) (def CTRL 29) (def SHIFT 42) (def ALT 56) (def SPACE 57)
(def KEY_A 30) (def KEY_B 48) (def KEY_E 18) (def KEY_T 20)
(def KEY_W 17) (def KEY_H 35) (def KEY_X 45) (def KEY_ESC 1)

(defn press [c] (core/on-key-event c 1))
(defn release [c] (core/on-key-event c 0))
(defn tap [c] (press c) (release c))
(defn combo [m k] (press m) (press k) (release k) (release m))

;; ---- Config (unified keymap) ----

(def config
  {:global-keymap
   {[:super :c] [:ctrl :c]
    [:super :t] [:ctrl :t]
    [:super :return] "kitty"
    [:super :space]
    {:desc "Main"
     :keys {:a {:desc "Apps"
                :keys {:e {:desc "Emacs" :action "echo emacs"}
                       :b {:desc "Brave" :action "echo brave"}
                       [:shift :b] {:desc "Brave Private" :action "echo brave-private"}
                       [:ctrl :shift :x] {:desc "Special" :action "echo special"}
                       :u {:desc "Utils"
                           :keys {:d {:desc "ncdu" :action "echo ncdu"}
                                  :n {:desc "Network"
                                      :keys {:w {:desc "Wireshark" :action "echo wireshark"}}}}}}}
            :w {:desc "Windows"
                :keys {:h {:desc "Left" :action "echo left"}}}
            :x {:desc "Execute" :action "echo exec"}}}}

   :app-local-keymap
   {:brave-browser
    {[:ctrl :n] [:down]
     [:ctrl :p] [:up]
     [:super :l] [:ctrl :l]}}})

(defn reset-state! []
  (reset! core/fsm-state (fsm/init config))
  (reset! core/focused-app nil)
  (reset! core/kbd-state
          {:mod-state 0 :buffered-mods 0 :consumed-mods 0
           :active-sims {} :buffer-mods (core/compute-buffer-mods (:global-keymap config))})
  (clear!))

(defn reset-state-with-app!
  "Reset state and simulate being focused on a specific app."
  [app-class]
  (reset-state!)
  (reset! core/focused-app app-class)
  (let [keymap (#'core/effective-keymap config)]
    (swap! core/kbd-state assoc :buffer-mods (core/compute-buffer-mods keymap)))
  (clear!))

;; ================================================================
(println "\n=== tecla.core unified keymap tests ===\n")
;; ================================================================

;; Passthrough

(deftest "plain key passes through"
  (reset-state!)
  (tap KEY_A)
  (assert= true (emitted? KEY_A 1) "press")
  (assert= true (emitted? KEY_A 0) "release"))

(deftest "non-keymap modifier passes through"
  (reset-state!)
  (combo CTRL KEY_A)
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (emitted? KEY_A 1) "A"))

;; Remap (vector value)

(deftest "Super+T -> Ctrl+T"
  (reset-state!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl press")
  (assert= true (emitted? KEY_T 1) "T press")
  (assert= true (emitted? KEY_T 0) "T release")
  (assert= true (emitted? CTRL 0) "Ctrl release")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "Super+C -> Ctrl+C"
  (reset-state!)
  (combo SUPER (keys/key->code :c))
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "plain T still types normally"
  (reset-state!)
  (tap KEY_T)
  (assert= true (emitted? KEY_T 1) "T")
  (assert= true (not-emitted? CTRL 1) "no Ctrl"))

(deftest "Super+unbound key flushes Super"
  (reset-state!)
  (combo SUPER KEY_H)
  (assert= true (emitted? SUPER 1) "Super flushed")
  (assert= true (emitted? KEY_H 1) "H"))

;; Shell command (string value)

(deftest "Super+Return dispatches action (string)"
  (reset-state!)
  (combo SUPER (keys/key->code :return))
  ;; Can't easily verify shell execution, but Super should be consumed
  (assert= true (not-emitted? SUPER 1) "Super consumed"))

;; Modal (map with :keys)

(deftest "Super+Space enters modal"
  (reset-state!)
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal")
  (assert= true (not-emitted? SUPER 1) "no Super")
  (assert= true (not-emitted? SPACE 1) "no Space"))

(deftest "keys in modal swallowed"
  (reset-state!)
  (combo SUPER SPACE)
  (clear!)
  (tap KEY_A)
  (assert= true (not-emitted? KEY_A 1) "swallowed"))

(deftest "escape exits modal"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_ESC)
  (assert= :idle (:mode @core/fsm-state) "idle"))

;; Full lifecycle

(deftest "trigger -> a -> e -> idle"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_E)
  (assert= :idle (:mode @core/fsm-state) "idle"))

(deftest "re-trigger after action"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A) (tap KEY_E)
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal again"))

(deftest "re-trigger after escape"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_ESC)
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal again"))

(deftest "rapid cycling 3x"
  (reset-state!)
  (dotimes [_ 3]
    (combo SUPER SPACE)
    (assert= :modal (:mode @core/fsm-state) "modal")
    (tap KEY_X)
    (assert= :idle (:mode @core/fsm-state) "idle")))

;; Deep nesting

(deftest "4-level deep action"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap (keys/key->code :u))
  (tap (keys/key->code :n))
  (tap KEY_W)
  (assert= :idle (:mode @core/fsm-state) "idle"))

(deftest "escape from deep"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A) (tap (keys/key->code :u)) (tap (keys/key->code :n))
  (tap KEY_ESC)
  (assert= :idle (:mode @core/fsm-state) "idle"))

;; Remap after modal

(deftest "remap works after modal action"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A) (tap KEY_E)
  (clear!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "remap works after escape"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_ESC)
  (clear!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl"))

;; Remap hold behavior (press-only on key-down, release on key-up)

(deftest "remap holds: no release until source key released"
  (reset-state!)
  (press SUPER)
  (press KEY_T)
  ;; At this point: Ctrl and T should be pressed, but NOT released
  (assert= true (emitted? CTRL 1) "Ctrl pressed")
  (assert= true (emitted? KEY_T 1) "T pressed")
  (assert= true (not-emitted? KEY_T 0) "T NOT released yet")
  (assert= true (not-emitted? CTRL 0) "Ctrl NOT released yet")
  ;; Now release source key
  (release KEY_T)
  (assert= true (emitted? KEY_T 0) "T released")
  (assert= true (emitted? CTRL 0) "Ctrl released"))

;; Symbol expansion in dispatch-form!

(deftest "expand-sym resolves wm/ alias"
  (reset-state!)
  (let [expanded (#'core/expand-sym 'wm/focus-left)]
    (assert= 'tecla.wm/focus-left expanded "wm -> tecla.wm"))
  (let [unchanged (#'core/expand-sym 'some.other.ns/foo)]
    (assert= 'some.other.ns/foo unchanged "fully-qualified unchanged")))

;; State cleanliness

(deftest "kbd-state clean after action"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A) (tap KEY_E)
  (let [s @core/kbd-state]
    (assert= 0 (:mod-state s) "mods")
    (assert= 0 (:buffered-mods s) "buffered")
    (assert= 0 (:consumed-mods s) "consumed")
    (assert= {} (:active-sims s) "sims")))

(deftest "kbd-state clean after escape"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_ESC)
  (let [s @core/kbd-state]
    (assert= 0 (:mod-state s) "mods")
    (assert= 0 (:buffered-mods s) "buffered")
    (assert= 0 (:consumed-mods s) "consumed")))

;; ---- Modal modifier+key tests ----

(deftest "modal: plain :b triggers Brave"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A) ;; enter Apps submenu
  (clear!)
  (tap KEY_B)                     ;; plain b -> "echo brave"
  (assert= :idle (:mode @core/fsm-state) "back to idle"))

(deftest "modal: Shift+B triggers Brave Private"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A) ;; enter Apps submenu
  (clear!)
  (press SHIFT) (tap KEY_B) (release SHIFT)
  (assert= :idle (:mode @core/fsm-state) "back to idle"))

(deftest "modal: Ctrl+Shift+X triggers Special"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A) ;; enter Apps submenu
  (clear!)
  (press CTRL) (press SHIFT) (tap KEY_X) (release SHIFT) (release CTRL)
  (assert= :idle (:mode @core/fsm-state) "back to idle"))

(deftest "modal: unmodified key ignores modifier entries"
  (reset-state!)
  (combo SUPER SPACE) (tap KEY_A)
  (clear!)
  (tap KEY_X) ;; plain x, no [:shift :x] or [:ctrl :shift :x] match - swallowed
  (assert= :modal (:mode @core/fsm-state) "still in modal"))

;; ---- App-local keymap tests ----

(def KEY_N 49) (def KEY_P 25) (def KEY_L 38) (def KEY_DOWN 108) (def KEY_UP 103)

(deftest "app-local: effective-keymap merges when focused on brave"
  (reset-state!)
  (reset! core/focused-app "brave-browser")
  (let [keymap (#'core/effective-keymap config)]
    ;; App-local entry exists
    (assert= [:down] (get keymap [:ctrl :n]) "ctrl+n -> down")
    ;; Global entry preserved
    (assert= [:ctrl :c] (get keymap [:super :c]) "super+c -> ctrl+c")))

(deftest "app-local: effective-keymap global-only when no app"
  (reset-state!)
  (reset! core/focused-app nil)
  (let [keymap (#'core/effective-keymap config)]
    (assert= nil (get keymap [:ctrl :n]) "no ctrl+n in global")))

(deftest "app-local: case-insensitive matching"
  (reset-state!)
  (reset! core/focused-app "Brave-browser")
  (let [keymap (#'core/effective-keymap config)]
    (assert= [:down] (get keymap [:ctrl :n]) "case-insensitive match")))

(deftest "app-local: find-app-local works"
  (reset-state!)
  (assert= true (some? (#'core/find-app-local config "brave-browser")) "lowercase")
  (assert= true (some? (#'core/find-app-local config "Brave-browser")) "mixed-case")
  (assert= true (some? (#'core/find-app-local config "BRAVE-BROWSER")) "uppercase")
  (assert= nil (#'core/find-app-local config "firefox") "no match"))

(deftest "app-local: Ctrl+N -> Down in brave"
  (reset-state-with-app! "brave-browser")
  (combo CTRL KEY_N)
  (assert= true (emitted? KEY_DOWN 1) "Down press")
  (assert= true (emitted? KEY_DOWN 0) "Down release")
  (assert= true (not-emitted? KEY_N 1) "N not emitted")
  (assert= true (not-emitted? CTRL 1) "Ctrl not emitted"))

(deftest "app-local: Ctrl+P -> Up in brave"
  (reset-state-with-app! "brave-browser")
  (combo CTRL KEY_P)
  (assert= true (emitted? KEY_UP 1) "Up press")
  (assert= true (emitted? KEY_UP 0) "Up release"))

(deftest "app-local: global remap still works in brave"
  (reset-state-with-app! "brave-browser")
  (combo SUPER (keys/key->code :c))
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "app-local: Ctrl+N passes through when not on brave"
  (reset-state!)
  (reset! core/focused-app "kitty")
  (let [keymap (#'core/effective-keymap config)]
    (swap! core/kbd-state assoc :buffer-mods (core/compute-buffer-mods keymap)))
  (clear!)
  (combo CTRL KEY_N)
  ;; Ctrl and N should pass through (no remap)
  (assert= true (emitted? CTRL 1) "Ctrl passed through")
  (assert= true (emitted? KEY_N 1) "N passed through"))

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
