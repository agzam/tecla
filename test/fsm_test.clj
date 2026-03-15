#!/usr/bin/env bb

(require '[tecla.fsm :as fsm])

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
;; Config
;; ----------------------------------------------------------------

(def root-menu
  {:a {:desc "Apps"
       :keys {:e {:desc "Emacs" :action "emacs"}
              :b {:desc "Browser" :action "brave"}
              :u {:desc "Utils"
                  :keys {:d {:desc "ncdu" :action "ncdu"}
                         :h {:desc "htop" :action "htop"}
                         :n {:desc "Network"
                             :keys {:w {:desc "Wireshark" :action "wireshark"}
                                    :p {:desc "Ping" :action "ping"}}}}}}}
   :w {:desc "Windows"
       :keys {:h {:desc "Left" :action "wm-left"}
              :l {:desc "Right" :action "wm-right"}
              :comma {:desc "Resize" :transient true
                      :keys {:h {:desc "Shrink" :action "shrink"}
                             :l {:desc "Grow" :action "grow"}}}}}
   :x {:desc "Execute" :action "rofi -show drun"}
   :t {:desc "Test mods"
       :keys {:b {:desc "Plain B" :action "plain-b"}
              [:shift :b] {:desc "Shift B" :action "shift-b"}
              [:ctrl :shift :x] {:desc "Ctrl+Shift+X" :action "csx"}}}})

(def modal-entry {:desc "Main" :keys root-menu})
(def config {:global-keymap {[:super :space] modal-entry}})

(defn fresh [] (fsm/init config))
(defn ev-enter [] {:type :enter-modal :entry modal-entry})
(defn ev
  ([k] {:type :key :key k :press? true})
  ([k mods] {:type :key :key k :mods mods :press? true}))
(defn ev-release [k] {:type :key :key k :press? false})

(defn pipeline [& events]
  (reduce fsm/process-event (fresh) events))

;; ================================================================
(println "\n=== tecla.fsm unit tests ===\n")
;; ================================================================

;; Basic state

(deftest "init returns idle"
  (let [s (fresh)]
    (assert= :idle (:mode s) "mode")
    (assert= [] (:menu-stack s) "stack")))

(deftest "enter-modal -> modal"
  (let [s (pipeline (ev-enter))]
    (assert= :modal (:mode s) "mode")
    (assert= :enter-modal (get-in s [:effect :type]) "effect")))

(deftest "enter-modal in modal is no-op"
  (let [s (pipeline (ev-enter) (ev-enter))]
    (assert= :modal (:mode s) "mode")
    (assert= 1 (count (:menu-stack s)) "stack")))

(deftest "key in idle has no effect"
  (let [s (pipeline (ev :a))]
    (assert= :idle (:mode s) "mode")))

(deftest "key release in modal is ignored"
  (let [s (pipeline (ev-enter) (ev-release :a))]
    (assert= :modal (:mode s) "mode")
    (assert= 1 (count (:menu-stack s)) "stack")))

(deftest "unbound key swallowed"
  (let [s (pipeline (ev-enter) (ev :z))]
    (assert= :modal (:mode s) "mode")
    (assert= nil (:effect s) "no effect")))

;; Submenu navigation

(deftest "submenu push"
  (let [s (pipeline (ev-enter) (ev :a))]
    (assert= 2 (count (:menu-stack s)) "depth")
    (assert= #{:e :b :u} (set (keys (fsm/current-menu s))) "keys")))

(deftest "deep nesting: 3 levels"
  (let [s (pipeline (ev-enter) (ev :a) (ev :u))]
    (assert= 3 (count (:menu-stack s)) "depth")
    (assert= #{:d :h :n} (set (keys (fsm/current-menu s))) "keys")))

(deftest "deep nesting: 4 levels"
  (let [s (pipeline (ev-enter) (ev :a) (ev :u) (ev :n))]
    (assert= 4 (count (:menu-stack s)) "depth")))

(deftest "submenu stores :desc"
  (let [s (pipeline (ev-enter) (ev :a))]
    (assert= "Apps" (:desc (meta (fsm/current-menu s))) "desc")))

;; Action leaf

(deftest "root action exits modal"
  (let [s (pipeline (ev-enter) (ev :x))]
    (assert= :idle (:mode s) "mode")
    (assert= "rofi -show drun" (get-in s [:effect :action]) "action")))

(deftest "nested action"
  (let [s (pipeline (ev-enter) (ev :a) (ev :e))]
    (assert= :idle (:mode s) "mode")
    (assert= "emacs" (get-in s [:effect :action]) "action")))

(deftest "4-level deep action"
  (let [s (pipeline (ev-enter) (ev :a) (ev :u) (ev :n) (ev :w))]
    (assert= :idle (:mode s) "mode")
    (assert= "wireshark" (get-in s [:effect :action]) "action")))

;; Escape exits ALL

(deftest "escape at root"
  (let [s (pipeline (ev-enter) (ev :esc))]
    (assert= :idle (:mode s) "mode")))

(deftest "escape in submenu exits all"
  (let [s (pipeline (ev-enter) (ev :a) (ev :esc))]
    (assert= :idle (:mode s) "mode")
    (assert= [] (:menu-stack s) "stack")))

(deftest "escape at 4 levels"
  (let [s (pipeline (ev-enter) (ev :a) (ev :u) (ev :n) (ev :esc))]
    (assert= :idle (:mode s) "mode")))

;; :previous

(deftest ":previous pops one level"
  (let [cfg (assoc-in config [:global-keymap [:super :space] :keys :a :keys :u :keys :p] :previous)
        entry (get-in cfg [:global-keymap [:super :space]])
        s (reduce fsm/process-event (fsm/init cfg)
                  [{:type :enter-modal :entry entry} (ev :a) (ev :u) (ev :p)])]
    (assert= :modal (:mode s) "modal")
    (assert= 2 (count (:menu-stack s)) "popped")))

(deftest ":previous at root exits"
  (let [cfg (assoc-in config [:global-keymap [:super :space] :keys :p] :previous)
        entry (get-in cfg [:global-keymap [:super :space]])
        s (reduce fsm/process-event (fsm/init cfg)
                  [{:type :enter-modal :entry entry} (ev :p)])]
    (assert= :idle (:mode s) "exits")))

;; :root

(deftest ":root jumps to root"
  (let [cfg (assoc-in config [:global-keymap [:super :space] :keys :a :keys :u :keys :n :keys :r] :root)
        entry (get-in cfg [:global-keymap [:super :space]])
        s (reduce fsm/process-event (fsm/init cfg)
                  [{:type :enter-modal :entry entry} (ev :a) (ev :u) (ev :n) (ev :r)])]
    (assert= :modal (:mode s) "modal")
    (assert= 1 (count (:menu-stack s)) "at root")))

;; :exit

(deftest ":exit from deep"
  (let [cfg (assoc-in config [:global-keymap [:super :space] :keys :a :keys :u :keys :q] :exit)
        entry (get-in cfg [:global-keymap [:super :space]])
        s (reduce fsm/process-event (fsm/init cfg)
                  [{:type :enter-modal :entry entry} (ev :a) (ev :u) (ev :q)])]
    (assert= :idle (:mode s) "mode")
    (assert= :exit-modal (get-in s [:effect :type]) "effect")))

;; Transient

(deftest "transient stays in modal"
  (let [s (pipeline (ev-enter) (ev :w) (ev :comma) (ev :h))]
    (assert= :modal (:mode s) "modal")
    (assert= "shrink" (get-in s [:effect :action]) "action")))

(deftest "transient multiple actions"
  (let [s (pipeline (ev-enter) (ev :w) (ev :comma) (ev :h) (ev :l) (ev :h))]
    (assert= :modal (:mode s) "modal")))

(deftest "transient escape exits all"
  (let [s (pipeline (ev-enter) (ev :w) (ev :comma) (ev :esc))]
    (assert= :idle (:mode s) "idle")))

;; Full journeys

(deftest "re-trigger after action"
  (let [s (pipeline (ev-enter) (ev :x) (ev-enter))]
    (assert= :modal (:mode s) "modal")
    (assert= 1 (count (:menu-stack s)) "fresh root")))

(deftest "re-trigger after escape"
  (let [s (pipeline (ev-enter) (ev :a) (ev :esc) (ev-enter))]
    (assert= :modal (:mode s) "modal")))

;; ---- Modifier+key in modal menus ----

(deftest "modal: bare :b with mods=0 matches plain entry"
  (let [s (pipeline (ev-enter) (ev :t) (ev :b 0))]
    (assert= :idle (:mode s) "action exits modal")
    (assert= "plain-b" (get-in (:effect s) [:action]) "matched plain :b")))

(deftest "modal: :b with mods=shift matches [:shift :b]"
  (let [s (pipeline (ev-enter) (ev :t) (ev :b 2))]
    (assert= :idle (:mode s) "action exits modal")
    (assert= "shift-b" (get-in (:effect s) [:action]) "matched [:shift :b]")))

(deftest "modal: :x with mods=ctrl+shift matches [:ctrl :shift :x]"
  (let [s (pipeline (ev-enter) (ev :t) (ev :x 3))] ;; ctrl=1 + shift=2 = 3
    (assert= :idle (:mode s) "action exits modal")
    (assert= "csx" (get-in (:effect s) [:action]) "matched [:ctrl :shift :x]")))

(deftest "modal: :b with no mods field falls back to plain"
  (let [s (pipeline (ev-enter) (ev :t) (ev :b))]
    (assert= :idle (:mode s) "action exits modal")
    (assert= "plain-b" (get-in (:effect s) [:action]) "old events without mods still work")))

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
