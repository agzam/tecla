(ns tecla.wm.hyprland
  "Hyprland backend for wm/action. Communicates via Hyprland's
   Unix domain socket IPC, not by shelling out to hyprctl."
  (:require [tecla.wm :as wm]
            [cheshire.core :as json]
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
  "Send a command to Hyprland via its IPC socket. Returns parsed JSON."
  [cmd]
  (when-let [sock-path (find-socket)]
    (try
      (let [cmd-str (cond->> cmd (keyword? cmd) name (string? cmd) identity
                             :always (str "j/"))]
        (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
          (.connect ch (UnixDomainSocketAddress/of sock-path))
          (let [write-buf (ByteBuffer/wrap (.getBytes cmd-str StandardCharsets/UTF_8))]
            (.write ch write-buf))
          (let [read-buf (ByteBuffer/allocate (int 1e7))
                n (.read ch read-buf)]
            (when (pos? n)
              (.flip read-buf)
              (let [raw (String. (.array read-buf) 0 n "UTF-8")]
                (try (json/parse-string raw true)
                     (catch Exception _ raw)))))))
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

(defmethod wm/action [:hyprland :focus-or-launch] [_ opts]
  (let [{:keys [class command]} opts
        command (or command class)
        designator (str/lower-case class)
        [key pattern] (if (str/includes? designator ":")
                        (let [[k v] (str/split designator #":" 2)]
                          [(keyword k) v])
                        [:class designator])
        regex (re-pattern (str "(?i)" pattern))
        matching (->> (hypr-cmd :clients)
                      (some #(when (and (contains? % key)
                                       (re-find regex
                                                 (str/lower-case
                                                  (str (get % key)))))
                               %)))]
    (if matching
      (dispatch (str "focuswindow address:" (:address matching)))
      (dispatch (str "exec " command)))))

(defmethod wm/action [:hyprland :toggle-floating] [& _] (dispatch "togglefloating"))
(defmethod wm/action [:hyprland :pin]             [& _] (dispatch "pin"))
(defmethod wm/action [:hyprland :minimize]         [& _]
  (dispatch "movetoworkspacesilent special:minimized"))
(defmethod wm/action [:hyprland :move-to-monitor]  [& _]
  (dispatch "movewindow mon:+1"))

(defmethod wm/action [:hyprland :send-shortcut] [_ mods key window]
  (dispatch (str "sendshortcut " mods "," key "," window)))

(defmethod wm/action [:hyprland :focus-window-by-address] [_ address]
  (dispatch (str "focuswindow address:" address)))

(defmethod wm/action [:hyprland :focused-window] [& _]
  (when-let [w (hypr-cmd "activewindow")]
    (select-keys w [:address :class :title])))

;; ----------------------------------------------------------------
;; Event listener (socket2 - async events)
;; ----------------------------------------------------------------

(defonce ^:private event-listener (atom nil))

(defn- find-event-socket
  "Find the Hyprland event socket (.socket2.sock)."
  []
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")
        sig (System/getenv "HYPRLAND_INSTANCE_SIGNATURE")
        hypr-dir (java.io.File. (str runtime "/hypr"))]
    (when (and runtime (.isDirectory hypr-dir))
      (let [candidates
            (if sig
              (cons (java.io.File. hypr-dir sig)
                    (remove #(= (.getName %) sig)
                            (sort-by #(- (.lastModified %))
                                     (.listFiles hypr-dir))))
              (sort-by #(- (.lastModified %)) (.listFiles hypr-dir)))]
        (some (fn [dir]
                (let [sock (java.io.File. dir ".socket2.sock")]
                  (when (.exists sock)
                    (.getPath sock))))
              candidates)))))

(defmethod wm/start-focus-listener! :hyprland [callback]
  (when-let [sock-path (find-event-socket)]
    (let [running (atom true)
          thread
          (doto (Thread.
                  (fn []
                    (while @running
                      (try
                        (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                          (.connect ch (UnixDomainSocketAddress/of sock-path))
                          (let [buf (ByteBuffer/allocate 4096)
                                sb (StringBuilder.)]
                            (while (and @running (pos? (.read ch buf)))
                              (.flip buf)
                              (let [raw (String. (.array buf) 0 (.limit buf) "UTF-8")]
                                (.append sb raw)
                                (loop []
                                  (let [s (.toString sb)
                                        idx (.indexOf s "\n")]
                                    (when (>= idx 0)
                                      (let [line (.substring s 0 idx)]
                                        (.delete sb 0 (inc idx))
                                        (when (str/starts-with? line "activewindow>>")
                                          (let [data (subs line (count "activewindow>>"))
                                                comma (.indexOf data ",")]
                                            (when (pos? comma)
                                              (callback (.substring data 0 comma)))))
                                        (recur))))))
                              (.clear buf))))
                        (catch Exception e
                          (when @running
                            (println "Hyprland event listener error:" (.getMessage e))
                            (Thread/sleep 2000))))))
                  "tecla-hyprland-events")
            (.setDaemon true)
            (.start))]
      (reset! event-listener {:running running :thread thread})
      (println "Hyprland event listener started"))))

(defmethod wm/stop-focus-listener! :hyprland []
  (when-let [{:keys [running]} @event-listener]
    (reset! running false)
    (reset! event-listener nil)
    (println "Hyprland event listener stopped")))
