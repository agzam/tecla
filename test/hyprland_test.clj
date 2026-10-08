#!/usr/bin/env bb

(require '[tecla.wm :as wm]
         '[tecla.wm.hyprland])

(def test-results (atom {:passed 0 :failed 0 :errors []}))
(defn pass! [n] (swap! test-results update :passed inc) (println (str "  \u2713 " n)))
(defn fail! [n msg]
  (swap! test-results #(-> % (update :failed inc) (update :errors conj {:test n :msg msg})))
  (println (str "  \u2717 " n ": " msg)))
(defmacro deftest [name & body]
  `(try ~@body (pass! (str ~name))
        (catch Exception e# (fail! (str ~name) (.getMessage e#)))))
(defn assert= [expected actual msg]
  (when (not= expected actual)
    (throw (ex-info (str msg " - expected: " (pr-str expected)
                         ", got: " (pr-str actual)) {}))))

(reset! wm/compositor :hyprland)

(defn sent-dispatches
  "Run f against a fake Hyprland whose `status` reply is `status`.
   Returns the dispatch commands f sent."
  [status f]
  (let [sent (atom [])]
    (with-redefs [tecla.wm.hyprland/hypr-cmd
                  (fn [cmd]
                    (cond
                      (= cmd "status") status
                      (= cmd :clients) [{:class "kitty" :address "0xabc"}]
                      :else (do (swap! sent conj cmd) "ok")))]
      (f))
    @sent))

(def lua-status {:configProvider "lua" :backend "drm"})
(def hyprlang-status {:configProvider "hyprlang" :backend "drm"})

;; [action args legacy lua]
(def cases
  [[:focus-left [] "movefocus l" "hl.dsp.focus({direction = 'l'})"]
   [:focus-right [] "movefocus r" "hl.dsp.focus({direction = 'r'})"]
   [:focus-up [] "movefocus u" "hl.dsp.focus({direction = 'u'})"]
   [:focus-down [] "movefocus d" "hl.dsp.focus({direction = 'd'})"]
   [:move-left [] "movewindow l" "hl.dsp.window.move({direction = 'l'})"]
   [:move-right [] "movewindow r" "hl.dsp.window.move({direction = 'r'})"]
   [:move-up [] "movewindow u" "hl.dsp.window.move({direction = 'u'})"]
   [:move-down [] "movewindow d" "hl.dsp.window.move({direction = 'd'})"]
   [:maximize [] "fullscreen 1" "hl.dsp.window.fullscreen({mode = 'maximized'})"]
   [:close-window [] "killactive" "hl.dsp.window.close()"]
   [:workspace-next [] "workspace e+1" "hl.dsp.focus({workspace = 'e+1'})"]
   [:workspace-prev [] "workspace e-1" "hl.dsp.focus({workspace = 'e-1'})"]
   [:resize-active [-20 0] "resizeactive -20 0" "hl.dsp.window.resize({x = -20, y = 0, relative = true})"]
   [:toggle-floating [] "togglefloating" "hl.dsp.window.float({action = 'toggle'})"]
   [:pin [] "pin" "hl.dsp.window.pin()"]
   [:minimize [] "movetoworkspacesilent special:minimized"
    "hl.dsp.window.move({workspace = 'special:minimized', follow = false})"]
   [:move-to-monitor [] "movewindow mon:+1" "hl.dsp.window.move({monitor = '+1'})"]
   [:send-shortcut ["SHIFT" "n" "activewindow"] "sendshortcut SHIFT,n,activewindow"
    "hl.dsp.send_shortcut({mods = \"SHIFT\", key = \"n\", window = \"activewindow\"})"]
   [:focus-window-by-address ["0x1"] "focuswindow address:0x1"
    "hl.dsp.focus({window = \"address:0x1\"})"]])

;; ================================================================
(println "\nhyprland dispatch:")

(doseq [[action args legacy lua] cases]
  (deftest (str action " under a Lua config")
    (assert= [(str "dispatch " lua)]
             (sent-dispatches lua-status #(apply wm/action action args))
             "dispatch"))
  (deftest (str action " under hyprlang")
    (assert= [(str "dispatch " legacy)]
             (sent-dispatches hyprlang-status #(apply wm/action action args))
             "dispatch")))

(deftest "Hyprland without a status command gets hyprlang syntax"
  (assert= ["dispatch killactive"]
           (sent-dispatches "unknown request" #(wm/action :close-window))
           "dispatch"))

(deftest "focus-or-launch focuses a matching window"
  (assert= ["dispatch hl.dsp.focus({window = \"address:0xabc\"})"]
           (sent-dispatches lua-status #(wm/focus-or-launch {:class "kitty"}))
           "dispatch"))

(deftest "focus-or-launch quotes the launch command for Lua"
  (assert= ["dispatch hl.dsp.exec_cmd(\"sh -c \\\"echo a\\\\\\\\b\\\"\\nnext\")"]
           (sent-dispatches lua-status
                            #(wm/focus-or-launch {:class "foot"
                                                  :command "sh -c \"echo a\\\\b\"\nnext"}))
           "dispatch"))

(deftest "focus-or-launch keeps the raw command for hyprlang"
  (assert= ["dispatch exec foot --title \"a b\""]
           (sent-dispatches hyprlang-status
                            #(wm/focus-or-launch {:class "foot" :command "foot --title \"a b\""}))
           "dispatch"))

(deftest "send-shortcut without a window omits it under Lua"
  (assert= ["dispatch hl.dsp.send_shortcut({mods = \"\", key = \"escape\"})"]
           (sent-dispatches lua-status #(wm/send-shortcut "" "escape" nil))
           "dispatch"))

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
