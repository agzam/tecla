(ns tecla.media
  "Music app remote control. Sends keystrokes to a specific
   app window (e.g. YouTube Music PWA) via compositor IPC."
  (:require [tecla.wm :as wm]))

(def music-app
  "The music app window class and launch command."
  {:class "brave-cinhimbnkkaeohfgghhklpknlkffjgod-Default"
   :command "brave --app-id=cinhimbnkkaeohfgghhklpknlkffjgod --ozone-platform=wayland"})

(defn music-app-press-key
  "Focus the music app, enter vimium insert mode, send `key` with
   optional `mods`, then return focus to the previously active window.
   Pass :remain? true to stay on the music app after sending the key.

   Examples:
     (music-app-press-key :semicolon)           ;; play/pause
     (music-app-press-key :n :mods \"Shift\")     ;; next song
     (music-app-press-key :slash :remain? true)  ;; search"
  [key & {:keys [mods remain?] :or {mods ""}}]
  (let [{:keys [address]} (wm/focused-window)]
    (wm/focus-or-launch music-app)
    (Thread/sleep 150)
    ;; exit any vimium mode, then enter insert mode
    (wm/send-shortcut "" "escape" "activewindow")
    (wm/send-shortcut "" "i" "activewindow")
    (Thread/sleep 100)
    (wm/send-shortcut mods (name key) "activewindow")
    (when (and (not remain?) address)
      (Thread/sleep 50)
      (wm/focus-window-by-address address))))
