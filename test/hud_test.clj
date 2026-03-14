#!/usr/bin/env bb

;; Tests for HUD (eww widget). Verifies:
;; 1. eww variables contain correct data after state changes
;; 2. widget is open/closed at the right times
;; 3. HUD doesn't interfere with key processing

(require '[tecla.fsm :as fsm]
         '[tecla.keys :as keys]
         '[tecla.core :as core]
         '[tecla.bridge :as bridge]
         '[tecla.hud :as hud]
         '[clojure.java.shell :refer [sh]]
         '[clojure.string :as str])

;; ----------------------------------------------------------------
;; Infrastructure
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
;; Mock bridge + tracking
;; ----------------------------------------------------------------

(def emitted (atom []))
(defn mock-emit! [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when code (swap! emitted conj [code value]))))
(defn emitted? [code value]
  (some (fn [[c v]] (and (= c code) (= v value))) @emitted))
(defn not-emitted? [code value] (not (emitted? code value)))
(defn clear! [] (reset! emitted []))

;; ----------------------------------------------------------------
;; eww query helpers
;; ----------------------------------------------------------------

(def eww-config hud/eww-config-dir)

(defn eww-get [var-name]
  (let [r (sh "eww" "--config" eww-config "get" var-name)]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn eww-windows []
  (let [r (sh "eww" "--config" eww-config "list-windows")]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn eww-active-windows []
  (let [r (sh "eww" "--config" eww-config "active-windows")]
    (when (zero? (:exit r))
      (str/trim (:out r)))))

(defn modal-open? []
  (let [aw (eww-active-windows)]
    (if (nil? aw) false (str/includes? aw "modal"))))

(defn modal-keys []
  (eww-get "current-modal-keys"))

(defn modal-name []
  (eww-get "current-modal-name"))

(alter-var-root #'bridge/emit! (constantly mock-emit!))

(def SUPER 125) (def CTRL 29) (def SPACE 57) (def KEY_A 30)
(def KEY_E 18) (def KEY_T 20) (def KEY_ESC 1) (def KEY_X 45)

(defn press [c] (core/on-key-event c 1))
(defn release [c] (core/on-key-event c 0))
(defn tap [c] (press c) (release c))
(defn combo [m k] (press m) (press k) (release k) (release m))

;; ----------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------

(def config
  {:trigger {:mods [:super] :key :space}
   :remaps {[:super :t] [:ctrl :t]}
   :root {:a {:desc "Apps"
              :keys {:e {:desc "Emacs" :action "echo emacs"}}}
          :x {:desc "Execute" :action "echo exec"}}})

(defn reset-state! []
  (reset! core/fsm-state (fsm/init config))
  (reset! core/kbd-state
          {:mod-state 0 :buffered-mods 0 :consumed-mods 0
           :active-remaps {} :remap-mods (core/compute-remap-mods config)})
  (clear!))

;; ================================================================
(println "\n=== HUD tests ===\n")
;; ================================================================

;; Kill any leftover eww, start fresh daemon, wait until ready
(hud/kill!)
(Thread/sleep 300)
(hud/init!)

;; Start the HUD poller
(reset-state!)
(hud/start! core/fsm-state fsm/current-menu)
(Thread/sleep 200)

;; ----------------------------------------------------------------
;; Widget content tests
;; ----------------------------------------------------------------

(deftest "modal closed initially"
  (assert= false (modal-open?) "modal should not be open"))

(deftest "trigger opens modal widget"
  (reset-state!)
  (combo SUPER SPACE)
  (Thread/sleep 500) ;; let poller + eww react
  (assert= true (modal-open?) "modal should be open"))

(deftest "modal widget shows root menu keys"
  ;; Still in modal from previous test
  (let [keys-str (modal-keys)]
    (assert= true (str/includes? (or keys-str "") "Apps") "should contain Apps")
    (assert= true (str/includes? (or keys-str "") "Execute") "should contain Execute")))

(deftest "submenu updates widget content"
  ;; Still in modal
  (tap KEY_A) ;; enter Apps submenu
  (Thread/sleep 500)
  (let [keys-str (modal-keys)
        name-str (modal-name)]
    (assert= true (str/includes? (or keys-str "") "Emacs") "should contain Emacs")
    (assert= "Apps" name-str "title should be Apps")))

(deftest "escape closes widget"
  (tap KEY_ESC)
  (Thread/sleep 500)
  (assert= false (modal-open?) "modal should be closed")
  (assert= :idle (:mode @core/fsm-state) "should be idle"))

(deftest "re-trigger shows widget with root menu"
  (combo SUPER SPACE)
  (Thread/sleep 500)
  (assert= true (modal-open?) "modal open")
  (let [keys-str (modal-keys)]
    (assert= true (str/includes? (or keys-str "") "Apps") "root menu restored"))
  ;; cleanup
  (tap KEY_ESC)
  (Thread/sleep 500))

(deftest "action closes widget"
  (reset-state!)
  (combo SUPER SPACE)
  (Thread/sleep 500)
  (tap KEY_X) ;; Execute action
  (Thread/sleep 500)
  (assert= false (modal-open?) "modal closed after action")
  (assert= :idle (:mode @core/fsm-state) "idle"))

;; ----------------------------------------------------------------
;; Key processing not affected by HUD
;; ----------------------------------------------------------------

(deftest "passthrough works with HUD active"
  (reset-state!)
  (tap KEY_A)
  (assert= true (emitted? KEY_A 1) "A press"))

(deftest "remap works with HUD active"
  (reset-state!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "re-trigger after action with HUD"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A)
  (tap KEY_E)
  (Thread/sleep 500)
  (assert= :idle (:mode @core/fsm-state) "idle")
  (combo SUPER SPACE)
  (Thread/sleep 500)
  (assert= :modal (:mode @core/fsm-state) "modal again")
  (assert= true (modal-open?) "widget open again")
  (tap KEY_ESC)
  (Thread/sleep 500))

(deftest "rapid cycling 5x: keys + widget"
  (reset-state!)
  (dotimes [_ 5]
    (combo SUPER SPACE)
    (Thread/sleep 300)
    (assert= true (modal-open?) "open")
    (tap KEY_X)
    (Thread/sleep 300)
    (assert= false (modal-open?) "closed")))

;; ----------------------------------------------------------------
;; Cleanup + results
;; ----------------------------------------------------------------

(hud/stop!)

(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors]
      (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
