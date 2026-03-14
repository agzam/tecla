#!/usr/bin/env bb

;; Tests for the full key processing pipeline in tecla.core.
;; Mocks bridge/emit! to capture output, tests complete lifecycles.

(require '[tecla.fsm :as fsm]
         '[tecla.keys :as keys]
         '[tecla.core :as core]
         '[tecla.bridge :as bridge])

;; ----------------------------------------------------------------
;; Test infrastructure
;; ----------------------------------------------------------------

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

;; ----------------------------------------------------------------
;; Mock bridge
;; ----------------------------------------------------------------

(def emitted (atom []))

(defn mock-emit! [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when code (swap! emitted conj [code value]))))

(defn emitted? [code value]
  (some (fn [[c v]] (and (= c code) (= v value))) @emitted))

(defn not-emitted? [code value]
  (not (emitted? code value)))

(defn clear-emitted! [] (reset! emitted []))

;; Key codes
(def SUPER 125)
(def CTRL 29)
(def SPACE 57)
(def KEY_A 30)
(def KEY_E 18)
(def KEY_T 20)
(def KEY_W 17)
(def KEY_H 35)
(def KEY_ESC 1)

;; ----------------------------------------------------------------
;; Setup
;; ----------------------------------------------------------------

(def config
  {:trigger {:mods [:super] :key :space}
   :remaps {[:super :t] [:ctrl :t]
            [:super :f] [:ctrl :f]}
   :root {:a {:desc "Apps"
              :keys {:e {:desc "Emacs" :action "emacs"}
                     :u {:desc "Utils"
                         :keys {:d {:desc "ncdu" :action "ncdu"}
                                :n {:desc "Network"
                                    :keys {:w {:desc "Wireshark" :action "wireshark"}}}}}}}
          :w {:desc "Windows"
              :keys {:h {:desc "Left" :action "wm-left"}}}
          :x {:desc "Execute" :action "rofi"}}})

(defn reset-state! []
  (reset! core/fsm-state (fsm/init config))
  (reset! core/kbd-state
          {:mod-state 0 :buffered-mods 0 :consumed-mods 0
           :active-remaps {} :remap-mods (core/compute-remap-mods config)})
  (clear-emitted!))

;; Patch bridge/emit! to use mock
(alter-var-root #'bridge/emit! (constantly mock-emit!))

(defn press [code] (core/on-key-event code 1))
(defn release [code] (core/on-key-event code 0))
(defn tap [code] (press code) (release code))
(defn combo [mod-code key-code]
  (press mod-code) (press key-code) (release key-code) (release mod-code))

;; ================================================================
(println "\n=== tecla.core key processing tests ===\n")
;; ================================================================

;; ----------------------------------------------------------------
;; Basic passthrough
;; ----------------------------------------------------------------

(deftest "plain key passes through"
  (reset-state!)
  (tap KEY_A)
  (assert= true (emitted? KEY_A 1) "A press emitted")
  (assert= true (emitted? KEY_A 0) "A release emitted"))

(deftest "non-remap modifier passes through"
  (reset-state!)
  (combo CTRL KEY_A)
  (assert= true (emitted? CTRL 1) "Ctrl press")
  (assert= true (emitted? KEY_A 1) "A press")
  (assert= true (emitted? KEY_A 0) "A release")
  (assert= true (emitted? CTRL 0) "Ctrl release"))

;; ----------------------------------------------------------------
;; Remaps
;; ----------------------------------------------------------------

(deftest "Super+T remaps to Ctrl+T"
  (reset-state!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl press")
  (assert= true (emitted? KEY_T 1) "T press")
  (assert= true (emitted? KEY_T 0) "T release")
  (assert= true (emitted? CTRL 0) "Ctrl release")
  (assert= true (not-emitted? SUPER 1) "Super NOT emitted"))

(deftest "Super+unremapped key flushes Super"
  (reset-state!)
  (combo SUPER KEY_W)
  (assert= true (emitted? SUPER 1) "Super flushed")
  (assert= true (emitted? KEY_W 1) "W press"))

(deftest "plain T still types normally"
  (reset-state!)
  (tap KEY_T)
  (assert= true (emitted? KEY_T 1) "T press")
  (assert= true (not-emitted? CTRL 1) "no Ctrl"))

;; ----------------------------------------------------------------
;; Trigger + modal
;; ----------------------------------------------------------------

(deftest "Super+Space enters modal"
  (reset-state!)
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "in modal")
  (assert= true (not-emitted? SUPER 1) "Super not emitted")
  (assert= true (not-emitted? SPACE 1) "Space not emitted"))

(deftest "keys in modal are swallowed (not emitted)"
  (reset-state!)
  (combo SUPER SPACE)
  (clear-emitted!)
  (tap KEY_A)
  (assert= true (not-emitted? KEY_A 1) "A not emitted in modal"))

(deftest "escape exits modal"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_ESC)
  (assert= :idle (:mode @core/fsm-state) "back to idle"))

