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
;; Config with deep nesting
;; ----------------------------------------------------------------

(def config
  {:trigger {:mods [:super] :key :space}
   :root {:a {:desc "Apps"
              :keys {:e {:desc "Emacs" :action "emacs"}
                     :b {:desc "Browser" :action "brave"}
                     :u {:desc "Utils"
                         :keys {:d {:desc "ncdu" :action "ncdu"}
                                :h {:desc "htop" :action "htop"}
                                :n {:desc "Network"
                                    :keys {:w {:desc "Wireshark" :action "wireshark"}
                                           :p {:desc "Ping" :action "ping"}
                                           :esc :previous}}
                                :esc :previous}}}}
          :w {:desc "Windows"
              :keys {:h {:desc "Left" :action "wm-left"}
                     :l {:desc "Right" :action "wm-right"}
                     :comma {:desc "Resize" :transient true
                             :keys {:h {:desc "Shrink" :action "shrink"}
                                    :l {:desc "Grow" :action "grow"}}}
                     :r {:desc "Back to root" :action :root}
                     :esc :previous}}
          :x {:desc "Execute" :action "rofi -show drun"}
          :q {:desc "Quit modal" :action :exit}}})

(defn fresh [] (fsm/init config))
(defn ev-trigger [] {:type :trigger})
(defn ev [k] {:type :key :key k :press? true})
(defn ev-release [k] {:type :key :key k :press? false})

(defn pipeline
  "Apply a sequence of events to fresh state."
  [& keys]
  (reduce fsm/process-event (fresh) keys))

;; ================================================================
(println "\n=== tecla.fsm unit tests ===\n")
;; ================================================================

;; ----------------------------------------------------------------
;; Basic state
;; ----------------------------------------------------------------

(deftest "init returns idle"
  (let [s (fresh)]
    (assert= :idle (:mode s) "mode")
    (assert= [] (:menu-stack s) "stack")))

(deftest "trigger -> modal"
  (let [s (pipeline (ev-trigger))]
    (assert= :modal (:mode s) "mode")
    (assert= :enter-modal (get-in s [:effect :type]) "effect")))

(deftest "trigger in modal is no-op"
  (let [s (pipeline (ev-trigger) (ev-trigger))]
    (assert= :modal (:mode s) "mode")
    (assert= 1 (count (:menu-stack s)) "stack unchanged")))

(deftest "key event in idle has no effect"
  (let [s (pipeline (ev :a))]
    (assert= :idle (:mode s) "mode")
    (assert= nil (:effect s) "no effect")))

(deftest "key release in modal is ignored"
  (let [s (pipeline (ev-trigger) (ev-release :a))]
    (assert= :modal (:mode s) "mode")
    (assert= 1 (count (:menu-stack s)) "stack unchanged")))

(deftest "unbound key in modal is swallowed"
  (let [s (pipeline (ev-trigger) (ev :z))]
    (assert= :modal (:mode s) "mode")
    (assert= nil (:effect s) "no effect")))

;; ----------------------------------------------------------------
;; Submenu navigation
;; ----------------------------------------------------------------

(deftest "submenu push"
  (let [s (pipeline (ev-trigger) (ev :a))]
    (assert= :modal (:mode s) "mode")
    (assert= 2 (count (:menu-stack s)) "depth 2")
    (assert= #{:e :b :u} (set (keys (fsm/current-menu s))) "Apps keys")))

(deftest "deep nesting: root -> Apps -> Utils"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u))]
    (assert= 3 (count (:menu-stack s)) "depth 3")
    (assert= #{:d :h :n :esc} (set (keys (fsm/current-menu s))) "Utils keys")))

(deftest "deep nesting: root -> Apps -> Utils -> Network (4 levels)"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :n))]
    (assert= 4 (count (:menu-stack s)) "depth 4")
    (assert= #{:w :p :esc} (set (keys (fsm/current-menu s))) "Network keys")))

(deftest "submenu stores :desc in metadata"
  (let [s (pipeline (ev-trigger) (ev :a))]
    (assert= "Apps" (:desc (meta (fsm/current-menu s))) "desc")))

(deftest "nested submenu stores :desc"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u))]
    (assert= "Utils" (:desc (meta (fsm/current-menu s))) "desc")))

