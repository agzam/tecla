(ns tecla.wm.gnome
  "GNOME backend for wm/action. GNOME 41+ locks down D-Bus shell
   access, so we use keyboard shortcuts via bridge/emit! for window
   management and gtk-launch / D-Bus activation for focus-or-launch."
  (:require [tecla.wm :as wm]
            [tecla.bridge :as bridge]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; ----------------------------------------------------------------
;; Desktop file lookup (for focus-or-launch)
;; ----------------------------------------------------------------

(def ^:private desktop-dirs
  (delay
    (let [home (System/getenv "HOME")
          data-home (or (System/getenv "XDG_DATA_HOME")
                        (str home "/.local/share"))]
      (filterv #(.isDirectory (io/file %))
               [(str data-home "/applications")
                "/usr/share/applications"
                "/usr/local/share/applications"
                (str home "/.local/share/flatpak/exports/share/applications")
                "/var/lib/flatpak/exports/share/applications"]))))

(defn- find-desktop-file
  "Find a .desktop file matching designator. Tries exact match first,
   then case-insensitive substring on filename."
  [designator]
  (let [lower (str/lower-case designator)]
    (some (fn [dir]
            (let [files (.listFiles (io/file dir))]
              (or
                ;; Exact match: designator.desktop
                (some #(when (= (.getName %) (str designator ".desktop"))
                         (.getPath %)) files)
                ;; Case-insensitive substring
                (some #(let [n (str/lower-case (.getName %))]
                         (when (and (str/ends-with? n ".desktop")
                                    (str/includes? n lower))
                           (.getPath %))) files))))
          @desktop-dirs)))

(defn- desktop-basename
  "Extract basename from a .desktop file path for gtk-launch."
  [path]
  (let [name (.getName (io/file path))]
    (if (str/ends-with? name ".desktop")
      (subs name 0 (- (count name) 8))
      name)))

;; ----------------------------------------------------------------
;; D-Bus activation (focus existing or launch)
;; ----------------------------------------------------------------

(defn- dbus-activate
  "Try to activate an app via org.freedesktop.Application.Activate.
   Returns true on success."
  [bus-name obj-path]
  (try
    (let [r (sh "gdbus" "call" "--session"
                "-d" bus-name "-o" obj-path
                "-m" "org.freedesktop.Application.Activate"
                "@a{sv}" "{}")]
      (zero? (:exit r)))
    (catch Throwable _ false)))

(defn- try-dbus-activate
  "Try common D-Bus name patterns for a designator."
  [designator]
  (let [;; If designator has dots, treat as D-Bus name directly
        names (if (str/includes? designator ".")
                [designator]
                [])
        ;; Also try to extract app-id from .desktop file
        desktop (find-desktop-file designator)
        names (if desktop
                (let [base (desktop-basename desktop)]
                  (distinct (conj names base)))
                names)]
    (some (fn [name]
            (let [obj-path (str "/" (str/replace name "." "/"))]
              (when (dbus-activate name obj-path)
                true)))
          names)))

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

(defmethod wm/action [:gnome :focus-or-launch] [_ designator & [app-cmd]]
  (let [app-cmd (or app-cmd designator)
        ;; Strip key:pattern prefix if present (Hyprland syntax)
        designator (if (str/includes? designator ":")
                     (second (str/split designator #":" 2))
                     designator)]
    ;; Try D-Bus activation first (focuses existing window)
    (when-not (try-dbus-activate designator)
      ;; Try gtk-launch with .desktop file
      (let [desktop (find-desktop-file designator)
            launched (when desktop
                       (zero? (:exit (sh "gtk-launch"
                                         (desktop-basename desktop)))))]
        ;; Fall back to running the command directly
        (when-not launched
          (sh "sh" "-c" app-cmd))))))

;; ----------------------------------------------------------------
;; Info queries
;; ----------------------------------------------------------------

(defmethod wm/action [:gnome :focused-window] [& _]
  ;; GNOME 41+ doesn't expose window info via D-Bus.
  ;; Return nil - callers should handle gracefully.
  nil)

(defmethod wm/action [:gnome :resize-active] [_ _dx _dy & _]
  ;; Not a standard GNOME operation.
  nil)
