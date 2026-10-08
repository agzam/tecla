#!/usr/bin/env bb
;; Runs every Hyprland wm/action against a live, disposable Hyprland and checks
;; the effect. Point HYPRLAND_INSTANCE_SIGNATURE at a nested instance (Hyprland
;; started inside a session); a drm-backed instance is refused. Needs kitty.

(require '[tecla.wm :as wm]
         '[tecla.wm.hyprland])

(def hypr-cmd #'tecla.wm.hyprland/hypr-cmd)
(def dispatch #'tecla.wm.hyprland/dispatch)

(let [{:keys [backend configProvider]} (hypr-cmd "status")]
  (when (or (nil? backend) (= "drm" backend))
    (println "refusing: HYPRLAND_INSTANCE_SIGNATURE is not a nested Hyprland")
    (System/exit 2))
  (println (str "\nhyprland live (" (or configProvider "hyprlang") " config):")))

(reset! wm/compositor :hyprland)

(def failures (atom 0))
(defn report [label pass? detail]
  (when-not pass? (swap! failures inc))
  (println (str "  " (if pass? "\u2713 " "\u2717 ") label (when-not pass? (str ": " detail)))))

(defn active [] (hypr-cmd "activewindow"))
(defn clients [] (hypr-cmd :clients))
(defn by-class [c] (first (filter #(= c (:class %)) (clients))))
(defn workspace-id [] (:id (hypr-cmd "activeworkspace")))

(defn wait-until [pred]
  (loop [n 50]
    (cond (pred) true
          (zero? n) false
          :else (do (Thread/sleep 100) (recur (dec n))))))

(defn check
  "Run action, give Hyprland a moment, then test ok? against the new state."
  [label action ok?]
  (let [result (action)]
    (Thread/sleep 300)
    (report label (and (= "ok" result) (ok?))
            (str "reply " (pr-str result) ", active "
                 (pr-str (select-keys (active) [:class :at :size :floating :pinned :fullscreen]))))))

(doseq [c ["probe-a" "probe-b" "probe-c"]]
  (wm/focus-or-launch {:class c :command (str "kitty --class " c)})
  (report (str "focus-or-launch starts " c) (wait-until #(by-class c)) "window never appeared"))

(check "focus-or-launch focuses probe-a" #(wm/focus-or-launch {:class "probe-a"})
       #(= "probe-a" (:class (active))))
(check "focus-right" wm/focus-right #(not= "probe-a" (:class (active))))
(check "focus-left" wm/focus-left #(= "probe-a" (:class (active))))
(let [start (:at (active))]
  (check "move-right" wm/move-right #(not= start (:at (active)))))
(let [start (:at (active))]
  (check "move-left" wm/move-left #(not= start (:at (active)))))
(check "maximize" wm/maximize #(= 1 (:fullscreen (active))))
(check "maximize again restores" wm/maximize #(= 0 (:fullscreen (active))))
(check "toggle-floating on" wm/toggle-floating #(:floating (active)))
(let [[w h] (:size (active))]
  (check "resize-active 20 10 on a floating window" #(wm/resize-active 20 10)
         #(= [(+ w 20) (+ h 10)] (:size (active)))))
(check "pin on" wm/pin #(:pinned (active)))
(check "pin off" wm/pin #(not (:pinned (active))))
(check "toggle-floating off" wm/toggle-floating #(not (:floating (active))))
(check "send-shortcut" #(wm/send-shortcut "" "escape" "activewindow") (constantly true))
(let [c (by-class "probe-c")]
  (check "focus-window-by-address" #(wm/focus-window-by-address (:address c))
         #(= "probe-c" (:class (active)))))
(let [focused (wm/focused-window)]
  (report "focused-window" (= "probe-c" (:class focused)) (pr-str focused)))

(dispatch "movetoworkspace 2" "hl.dsp.window.move({workspace = 2})")
(Thread/sleep 300)
(check "workspace-prev" wm/workspace-prev #(= 1 (workspace-id)))
(check "workspace-next" wm/workspace-next #(= 2 (workspace-id)))
(check "workspace-prev again" wm/workspace-prev #(= 1 (workspace-id)))
(check "move-to-monitor with one monitor" wm/move-to-monitor (constantly true))

(let [seen (atom [])]
  (wm/start-focus-listener! #(swap! seen conj %))
  (Thread/sleep 300)
  (wm/focus-or-launch {:class "probe-b"})
  (report "focus listener sees probe-b" (wait-until #(some #{"probe-b"} @seen)) (pr-str @seen))
  (wm/stop-focus-listener!))

(let [address (:address (active))]
  (check "minimize" wm/minimize
         #(= "special:minimized"
             (get-in (first (filter (fn [c] (= address (:address c))) (clients)))
                     [:workspace :name]))))

(doseq [c ["probe-a" "probe-b" "probe-c"]]
  (when-let [{:keys [address]} (by-class c)]
    (wm/focus-window-by-address address)
    (Thread/sleep 100)
    (wm/close-window)))
(report "close-window closes every probe" (wait-until #(empty? (filter (comp #{"probe-a" "probe-b" "probe-c"} :class) (clients))))
        (pr-str (map :class (clients))))

(println (format "\nResults: %d failed" @failures))
(System/exit (if (zero? @failures) 0 1))