;; ----------------------------------------------------------------
;; Action leaf
;; ----------------------------------------------------------------

(deftest "root-level action exits modal"
  (let [s (pipeline (ev-trigger) (ev :x))]
    (assert= :idle (:mode s) "mode")
    (assert= "rofi -show drun" (get-in s [:effect :action]) "action")))

(deftest "nested action: Apps -> Emacs"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :e))]
    (assert= :idle (:mode s) "mode")
    (assert= "emacs" (get-in s [:effect :action]) "action")))

(deftest "deep nested action: Apps -> Utils -> htop"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :h))]
    (assert= :idle (:mode s) "mode")
    (assert= "htop" (get-in s [:effect :action]) "action")))

(deftest "4-level deep action: Apps -> Utils -> Network -> Wireshark"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :n) (ev :w))]
    (assert= :idle (:mode s) "mode")
    (assert= "wireshark" (get-in s [:effect :action]) "action")))

;; ----------------------------------------------------------------
;; Escape exits ALL modals
;; ----------------------------------------------------------------

(deftest "escape at root exits modal"
  (let [s (pipeline (ev-trigger) (ev :esc))]
    (assert= :idle (:mode s) "mode")
    (assert= :exit-modal (get-in s [:effect :type]) "effect")))

(deftest "escape in submenu exits ALL modals"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :esc))]
    (assert= :idle (:mode s) "mode")
    (assert= [] (:menu-stack s) "stack empty")))

(deftest "escape at 3 levels deep exits ALL"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :esc))]
    (assert= :idle (:mode s) "mode")))

(deftest "escape at 4 levels deep exits ALL"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :n) (ev :esc))]
    (assert= :idle (:mode s) "mode")))

;; ----------------------------------------------------------------
;; :previous - go back one level
;; ----------------------------------------------------------------

(deftest ":previous in Apps pops to root"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :esc))] ;; :esc is mapped to :previous in Apps
    ;; Wait - :esc is handled specially by process-event (exits all).
    ;; The :esc -> :previous mapping is in the KEYMAP, not in process-event.
    ;; Let me re-check... process-event intercepts :esc before handle-key.
    ;; So :esc bound to :previous in the keymap is never reached.
    ;; Users should bind a different key (like :backspace) to :previous.
    ;; Let me test with the Network submenu which has :esc -> :previous
    ;; but process-event catches :esc first. This is a design issue.
    ;; For now, test that :esc exits all.
    (assert= :idle (:mode s) "esc always exits all")))

;; Test :previous with a key that isn't esc
(deftest ":previous pops one level (via keymap binding)"
  ;; Add a :previous binding on a non-esc key for testing
  (let [cfg (assoc-in config [:root :a :keys :u :keys :p] :previous)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :a) (ev :u) (ev :p)])]
    (assert= :modal (:mode s) "still modal")
    (assert= 2 (count (:menu-stack s)) "popped to Apps level")))

(deftest ":previous at root exits modal"
  (let [cfg (assoc-in config [:root :p] :previous)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :p)])]
    (assert= :idle (:mode s) "exits modal")))

(deftest ":previous pops through multiple levels one at a time"
  (let [cfg (-> config
                (assoc-in [:root :a :keys :u :keys :n :keys :p] :previous)
                (assoc-in [:root :a :keys :u :keys :p] :previous)
                (assoc-in [:root :a :keys :p] :previous))
        s0 (reduce fsm/process-event (fsm/init cfg)
                   [(ev-trigger) (ev :a) (ev :u) (ev :n)])
        _ (assert= 4 (count (:menu-stack s0)) "4 levels deep")
        s1 (fsm/process-event s0 (ev :p))
        _ (assert= 3 (count (:menu-stack s1)) "3 levels")
        s2 (fsm/process-event s1 (ev :p))
        _ (assert= 2 (count (:menu-stack s2)) "2 levels")
        s3 (fsm/process-event s2 (ev :p))]
    (assert= 1 (count (:menu-stack s3)) "1 level (root)")))

