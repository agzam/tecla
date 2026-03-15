(ns tecla.wm.hyprland
  "Hyprland backend for wm/action. Communicates via Hyprland's
   Unix domain socket IPC, not by shelling out to hyprctl."
  (:require [tecla.wm :as wm]
            [clojure.string :as str])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

;; ----------------------------------------------------------------
;; Socket discovery (borrowed from ~/.config/hypr/hypr.clj pattern)
;; ----------------------------------------------------------------

(defn- find-socket
  "Find the Hyprland command socket. Tries HYPRLAND_INSTANCE_SIGNATURE
   first, then scans XDG_RUNTIME_DIR/hypr/."
  []
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")
        sig (System/getenv "HYPRLAND_INSTANCE_SIGNATURE")
        hypr-dir (java.io.File. (str runtime "/hypr"))]
    (when (and runtime (.isDirectory hypr-dir))
      (let [candidates
            (if sig
              ;; Known instance first, then others
              (cons (java.io.File. hypr-dir sig)
                    (remove #(= (.getName %) sig)
                            (sort-by #(- (.lastModified %))
                                     (.listFiles hypr-dir))))
              ;; Scan all, most recent first
              (sort-by #(- (.lastModified %)) (.listFiles hypr-dir)))]
        (some (fn [dir]
                (let [sock (java.io.File. dir ".socket.sock")]
                  (when (.exists sock)
                    (.getPath sock))))
              candidates)))))

(defn- hypr-cmd
  "Send a command to Hyprland via its IPC socket. Returns response string."
  [cmd]
  (when-let [sock-path (find-socket)]
    (try
      (let [addr (UnixDomainSocketAddress/of sock-path)
            ch (SocketChannel/open StandardProtocolFamily/UNIX)]
        (.connect ch addr)
        (.configureBlocking ch true)
        ;; Prepend j/ for JSON output
        (let [cmd-str (str "j/" cmd)
              write-buf (ByteBuffer/wrap (.getBytes cmd-str StandardCharsets/UTF_8))]
          (.write ch write-buf))
        ;; Read response
        (let [read-buf (ByteBuffer/allocate 65536)
              n (.read ch read-buf)]
          (.close ch)
          (when (pos? n)
            (.flip read-buf)
            (let [bytes (byte-array n)]
              (.get read-buf bytes)
              (String. bytes StandardCharsets/UTF_8)))))
      (catch Exception e
        (println "hypr-cmd error:" (.getMessage e))
        nil))))

(defn- dispatch [cmd]
  (hypr-cmd (str "dispatch " cmd)))

;; ----------------------------------------------------------------
;; Register methods
;; ----------------------------------------------------------------

(defmethod wm/action [:hyprland :focus-left]  [& _] (dispatch "movefocus l"))
(defmethod wm/action [:hyprland :focus-right] [& _] (dispatch "movefocus r"))
(defmethod wm/action [:hyprland :focus-up]    [& _] (dispatch "movefocus u"))
(defmethod wm/action [:hyprland :focus-down]  [& _] (dispatch "movefocus d"))

(defmethod wm/action [:hyprland :move-left]   [& _] (dispatch "movewindow l"))
(defmethod wm/action [:hyprland :move-right]  [& _] (dispatch "movewindow r"))
(defmethod wm/action [:hyprland :move-up]     [& _] (dispatch "movewindow u"))
(defmethod wm/action [:hyprland :move-down]   [& _] (dispatch "movewindow d"))

(defmethod wm/action [:hyprland :maximize]      [& _] (dispatch "fullscreen 1"))
(defmethod wm/action [:hyprland :close-window]  [& _] (dispatch "killactive"))

(defmethod wm/action [:hyprland :workspace-next] [& _] (dispatch "workspace e+1"))
(defmethod wm/action [:hyprland :workspace-prev] [& _] (dispatch "workspace e-1"))

(defmethod wm/action [:hyprland :resize-active] [_ dx dy & _]
  (dispatch (str "resizeactive " dx " " dy)))

(defmethod wm/action [:hyprland :focus-or-launch] [_ designator & [app-cmd]]
  (let [app-cmd (or app-cmd designator)
        ;; Parse key:pattern syntax
        [match-key match-pattern]
        (if (str/includes? designator ":")
          (str/split designator #":" 2)
          ["class" designator])
        match-key (keyword match-key)
        clients (hypr-cmd "clients")
        ;; Find matching client in JSON array
        found (when clients
                (re-find
                  (re-pattern
                    (str "\"address\":\\s*\"(0x[^\"]+)\"[^}]*\""
                         (name match-key) "\":\\s*\""
                         match-pattern "\""))
                  clients))]
    (if found
      (dispatch (str "focuswindow address:" (second found)))
      (dispatch (str "exec " app-cmd)))))

(defmethod wm/action [:hyprland :focused-window] [& _]
  (when-let [raw (hypr-cmd "activewindow")]
    ;; Quick extraction without a JSON parser
    (let [class-match (re-find #"\"class\":\s*\"([^\"]+)\"" raw)
          title-match (re-find #"\"title\":\s*\"([^\"]*)\""  raw)]
      {:class (second class-match)
       :title (second title-match)})))
