#!/usr/bin/env bb

;; Unit tests for tecla.fsm - pure functions, no I/O needed.

(require '[tecla.fsm :as fsm])

(def test-results (atom {:passed 0 :failed 0 :errors []}))

(defn pass! [name]
  (swap! test-results update :passed inc)
  (println (str "  ✓ " name)))

(defn fail! [name msg]
  (swap! test-results (fn [r]
                        (-> r
                            (update :failed inc)
                            (update :errors conj {:test name :msg msg}))))
  (println (str "  ✗ " name ": " msg)))

(defmacro deftest [name & body]
  `(try
     ~@body
     (pass! ~(str name))
     (catch Exception e#
       (fail! ~(str name) (.getMessage e#)))))

(defn assert= [expected actual msg]
  (when (not= expected actual)
    (throw (ex-info (str msg " - expected: " (pr-str expected)
                         ", got: " (pr-str actual))
                    {}))))

;; ----------------------------------------------------------------
;; Fixtures
;; ----------------------------------------------------------------

(def config
  {:trigger {:mods [:super] :key :space}
   :root {:a {:desc "Apps"
              :keys {:e {:desc "Emacs" :action "emacs"}
                     :b {:desc "Browser" :action "brave"}}}
          :w {:desc "Windows"
              :keys {:h {:desc "Left" :action "wm-left"}
                     :comma {:desc "Resize" :transient true
                            :keys {:h {:desc "Shrink" :action "shrink"}
                                   :l {:desc "Grow" :action "grow"}}}}}
          :x {:desc "Execute" :action "rofi -show drun"}}})

(defn fresh [] (fsm/init config))

(defn ev-trigger [] {:type :trigger})
(defn ev-key [k] {:type :key :key k :press? true})
(defn ev-release [k] {:type :key :key k :press? false})

;; ----------------------------------------------------------------
;; Init
;; ----------------------------------------------------------------

(println "\n=== tecla.fsm unit tests ===\n")

(deftest "init returns idle state"
  (let [s (fresh)]
    (assert= :idle (:mode s) "mode")
    (assert= [] (:menu-stack s) "menu-stack")
    (assert= nil (:effect s) "effect")))

;; ----------------------------------------------------------------
;; Trigger
;; ----------------------------------------------------------------

(deftest "trigger transitions idle -> modal"
  (let [s (-> (fresh) (fsm/process-event (ev-trigger)))]
    (assert= :modal (:mode s) "mode")
    (assert= :enter-modal (get-in s [:effect :type]) "effect")))

(deftest "trigger in modal is a no-op"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-trigger)))]
    (assert= :modal (:mode s) "mode")))

;; ----------------------------------------------------------------
;; Submenu navigation
;; ----------------------------------------------------------------

(deftest "pressing submenu key pushes onto stack"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :a)))]
    (assert= :modal (:mode s) "still in modal")
    (assert= 2 (count (:menu-stack s)) "stack depth = 2")
    (assert= nil (:effect s) "no side effect for submenu")))

(deftest "current-menu reflects pushed submenu"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :a)))]
    (assert= #{:e :b} (set (keys (fsm/current-menu s))) "Apps submenu keys")))

;; ----------------------------------------------------------------
;; Action leaf
;; ----------------------------------------------------------------

(deftest "pressing action key exits modal"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :x)))]
    (assert= :idle (:mode s) "back to idle")
    (assert= "rofi -show drun" (get-in s [:effect :action]) "action value")))

(deftest "nested action: submenu then leaf"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :a))
              (fsm/process-event (ev-key :e)))]
    (assert= :idle (:mode s) "back to idle")
    (assert= "emacs" (get-in s [:effect :action]) "action")))

;; ----------------------------------------------------------------
;; Transient actions
;; ----------------------------------------------------------------

(deftest "transient submenu stays in modal after action"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :w))
              (fsm/process-event (ev-key :comma))
              (fsm/process-event (ev-key :h)))]
    (assert= :modal (:mode s) "still in modal (transient)")
    (assert= "shrink" (get-in s [:effect :action]) "action")))

;; ----------------------------------------------------------------
;; Escape
;; ----------------------------------------------------------------

(deftest "escape at root exits modal"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :esc)))]
    (assert= :idle (:mode s) "back to idle")
    (assert= :exit-modal (get-in s [:effect :type]) "effect")))

(deftest "escape in submenu pops one level"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :a))
              (fsm/process-event (ev-key :esc)))]
    (assert= :modal (:mode s) "still in modal")
    (assert= 1 (count (:menu-stack s)) "back to root level")))

(deftest "escape in nested submenu pops one level at a time"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :w))
              (fsm/process-event (ev-key :comma)))]
    (assert= 3 (count (:menu-stack s)) "3 levels deep")
    (let [s2 (fsm/process-event s (ev-key :esc))]
      (assert= 2 (count (:menu-stack s2)) "popped to 2")
      (let [s3 (fsm/process-event s2 (ev-key :esc))]
        (assert= 1 (count (:menu-stack s3)) "popped to root")
        (let [s4 (fsm/process-event s3 (ev-key :esc))]
          (assert= :idle (:mode s4) "exited modal"))))))

;; ----------------------------------------------------------------
;; Unbound keys
;; ----------------------------------------------------------------

(deftest "unbound key in modal is swallowed"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-key :z)))]
    (assert= :modal (:mode s) "still in modal")
    (assert= nil (:effect s) "no effect")))

;; ----------------------------------------------------------------
;; Key release events
;; ----------------------------------------------------------------

(deftest "key release in modal is ignored"
  (let [s (-> (fresh)
              (fsm/process-event (ev-trigger))
              (fsm/process-event (ev-release :a)))]
    (assert= :modal (:mode s) "still in modal")
    (assert= 1 (count (:menu-stack s)) "stack unchanged")
    (assert= nil (:effect s) "no effect")))

;; ----------------------------------------------------------------
;; Idle ignores keys
;; ----------------------------------------------------------------

(deftest "key events in idle produce no effect"
  (let [s (-> (fresh)
              (fsm/process-event (ev-key :a)))]
    (assert= :idle (:mode s) "still idle")
    (assert= nil (:effect s) "no effect")))

;; ----------------------------------------------------------------
;; Results
;; ----------------------------------------------------------------

(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors]
      (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
