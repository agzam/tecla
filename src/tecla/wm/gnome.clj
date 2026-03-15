(ns tecla.wm.gnome
  "GNOME/Wayland backend for wm/action.

   GNOME 41+ locked down org.gnome.Shell.Eval and
   org.gnome.Shell.Introspect to privileged callers only, so there is
   no built-in way to list or activate windows from the outside.

   Window listing/activation requires the 'window-calls' GNOME Shell
   extension (https://extensions.gnome.org/extension/4724/window-calls/)
   which exposes a D-Bus interface for List, Focus, etc.

   Basic window management (close, maximize, tiling) uses keyboard
   shortcuts via bridge/emit!."
  (:require [tecla.wm :as wm]
            [tecla.bridge :as bridge]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; ----------------------------------------------------------------
;; window-calls extension (D-Bus interface)
;; https://github.com/ickyicky/window-calls
;; ----------------------------------------------------------------

(def ^:private wc-dest "org.gnome.Shell")
(def ^:private wc-path "/org/gnome/Shell/Extensions/Windows")
(def ^:private wc-iface "org.gnome.Shell.Extensions.Windows")

(defonce ^:private ext-checked? (atom false))
(defonce ^:private ext-available? (atom false))

(defn- extract-gdbus-string
  "Extract string value from GVariant output: ('json...',) -> json..."
  [raw]
  (when raw
    (when-let [[_ s] (re-find #"(?s)^\('(.*)',\s*\)$" (str/trim raw))]
      s)))

(defn- wc-call
  "Call a window-calls extension method via gdbus. Returns raw stdout."
  [method & args]
  (let [cmd (into ["gdbus" "call" "--session"
                    "-d" wc-dest "-o" wc-path
                    "-m" (str wc-iface "." method)]
                  (map str args))
        r (apply sh cmd)]
    (when (zero? (:exit r))
      (:out r))))

(defn check-extension!
  "Check if the window-calls GNOME Shell extension is reachable.
   Prints a warning on first failure. Returns true/false."
  []
  (if @ext-checked?
    @ext-available?
    (let [ok? (boolean (wc-call "List"))]
      (reset! ext-checked? true)
      (reset! ext-available? ok?)
      (when-not ok?
        (println)
        (println "WARNING: 'window-calls' GNOME Shell extension not detected.")
        (println "  focus-or-launch and focused-window will not work without it.")
        (println)
        (println "  Run:    bb setup:gnome")
        (println))
      ok?)))

(defn- list-windows
  "List all windows via window-calls. Returns seq of maps with
   :id, :wm_class, :focus, :title, etc. or nil."
  []
  (when (or @ext-available? (check-extension!))
    (when-let [raw (wc-call "List")]
      (try
        (json/parse-string (extract-gdbus-string raw) true)
        (catch Exception _ nil)))))

(defn- find-window-by-class
  "Find first window whose wm_class matches designator
   (case-insensitive substring)."
  [designator]
  (let [lower (str/lower-case designator)]
    (some (fn [w]
            (when (and (:wm_class w)
                       (str/includes? (str/lower-case (:wm_class w)) lower))
              w))
          (list-windows))))

(defn- focus-window!
  "Activate a window by its numeric id."
  [window-id]
  (wc-call "Focus" (long window-id)))

;; ----------------------------------------------------------------
;; Window actions via keyboard shortcuts
;; ----------------------------------------------------------------

(defmethod wm/action [:gnome :close-window] [& _]
  (bridge/emit-combo! [:left-alt] :f4))

(defmethod wm/action [:gnome :maximize] [& _]
  (bridge/emit-combo! [:left-meta] :up))

(defmethod wm/action [:gnome :workspace-next] [& _]
  (bridge/emit-combo! [:left-meta] :pagedown))

(defmethod wm/action [:gnome :workspace-prev] [& _]
  (bridge/emit-combo! [:left-meta] :pageup))

;; GNOME doesn't have directional focus (stacking WM),
;; but Super+Left/Right tiles windows which is the closest equivalent.
(defmethod wm/action [:gnome :focus-left] [& _]
  (bridge/emit-combo! [:left-meta] :left))

(defmethod wm/action [:gnome :focus-right] [& _]
  (bridge/emit-combo! [:left-meta] :right))

(defmethod wm/action [:gnome :focus-up] [& _]
  (bridge/emit-combo! [:left-meta] :up))

(defmethod wm/action [:gnome :focus-down] [& _]
  (bridge/emit-combo! [:left-meta] :down))

;; ----------------------------------------------------------------
;; focus-or-launch
;; ----------------------------------------------------------------

(defmethod wm/action [:gnome :focus-or-launch] [_ opts]
  (let [{:keys [class command]} opts
        command (or command class)
        designator (if (str/includes? class ":")
                     (second (str/split class #":" 2))
                     class)]
    (if-let [w (find-window-by-class designator)]
      (focus-window! (:id w))
      (sh "sh" "-c" command))))

;; ----------------------------------------------------------------
;; Info queries
;; ----------------------------------------------------------------

(defmethod wm/action [:gnome :focused-window] [& _]
  (some (fn [w]
          (when (:focus w)
            {:class (:wm_class w)
             :title (:title w)}))
        (list-windows)))

(defmethod wm/action [:gnome :resize-active] [_ _dx _dy & _]
  ;; Not available as a standard GNOME operation.
  nil)
