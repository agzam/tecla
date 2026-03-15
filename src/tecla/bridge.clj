(ns tecla.bridge
  "Socket client for tecla-bridge."
  (:require [tecla.keys :as keys]
            [clojure.string :as str]  ;; used in start-bridge!
            [babashka.process :as proc])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

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
  (try
    (let [buf (ByteBuffer/wrap (.getBytes (str line "\n") StandardCharsets/UTF_8))]
      (while (.hasRemaining buf)
        (.write ch buf)))
    (catch java.io.IOException _
      (println "Bridge connection lost")
      (swap! state assoc :channel nil))))

;; ----------------------------------------------------------------
;; Public API
;; ----------------------------------------------------------------

(defn emit! [code value]
  (let [code (if (keyword? code) (keys/key->code code) (int code))]
    (when (and code (:channel @state))
      (send-line (:channel @state) (str "EM " code " " value)))))

(defn emit-key-tap! [code]
  (emit! code 1) (emit! code 0))

(defn emit-combo! [mod-codes key-code]
  (doseq [m mod-codes] (emit! m 1))
  (emit! key-code 1) (emit! key-code 0)
  (doseq [m (reverse mod-codes)] (emit! m 0)))

;; ----------------------------------------------------------------
;; Fast-path control - tell bridge which keys to intercept
;; ----------------------------------------------------------------

(defn send-binding!
  "Register a keymap binding with the bridge for fast-path routing."
  [mod-mask keycode]
  (when-let [ch (:channel @state)]
    (send-line ch (str "BIND " mod-mask " " keycode))))

(defn send-clear!
  "Clear all registered bindings in the bridge."
  []
  (when-let [ch (:channel @state)]
    (send-line ch "CLEAR")))

(defn set-passthrough!
  "Activate fast-path: only bound keys routed to client, rest pass through."
  []
  (when-let [ch (:channel @state)]
    (send-line ch "PASSTHROUGH")))

(defn set-intercept!
  "Intercept all keys (for modal mode)."
  []
  (when-let [ch (:channel @state)]
    (send-line ch "INTERCEPT")))

;; ----------------------------------------------------------------
;; Reader thread
;; ----------------------------------------------------------------

(defn- parse-line
  "Parse a bridge protocol line. EV events are placed on the queue
   for the processor thread. HELLO/DEVNODE are handled inline."
  [^String line ^LinkedBlockingQueue queue]
  (cond
    (.startsWith line "EV ")
    (let [sp1 (.indexOf line (int \space) 3)]
      (when (pos? sp1)
        (let [code  (parse-long (.substring line 3 sp1))
              value (parse-long (.substring line (inc sp1)))]
          (when (and code value)
            (.offer queue (long-array [code value]))))))

    (.startsWith line "HELLO")
    (println "Bridge connected")

    (.startsWith line "DEVNODE ")
    (let [path (.substring line 8)]
      (swap! state assoc :devnode path)
      (println "Virtual device:" path))))

(defn- reader-loop
  "Blocking reader. Reads from the channel and enqueues parsed EV events.
   Exits when the channel is closed or :running becomes false."
  [ch ^LinkedBlockingQueue queue]
  (let [partial (StringBuilder.)
        buf     (ByteBuffer/allocate 4096)]
    (while (:running @state)
      (try
        (.clear buf)
        (let [n (.read ch buf)]
          (if (or (nil? n) (not (pos? n)))
            (swap! state assoc :running false)
            (do
              (.flip buf)
              (let [bytes (byte-array n)]
                (.get buf bytes)
                (.append partial (String. bytes StandardCharsets/UTF_8)))
              (loop []
                (let [s   (.toString partial)
                      idx (.indexOf s "\n")]
                  (when-not (neg? idx)
                    (let [line (.substring s 0 idx)]
                      (.delete partial 0 (inc idx))
                      (when (pos? (.length line))
                        (parse-line line queue))
                      (recur))))))))
        (catch Exception e
          (when (:running @state)
            (println "Bridge reader error:" (.getMessage e))))))))

(defn- event-processor
  "Drains the event queue, calling on-key for each event.
   Wraps the callback in error handling to prevent stuck keys."
  [^LinkedBlockingQueue queue on-key]
  (while (:running @state)
    (try
      (when-let [^longs ev (.poll queue 50 TimeUnit/MILLISECONDS)]
        (let [code  (aget ev 0)
              value (aget ev 1)]
          (try
            (on-key code value)
            (catch Exception e
              (println "Key handler error:" code value (.getMessage e))
              ;; If release processing failed, emit safety release
              ;; to prevent the key from being stuck down.
              (when (zero? value)
                (try (emit! code 0) (catch Exception _)))))))
      (catch InterruptedException _
        nil))))

;; ----------------------------------------------------------------
;; Connection
;; ----------------------------------------------------------------

(defn connect!
  "Connect to the bridge with retries.
   Starts two daemon threads: a blocking reader that enqueues events
   and a processor that drains the queue and calls on-key."
  [on-key & {:keys [socket-path retries]
             :or {socket-path default-socket-path retries 30}}]
  (loop [attempt 0]
    (let [ch (try
               (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                 (.connect ch (UnixDomainSocketAddress/of socket-path))
                 ;; Blocking mode: reader blocks on .read() until data
                 ;; arrives. No spin-loop, no sleep, minimal latency.
                 (.configureBlocking ch true)
                 ch)
               (catch Exception e
                 (when (>= attempt retries)
                   (throw (ex-info (str "Cannot connect to bridge: "
                                        (.getMessage e)) {})))
                 nil))]
      (if ch
        (let [queue (LinkedBlockingQueue. 512)]
          (swap! state assoc :channel ch :running true :on-key on-key)
          (doto (Thread. #(reader-loop ch queue) "tecla-bridge-reader")
            (.setDaemon true)
            (.start))
          (doto (Thread. #(event-processor queue on-key) "tecla-event-processor")
            (.setDaemon true)
            (.start))
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
          ;; Check if bridge exited early
          (when (.isAlive ^java.lang.Process (:proc p))
            (let [ready (try
                          (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                            (.connect ch (UnixDomainSocketAddress/of socket-path))
                            (.close ch)
                            true)
                          (catch Exception _ false))]
              (when-not ready
                (Thread/sleep 200)
                (recur (inc n))))))))))
