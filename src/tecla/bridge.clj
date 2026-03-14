(ns tecla.bridge
  "Socket client for tecla-bridge. Connects to the C bridge,
   receives raw key events, sends emit commands.

   ALL keyboard logic lives here and in tecla.fsm - the bridge
   is just an I/O pipe."
  (:require [tecla.keys :as keys]
            [clojure.string :as str]
            [babashka.process :as proc])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(def default-socket-path
  (str (or (System/getenv "XDG_RUNTIME_DIR") "/tmp") "/tecla-bridge.sock"))

(defonce state
  (atom {:channel nil
         :running false
         :on-key nil       ;; callback: (fn [code value]) for every key event
         :devnode nil}))

;; ----------------------------------------------------------------
;; Low-level I/O
;; ----------------------------------------------------------------

(defn- send-line [ch line]
  (.write ch (ByteBuffer/wrap (.getBytes (str line "\n") StandardCharsets/UTF_8))))

(defn- read-chunk [ch]
  (let [buf (ByteBuffer/allocate 4096)
        n (.read ch buf)]
    (when (pos? n)
      (.flip buf)
      (let [bytes (byte-array n)]
        (.get buf bytes)
        (String. bytes StandardCharsets/UTF_8)))))

;; ----------------------------------------------------------------
;; Public API
;; ----------------------------------------------------------------

(defn emit!
  "Emit a key event on the virtual keyboard."
  [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when (and code (some? (:channel @state)))
      (send-line (:channel @state) (format "EM %d %d" code value)))))

(defn emit-key-tap!
  "Press and release a key."
  [code]
  (emit! code 1)
  (emit! code 0))

(defn emit-combo!
  "Emit a modifier+key combo (press mods, press key, release key, release mods)."
  [mod-codes key-code]
  (doseq [m mod-codes] (emit! m 1))
  (emit! key-code 1)
  (emit! key-code 0)
  (doseq [m (reverse mod-codes)] (emit! m 0)))

;; ----------------------------------------------------------------
;; Reader thread
;; ----------------------------------------------------------------

(defn- parse-line [line on-key]
  (let [parts (str/split (str/trim line) #"\s+")]
    (case (first parts)
      "EV" (let [code (parse-long (nth parts 1))
                 value (parse-long (nth parts 2))]
             (when on-key (on-key code value)))
      "HELLO" (println "Bridge connected")
      "DEVNODE" (do (swap! state assoc :devnode (second parts))
                    (println "Bridge virtual device:" (second parts)))
      nil)))

(defn- reader-loop [ch on-key]
  (let [partial (StringBuilder.)]
    (while (:running @state)
      (try
        (let [data (read-chunk ch)]
          (if (nil? data)
            (Thread/sleep 5)
            (do
              (.append partial data)
              (let [s (.toString partial)
                    lines (str/split s #"\n" -1)]
                (.setLength partial 0)
                (.append partial (last lines))
                (doseq [line (butlast lines)]
                  (when-not (str/blank? line)
                    (parse-line line on-key)))))))
        (catch Exception e
          (when (:running @state)
            (println "Bridge reader error:" (.getMessage e))
            (println "  " (str e))
            (Thread/sleep 100)))))))

;; ----------------------------------------------------------------
;; Connection management
;; ----------------------------------------------------------------

(defn connect!
  "Connect to the bridge. on-key is called for every key event: (fn [code value])."
  [on-key & {:keys [socket-path] :or {socket-path default-socket-path}}]
  (let [addr (UnixDomainSocketAddress/of socket-path)
        ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch addr)
    (.configureBlocking ch false)
    (swap! state assoc :channel ch :running true :on-key on-key)
    (let [t (Thread. #(reader-loop ch on-key) "tecla-bridge-reader")]
      (.setDaemon t true)
      (.start t))
    (println "Connected to bridge")))

(defn disconnect! []
  (swap! state assoc :running false)
  (when-let [ch (:channel @state)]
    (try (.close ch) (catch Exception _)))
  (swap! state assoc :channel nil))

;; ----------------------------------------------------------------
;; Bridge process management
;; ----------------------------------------------------------------

(def bridge-bin "bridge/tecla-bridge")

(defn- find-bridge-binary []
  (let [candidates [(str (System/getProperty "user.dir") "/" bridge-bin)
                    (str (System/getenv "HOME") "/.local/bin/tecla-bridge")
                    "/usr/local/bin/tecla-bridge"]]
    (first (filter #(.exists (java.io.File. %)) candidates))))

(defn- bridge-running? [socket-path]
  (try
    (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
      (.connect ch (UnixDomainSocketAddress/of socket-path))
      (.close ch) true)
    (catch Exception _ false)))

(defn start-bridge!
  [{:keys [socket-path]
    :or {socket-path default-socket-path}}]
  (if (bridge-running? socket-path)
    (println "Bridge already running")
    (let [bin (find-bridge-binary)]
      (when-not bin (throw (ex-info "Cannot find tecla-bridge binary" {})))
      (let [args [bin "--socket" socket-path]]
        (println "Starting bridge:" (str/join " " args))
        (apply proc/process {:out :inherit :err :inherit :in :inherit} args)
        (loop [n 0]
          (when (< n 20)
            (if (.exists (java.io.File. socket-path))
              (Thread/sleep 200)
              (do (Thread/sleep 100) (recur (inc n))))))))))