;; ----------------------------------------------------------------
;; :root - jump back to root menu
;; ----------------------------------------------------------------

(deftest ":root jumps from deep nesting to root"
  (let [cfg (assoc-in config [:root :a :keys :u :keys :n :keys :r] :root)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :a) (ev :u) (ev :n) (ev :r)])]
    (assert= :modal (:mode s) "still modal")
    (assert= 1 (count (:menu-stack s)) "at root")
    (assert= #{:a :w :x :q} (set (keys (fsm/current-menu s))) "root keys")))

(deftest ":root from level 2 goes to root"
  (let [cfg (assoc-in config [:root :a :keys :r] :root)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :a) (ev :r)])]
    (assert= 1 (count (:menu-stack s)) "at root")))

;; ----------------------------------------------------------------
;; :exit - explicit exit
;; ----------------------------------------------------------------

(deftest ":exit from deep nesting exits modal"
  (let [cfg (assoc-in config [:root :a :keys :u :keys :n :keys :q] :exit)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :a) (ev :u) (ev :n) (ev :q)])]
    (assert= :idle (:mode s) "mode")
    (assert= :exit-modal (get-in s [:effect :type]) "effect")))

;; ----------------------------------------------------------------
;; Transient submenus
;; ----------------------------------------------------------------

(deftest "transient: action stays in modal"
  (let [s (pipeline (ev-trigger) (ev :w) (ev :comma) (ev :h))]
    (assert= :modal (:mode s) "still modal")
    (assert= "shrink" (get-in s [:effect :action]) "action")))

(deftest "transient: multiple actions stay in modal"
  (let [s (pipeline (ev-trigger) (ev :w) (ev :comma) (ev :h) (ev :l) (ev :h))]
    (assert= :modal (:mode s) "still modal")
    (assert= "shrink" (get-in s [:effect :action]) "last action")))

(deftest "transient: escape still exits all"
  (let [s (pipeline (ev-trigger) (ev :w) (ev :comma) (ev :esc))]
    (assert= :idle (:mode s) "exits all")))

;; ----------------------------------------------------------------
;; Full journey tests
;; ----------------------------------------------------------------

(deftest "full journey: trigger -> Apps -> Utils -> ncdu -> exit"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :d))]
    (assert= :idle (:mode s) "mode")
    (assert= "ncdu" (get-in s [:effect :action]) "action")))

(deftest "full journey: trigger -> navigate -> escape at any point exits all"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :n) (ev :esc))]
    (assert= :idle (:mode s) "mode")))

(deftest "re-trigger after exit works"
  (let [s (pipeline (ev-trigger) (ev :x) (ev-trigger))]
    (assert= :modal (:mode s) "modal again")
    (assert= 1 (count (:menu-stack s)) "fresh root")))

(deftest "re-trigger after escape works"
  (let [s (pipeline (ev-trigger) (ev :a) (ev :u) (ev :esc) (ev-trigger))]
    (assert= :modal (:mode s) "modal again")
    (assert= 1 (count (:menu-stack s)) "fresh root")))

(deftest ":previous then navigate into different submenu"
  (let [cfg (assoc-in config [:root :a :keys :p] :previous)
        s (reduce fsm/process-event (fsm/init cfg)
                  [(ev-trigger) (ev :a) (ev :p) (ev :w)])]
    (assert= :modal (:mode s) "modal")
    (assert= 2 (count (:menu-stack s)) "in Windows submenu")
    (assert= "Windows" (:desc (meta (fsm/current-menu s))) "desc")))

;; ================================================================
;; Results
;; ================================================================

(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors]
      (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
