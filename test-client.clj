#!/usr/bin/env bb

;; Minimal test client for tecla-bridge.
;; Connects via Unix socket, configures a remap and trigger,
;; prints events received from the bridge.
;;
;; Usage: bb test-client.clj

(import [java.net StandardProtocolFamily UnixDomainSocketAddress]
        [java.nio.channels SocketChannel]
        [java.nio ByteBuffer]
        [java.nio.charset StandardCharsets])

;; Linux input keycodes (from linux/input-event-codes.h)
(def KEY_M 50)
(def KEY_SPACE 57)

;; Modifier bitmask (must match bridge protocol)
(def MOD_CTRL  1)
(def MOD_SHIFT 2)
(def MOD_ALT   4)
(def MOD_META  8)

(defn socket-path []
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")]
    (if runtime
      (str runtime "/tecla-bridge.sock")
      "/tmp/tecla-bridge.sock")))

(defn connect [path]
  (println "Connecting to" path)
  (let [addr (UnixDomainSocketAddress/of path)
        ch   (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch addr)
    (.configureBlocking ch true)
    (println "Connected!")
    ch))

(defn send-cmd [ch cmd]
  (println ">>" cmd)
  (let [buf (ByteBuffer/wrap (.getBytes (str cmd "\n")
                                        StandardCharsets/UTF_8))]
    (.write ch buf)))

(defn read-lines [ch]
  (let [buf (ByteBuffer/allocate 1024)
        n   (.read ch buf)]
    (when (pos? n)
      (.flip buf)
      (let [bytes (byte-array n)]
        (.get buf bytes)
        (str/split (String. bytes StandardCharsets/UTF_8) #"\n")))))

(defn run []
  (let [ch (connect (socket-path))]
    (try
      ;; Read HELLO
      (when-let [lines (read-lines ch)]
        (doseq [l lines]
          (println "<<" l)))

      ;; Configure: Super+M -> Ctrl+M
      (send-cmd ch (format "ADD_REMAP %d %d %d %d"
                           MOD_META KEY_M MOD_CTRL KEY_M))

      ;; Configure trigger: Super+Space
      (send-cmd ch (format "SET_TRIGGER %d %d" MOD_META KEY_SPACE))

      (println)
      (println "Listening for events. Press Super+Space to trigger modal.")
      (println "Press Ctrl+C to quit.")
      (println)

      ;; Event loop
      (loop []
        (when-let [lines (read-lines ch)]
          (doseq [line lines]
            (println "<<" line)
            (when (= line "TRIGGER")
              (println "*** MODAL ACTIVATED ***")
              (println "*** Press any key - it will be sent as KEY event ***")
              (println "*** Sending PASSTHROUGH in 5 seconds ***")
              (future
                (Thread/sleep 5000)
                (println "*** Exiting modal ***")
                (send-cmd ch "PASSTHROUGH")))))
        (recur))

      (catch Exception e
        (println "Error:" (.getMessage e)))
      (finally
        (.close ch)))))

(run)
