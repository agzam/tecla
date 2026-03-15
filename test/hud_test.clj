#!/usr/bin/env bb

;; Tests for the HUD poller. Mocks send! to capture JSON messages,
;; verifies correct show/hide commands at the right times.

(require '[tecla.fsm :as fsm]
         '[tecla.keys :as keys]
         '[tecla.core :as core]
         '[tecla.bridge :as bridge]
         '[tecla.hud :as hud]
         '[clojure.string :as str])

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
(alter-var-root #'bridge/emit! (constantly mock-emit!))

;; ---- Mock HUD send ----

(def sent (atom []))
(alter-var-root #'tecla.hud/send! (constantly (fn [json] (swap! sent conj json))))

(defn last-sent [] (peek @sent))
(defn sent-contains? [s] (some #(str/includes? % s) @sent))
(defn last-is-show? [] (some-> (last-sent) (str/includes? "\"show\":true")))
(defn last-is-hide? [] (some-> (last-sent) (str/includes? "\"show\":false")))
(defn clear-sent! [] (reset! sent []))

;; ---- Keys ----

(def SUPER 125) (def CTRL 29) (def SPACE 57) (def KEY_A 30)
(def KEY_E 18) (def KEY_T 20) (def KEY_ESC 1) (def KEY_X 45)

(defn press [c] (core/on-key-event c 1))
(defn release [c] (core/on-key-event c 0))
(defn tap [c] (press c) (release c))
(defn combo [m k] (press m) (press k) (release k) (release m))

;; ---- Config (unified keymap) ----

(def config
  {:global-keymap
   {[:super :t] [:ctrl :t]
    [:super :space]
    {:desc "Main"
     :keys {:a {:desc "Apps"
                :keys {:e {:desc "Emacs" :action "echo emacs"}
                       [:shift :b] {:desc "Brave Private" :action "echo bp"}}}
            :x {:desc "Execute" :action "echo exec"}}}}})

(defn reset-state! []
  (reset! core/fsm-state (fsm/init config))
  (reset! core/kbd-state
          {:mod-state 0 :buffered-mods 0 :consumed-mods 0
           :active-sims {} :buffer-mods (core/compute-buffer-mods (:global-keymap config))})
  (reset! emitted [])
  (clear-sent!))

;; ================================================================
(println "\n=== HUD poller tests ===\n")
;; ================================================================

;; Start poller (no gjs process needed - send! is mocked)
(reset-state!)
(#'tecla.hud/start-poller! core/fsm-state fsm/current-menu)
(Thread/sleep 100)

;; ---- Show/hide timing ----

(deftest "no messages sent initially"
  (assert= 0 (count @sent) "no messages"))

(deftest "trigger sends show with root menu"
  (reset-state!)
  (combo SUPER SPACE)
  (Thread/sleep 200)
  (assert= true (last-is-show?) "show sent")
  (assert= true (sent-contains? "Apps") "contains Apps")
  (assert= true (sent-contains? "Execute") "contains Execute")
  (assert= true (sent-contains? "Main") "title is Main"))

(deftest "submenu updates show content"
  ;; Still in modal from previous
  (clear-sent!)
  (tap KEY_A)
  (Thread/sleep 200)
  (assert= true (last-is-show?) "show sent")
  (assert= true (sent-contains? "Emacs") "contains Emacs")
  (assert= true (sent-contains? "Apps") "title is Apps")
  ;; Vector key [:shift :b] should render as "shift+b"
  (assert= true (sent-contains? "shift+b") "vector key label")
  (assert= true (sent-contains? "Brave Private") "vector key desc"))

(deftest "escape sends hide"
  (clear-sent!)
  (tap KEY_ESC)
  (Thread/sleep 200)
  (assert= true (last-is-hide?) "hide sent")
  (assert= :idle (:mode @core/fsm-state) "idle"))

(deftest "re-trigger shows root menu again"
  (clear-sent!)
  (combo SUPER SPACE)
  (Thread/sleep 200)
  (assert= true (last-is-show?) "show sent")
  (assert= true (sent-contains? "Apps") "root menu")
  (tap KEY_ESC)
  (Thread/sleep 200))

(deftest "action sends hide"
  (reset-state!)
  (clear-sent!)
  (combo SUPER SPACE)
  (Thread/sleep 200)
  (tap KEY_X)
  (Thread/sleep 200)
  (assert= true (last-is-hide?) "hide after action")
  (assert= :idle (:mode @core/fsm-state) "idle"))

;; ---- Key processing not affected ----

(deftest "passthrough works with poller active"
  (reset-state!)
  (tap KEY_A)
  (assert= true (emitted? KEY_A 1) "A press"))

(deftest "remap works with poller active"
  (reset-state!)
  (combo SUPER KEY_T)
  (assert= true (emitted? CTRL 1) "Ctrl")
  (assert= true (not-emitted? SUPER 1) "no Super"))

(deftest "re-trigger after action"
  (reset-state!)
  (combo SUPER SPACE)
  (tap KEY_A) (tap KEY_E)
  (Thread/sleep 200)
  (assert= :idle (:mode @core/fsm-state) "idle")
  (combo SUPER SPACE)
  (Thread/sleep 200)
  (assert= :modal (:mode @core/fsm-state) "modal again")
  (assert= true (last-is-show?) "show sent")
  (tap KEY_ESC)
  (Thread/sleep 200))

(deftest "rapid cycling 5x"
  (reset-state!)
  (dotimes [_ 5]
    (clear-sent!)
    (combo SUPER SPACE)
    (Thread/sleep 150)
    (assert= true (last-is-show?) "show")
    (clear-sent!)
    (tap KEY_X)
    (Thread/sleep 150)
    (assert= true (last-is-hide?) "hide")))

;; ---- JSON structure ----

(deftest "show message is valid JSON structure"
  (reset-state!)
  (clear-sent!)
  (combo SUPER SPACE)
  (Thread/sleep 200)
  (let [msg (last-sent)]
    (assert= true (str/starts-with? msg "{") "starts with {")
    (assert= true (str/includes? msg "\"show\":true") "has show:true")
    (assert= true (str/includes? msg "\"name\":\"Main\"") "has name")
    (assert= true (str/includes? msg "\"keys\":[") "has keys array"))
  (tap KEY_ESC)
  (Thread/sleep 200))

;; ---- Cleanup ----

(reset! @(ns-resolve 'tecla.hud 'poller-running) false)

(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
