(ns tecla.bridge
  "Socket client for tecla-bridge."
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
         :on-key nil
         :devnode nil
         :bridge-proc nil}))

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

(defn emit! [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when (and code (:channel @state))
      (send-line (:channel @state) (format "EM %d %d" code value)))))

(defn emit-key-tap! [code]
  (emit! code 1) (emit! code 0))

(defn emit-combo! [mod-codes key-code]
  (doseq [m mod-codes] (emit! m 1))
  (emit! key-code 1) (emit! key-code 0)
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
                    (println "Virtual device:" (second parts)))
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
            (Thread/sleep 100)))))))

;; ----------------------------------------------------------------
;; Connection
;; ----------------------------------------------------------------

(defn connect!
  "Connect to the bridge with retries."
  [on-key & {:keys [socket-path retries]
             :or {socket-path default-socket-path retries 30}}]
  (loop [attempt 0]
    (let [ch (try
               (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                 (.connect ch (UnixDomainSocketAddress/of socket-path))
                 (.configureBlocking ch false)
                 ch)
               (catch Exception e
                 (when (>= attempt retries)
                   (throw (ex-info (str "Cannot connect to bridge: "
                                        (.getMessage e)) {})))
                 nil))]
      (if ch
        (do
          (swap! state assoc :channel ch :running true :on-key on-key)
          (let [t (Thread. #(reader-loop ch on-key) "tecla-bridge-reader")]
            (.setDaemon t true)
            (.start t))
          (println "Connected to bridge"))
        (do (Thread/sleep 200) (recur (inc attempt)))))))

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
  (first (filter #(.exists (java.io.File. %))
                 [(str (System/getProperty "user.dir") "/" bridge-bin)
                  (str (System/getenv "HOME") "/.local/bin/tecla-bridge")
                  "/usr/local/bin/tecla-bridge"])))

(defn stop-bridge! []
  (when-let [p (:bridge-proc @state)]
    (let [proc (:proc p)]
      (try (.destroyForcibly proc) (catch Exception _))
      (try (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS)
           (catch Exception _)))
    (swap! state assoc :bridge-proc nil)))

(defn start-bridge!
  [{:keys [socket-path] :or {socket-path default-socket-path}}]
  (let [bin (find-bridge-binary)]
    (when-not bin (throw (ex-info "Cannot find tecla-bridge binary" {})))

    ;; Kill any leftover bridge and clean stale socket
    (stop-bridge!)
    (let [f (java.io.File. socket-path)]
      (when (.exists f) (.delete f)))

    (let [args [bin "--socket" socket-path]
          p (apply proc/process {:out :inherit :err :inherit :in :inherit} args)]
      (println "Starting bridge:" (str/join " " args))
      (swap! state assoc :bridge-proc p)

      ;; Wait for bridge to be ready (accepting connections)
      (loop [n 0]
        (when (< n 30)
          (let [ready (try
                        (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                          (.connect ch (UnixDomainSocketAddress/of socket-path))
                          (.close ch)
                          true)
                        (catch Exception _ false))]
            (when-not ready
              (Thread/sleep 200)
              (recur (inc n)))))))))