;; ----------------------------------------------------------------
;; Full lifecycle: trigger -> navigate -> action -> re-trigger
;; ----------------------------------------------------------------

(deftest "trigger -> a -> e executes action and exits"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (assert= :modal (:mode @core/fsm-state) "in Apps submenu")
  (tap KEY_E)
  (assert= :idle (:mode @core/fsm-state) "back to idle after action"))

(deftest "re-trigger works after action exit"
  (reset-state!)
  ;; First cycle
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_E)
  (assert= :idle (:mode @core/fsm-state) "idle after first cycle")
  ;; Second cycle
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal again"))

(deftest "re-trigger works after escape"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_ESC)
  (assert= :idle (:mode @core/fsm-state) "idle after escape")
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal again"))

(deftest "rapid: trigger -> a -> esc -> trigger -> a -> esc (3 times)"
  (reset-state!)
  (dotimes [_ 3]
    (combo SUPER SPACE)
    (assert= :modal (:mode @core/fsm-state) "modal")
    (tap KEY_A)
    (assert= :modal (:mode @core/fsm-state) "in submenu")
    (tap KEY_ESC)
    (assert= :idle (:mode @core/fsm-state) "idle")))

;; ----------------------------------------------------------------
;; Deep nesting
;; ----------------------------------------------------------------

(deftest "trigger -> a -> u -> d executes deep action"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)          ;; Apps
  (tap (keys/key->code :u))  ;; Utils
  (tap (keys/key->code :d))  ;; ncdu
  (assert= :idle (:mode @core/fsm-state) "idle"))

(deftest "trigger -> a -> u -> n -> w executes 4-level deep action"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap (keys/key->code :u))
  (tap (keys/key->code :n))
  (tap KEY_W)          ;; Wireshark (KEY_W = 17)
  (assert= :idle (:mode @core/fsm-state) "idle"))

(deftest "escape from deep nesting exits all"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap (keys/key->code :u))
  (tap (keys/key->code :n))
  (assert= :modal (:mode @core/fsm-state) "4 levels deep")
  (tap KEY_ESC)
  (assert= :idle (:mode @core/fsm-state) "all exited"))

(deftest "re-trigger after deep escape"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap (keys/key->code :u))
  (tap (keys/key->code :n))
  (tap KEY_ESC)
  (combo SUPER SPACE)
  (assert= :modal (:mode @core/fsm-state) "modal again")
  (assert= 1 (count (:menu-stack @core/fsm-state)) "at root"))

;; ----------------------------------------------------------------
;; Remaps work after modal exit
;; ----------------------------------------------------------------

(deftest "remap works after modal action exit"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_E)
  (assert= :idle (:mode @core/fsm-state) "idle")
  (clear-emitted!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl emitted")
  (assert= true (emitted? KEY_T 1) "T emitted")
  (assert= true (not-emitted? SUPER 1) "Super not emitted"))

(deftest "remap works after escape"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_ESC)
  (clear-emitted!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl emitted")
  (assert= true (not-emitted? SUPER 1) "Super not emitted"))

;; ----------------------------------------------------------------
;; kbd-state cleanliness
;; ----------------------------------------------------------------

(deftest "kbd-state clean after action exit"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_E)
  (let [s @core/kbd-state]
    (assert= 0 (:mod-state s) "mod-state 0")
    (assert= 0 (:buffered-mods s) "buffered 0")
    (assert= 0 (:consumed-mods s) "consumed 0")
    (assert= {} (:active-remaps s) "no active remaps")))

(deftest "kbd-state clean after escape"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_ESC)
  (let [s @core/kbd-state]
    (assert= 0 (:mod-state s) "mod-state 0")
    (assert= 0 (:buffered-mods s) "buffered 0")
    (assert= 0 (:consumed-mods s) "consumed 0")))

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors]
      (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
