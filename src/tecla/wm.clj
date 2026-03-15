(ns tecla.wm
  "Window manager actions. Single multimethod dispatching on
   [compositor action] pairs. Each compositor backend registers
   its own methods.")

(defonce compositor (atom nil))

(defn set-compositor! [c]
  (reset! compositor c)
  (try
    (require (symbol (str "tecla.wm." (name c))))
    (catch Exception e
      (println "Warning: no wm backend for" c "-" (.getMessage e))))
  (println "Compositor:" c))

(defn detect-compositor! []
  (let [desktop (some-> (System/getenv "XDG_CURRENT_DESKTOP")
                        .toLowerCase keyword)]
    (set-compositor! (or desktop :generic))))

(defmulti action
  "Dispatch a WM action. Called as (wm/action :focus-left) or
   (wm/action :focus-left :some-arg). Dispatches on [@compositor, action-name]."
  (fn [action-name & _args] [@compositor action-name]))

(defmethod action :default [action-name & _]
  (println (str "wm/action: " action-name " not implemented for " @compositor)))

;; Convenience fns for config readability: (wm/focus-left) -> (wm/action :focus-left)
(defn focus-left     [& args] (apply action :focus-left args))
(defn focus-right    [& args] (apply action :focus-right args))
(defn focus-up       [& args] (apply action :focus-up args))
(defn focus-down     [& args] (apply action :focus-down args))
(defn move-left      [& args] (apply action :move-left args))
(defn move-right     [& args] (apply action :move-right args))
(defn move-up        [& args] (apply action :move-up args))
(defn move-down      [& args] (apply action :move-down args))
(defn maximize       [& args] (apply action :maximize args))
(defn close-window   [& args] (apply action :close-window args))
(defn workspace-next [& args] (apply action :workspace-next args))
(defn workspace-prev [& args] (apply action :workspace-prev args))
(defn focused-window    [& args] (apply action :focused-window args))
(defn focus-or-launch
  "Focus a window matching :class (or :initialTitle, etc.), or launch :command.
   (wm/focus-or-launch {:class \"kitty\" :command \"kitty\"})
   (wm/focus-or-launch {:class \"kitty\"})  ;; command defaults to class
   Legacy positional form also works:
   (wm/focus-or-launch \"kitty\")
   (wm/focus-or-launch \"kitty\" \"kitty --title foo\")"
  ([opts-or-class]
   (if (map? opts-or-class)
     (action :focus-or-launch opts-or-class)
     (action :focus-or-launch {:class (str opts-or-class)})))
  ([class command]
   (action :focus-or-launch {:class (str class) :command (str command)})))
(defn resize-active     [& args] (apply action :resize-active args))
