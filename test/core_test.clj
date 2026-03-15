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

(def SUPER 125) (def CTRL 29) (def SPACE 57)
(def KEY_A 30) (def KEY_E 18) (def KEY_T 20)
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
                       :u {:desc "Utils"
                           :keys {:d {:desc "ncdu" :action "echo ncdu"}
                                  :n {:desc "Network"
                                      :keys {:w {:desc "Wireshark" :action "echo wireshark"}}}}}}}
            :w {:desc "Windows"
                :keys {:h {:desc "Left" :action "echo left"}}}
            :x {:desc "Execute" :action "echo exec"}}}}})

(defn reset-state! []
  (reset! core/fsm-state (fsm/init config))
  (reset! core/kbd-state
          {:mod-state 0 :buffered-mods 0 :consumed-mods 0
           :active-sims {} :buffer-mods (core/compute-buffer-mods (:global-keymap config))})
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

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
