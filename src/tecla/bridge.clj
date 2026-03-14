(ns tecla.bridge
  "Socket client for tecla-bridge. Connects to the C bridge process,
   sends configuration commands, and receives key events."
  (:require [tecla.keys :as keys]
            [clojure.string :as str]
            [babashka.process :as proc])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels SocketChannel]
           [java.nio ByteBuffer]
           [java.nio.charset StandardCharsets]))

(def default-socket-path
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")]
    (str (or runtime "/tmp") "/tecla-bridge.sock")))

(defonce state
  (atom {:channel nil
         :device nil
         :event-handler nil
         :reader-thread nil
         :running false}))

;; ----------------------------------------------------------------
;; Low-level socket I/O
;; ----------------------------------------------------------------

(defn- send-line [ch line]
  (let [buf (ByteBuffer/wrap (.getBytes (str line "\n") StandardCharsets/UTF_8))]
    (.write ch buf)))

(defn- read-chunk [ch]
  (let [buf (ByteBuffer/allocate 4096)
        n (.read ch buf)]
    (when (pos? n)
      (.flip buf)
      (let [bytes (byte-array n)]
        (.get buf bytes)
        (String. bytes StandardCharsets/UTF_8)))))

;; ----------------------------------------------------------------
;; Event reader thread
;; ----------------------------------------------------------------

(defn- parse-event
  "Parse a line from the bridge into an event map."
  [line]
  (let [parts (str/split (str/trim line) #"\s+")]
    (case (first parts)
      "HELLO"   {:type :hello :device (str/join " " (rest parts))}
      "TRIGGER" {:type :trigger}
      "KEY"     (let [code (parse-long (nth parts 1 "0"))
                      value (parse-long (nth parts 2 "0"))]
                  {:type :key
                   :code code
                   :key (get keys/code->key code :unknown)
                   :value value
                   :press? (= value 1)})
      {:type :unknown :raw line})))

(defn- reader-loop [ch handler]
  (let [partial (StringBuilder.)]
    (try
      (while (:running @state)
        (let [data (read-chunk ch)]
          (if (nil? data)
            ;; No data - brief sleep to avoid busy-wait
            (Thread/sleep 10)
            (do
              (.append partial data)
              (let [s (.toString partial)
                    lines (str/split s #"\n" -1)]
                ;; Last element is incomplete line (or empty if ended with \n)
                (.setLength partial 0)
                (.append partial (last lines))
                (doseq [line (butlast lines)]
                  (when-not (str/blank? line)
                    (handler (parse-event line)))))))))
      (catch Exception e
        (when (:running @state)
          (println "Bridge reader error:" (.getMessage e)))))))

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
    (let [addr (UnixDomainSocketAddress/of socket-path)
          ch (SocketChannel/open StandardProtocolFamily/UNIX)]
      (.connect ch addr)
      (.close ch)
      true)
    (catch Exception _ false)))

(defn start-bridge!
  "Start the bridge process if not already running."
  [{:keys [device socket-path debug?]
    :or {socket-path default-socket-path
         debug? false}}]
  (if (bridge-running? socket-path)
    (println "Bridge already running on" socket-path)
    (let [bin (find-bridge-binary)]
      (when-not bin
        (throw (ex-info "Cannot find tecla-bridge binary" {})))
      (let [args (cond-> [bin "--socket" socket-path]
                   debug? (conj "--debug")
                   device (conj device))]
        (println "Starting bridge:" (str/join " " args))
        (proc/process {:out :inherit :err :inherit :in :inherit}
                      args)
        ;; Wait for socket to appear
        (loop [attempts 0]
          (when (< attempts 20)
            (if (.exists (java.io.File. socket-path))
              (Thread/sleep 200)  ;; extra settle time
              (do (Thread/sleep 100)
                  (recur (inc attempts))))))))))

;; ----------------------------------------------------------------
;; Public API
;; ----------------------------------------------------------------

(defn connect!
  "Connect to the bridge socket. Starts reader thread with the given handler."
  ([handler] (connect! handler {}))
  ([handler {:keys [socket-path]
             :or {socket-path default-socket-path}}]
   (let [addr (UnixDomainSocketAddress/of socket-path)
         ch (SocketChannel/open StandardProtocolFamily/UNIX)]
     (.connect ch addr)
     (.configureBlocking ch false)
     (swap! state assoc
            :channel ch
            :event-handler handler
            :running true)
     (let [t (Thread. #(reader-loop ch handler) "tecla-bridge-reader")]
       (.setDaemon t true)
       (.start t)
       (swap! state assoc :reader-thread t))
     (println "Connected to bridge"))))

(defn disconnect! []
  (swap! state assoc :running false)
  (when-let [ch (:channel @state)]
    (try (.close ch) (catch Exception _)))
  (swap! state assoc :channel nil :reader-thread nil))

(defn send!
  "Send a raw command to the bridge."
  [cmd]
  (when-let [ch (:channel @state)]
    (send-line ch cmd)))

(defn set-trigger!
  "Configure the modal trigger key combo."
  [mods key]
  (let [mod-bits (keys/mods->bits mods)
        key-code (get keys/key->code key)]
    (when-not key-code
      (throw (ex-info (str "Unknown key: " key) {:key key})))
    (send! (format "SET_TRIGGER %d %d" mod-bits key-code))))

(defn add-remap!
  "Add a key remap. from/to are each [mods key], e.g. [:super :c] [:ctrl :c]."
  [[from-mods from-key] [to-mods to-key]]
  (let [fm (keys/mods->bits (if (keyword? from-mods) [from-mods] from-mods))
        fk (get keys/key->code from-key)
        tm (keys/mods->bits (if (keyword? to-mods) [to-mods] to-mods))
        tk (get keys/key->code to-key)]
    (when-not (and fk tk)
      (throw (ex-info "Unknown key in remap"
                      {:from-key from-key :to-key to-key})))
    (send! (format "ADD_REMAP %d %d %d %d" fm fk tm tk))))

(defn clear-remaps! []
  (send! "CLEAR_REMAPS"))

(defn emit-key!
  "Emit a key event on the virtual keyboard."
  [key value]
  (let [code (if (keyword? key) (get keys/key->code key) key)]
    (send! (format "EMIT %d %d" code value))))

(defn exit-modal!
  "Tell the bridge to exit modal mode and return to passthrough."
  []
  (send! "PASSTHROUGH"))
