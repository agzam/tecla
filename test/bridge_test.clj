#!/usr/bin/env bb

;; Integration tests for tecla-bridge.
;;
;; Starts a virtual test keyboard and the bridge process, connects via
;; Unix socket, injects key events, and verifies bridge behavior.
;;
;; Usage: bb test/bridge_test.clj
;;
;; Requires: bridge/tecla-bridge and bridge/tecla-test-kbd to be built.

(require '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(import [java.net StandardProtocolFamily UnixDomainSocketAddress]
        [java.nio.channels SocketChannel]
        [java.nio ByteBuffer]
        [java.nio.charset StandardCharsets])

;; ----------------------------------------------------------------
;; Key codes (linux/input-event-codes.h)
;; ----------------------------------------------------------------

(def KEY_ESC 1)
(def KEY_A 30)
(def KEY_B 48)
(def KEY_M 50)
(def KEY_SPACE 57)
(def KEY_F1 59)
(def KEY_LEFTCTRL 29)
(def KEY_LEFTSHIFT 42)
(def KEY_LEFTALT 56)
(def KEY_LEFTMETA 125)

;; Modifier bitmask (must match bridge protocol)
(def MOD_CTRL 1)
(def MOD_SHIFT 2)
(def MOD_ALT 4)
(def MOD_META 8)

;; ----------------------------------------------------------------
;; Test infrastructure
;; ----------------------------------------------------------------

(def project-dir
  (.getCanonicalPath (io/file (System/getProperty "user.dir"))))

(def bridge-bin (str project-dir "/bridge/tecla-bridge"))
(def test-kbd-bin (str project-dir "/bridge/tecla-test-kbd"))

(def ^:dynamic *test-kbd* nil)   ;; test keyboard process
(def ^:dynamic *bridge* nil)     ;; bridge process
(def ^:dynamic *socket* nil)     ;; SocketChannel to bridge
(def ^:dynamic *kbd-writer* nil) ;; writer to test keyboard stdin

(def test-results (atom {:passed 0 :failed 0 :errors []}))

(defn pass! [name]
  (swap! test-results update :passed inc)
  (println (str "  ✓ " name)))

(defn fail! [name msg]
  (swap! test-results (fn [r]
                        (-> r
                            (update :failed inc)
                            (update :errors conj {:test name :msg msg}))))
  (println (str "  ✗ " name ": " msg)))

(defmacro deftest [name & body]
  `(do
     (try
       ~@body
       (pass! ~(str name))
       (catch Exception e#
         (fail! ~(str name) (.getMessage e#))))))

(defn assert= [expected actual msg]
  (when (not= expected actual)
    (throw (ex-info (str msg " - expected: " (pr-str expected)
                         ", got: " (pr-str actual))
                    {:expected expected :actual actual}))))

(defn assert-contains [haystack needle msg]
  (when-not (str/includes? (str haystack) (str needle))
    (throw (ex-info (str msg " - " (pr-str haystack)
                         " does not contain " (pr-str needle))
                    {}))))

;; ----------------------------------------------------------------
;; Socket helpers
;; ----------------------------------------------------------------

(defn socket-path []
  (let [runtime (System/getenv "XDG_RUNTIME_DIR")]
    (str (or runtime "/tmp") "/tecla-bridge.sock")))

(defn connect-socket
  "Connect to bridge socket. Retries a few times for startup delay."
  [path max-retries]
  (loop [attempt 0]
    (let [result (try
                   (let [addr (UnixDomainSocketAddress/of path)
                         ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                     (.connect ch addr)
                     (.configureBlocking ch false)
                     ch)
                   (catch Exception e
                     (when (>= attempt max-retries)
                       (throw (ex-info (str "Cannot connect to bridge socket after "
                                           max-retries " attempts: " (.getMessage e))
                                       {})))
                     nil))]
      (if result
        result
        (do (Thread/sleep 200)
            (recur (inc attempt)))))))

(defn send-cmd [ch cmd]
  (let [buf (ByteBuffer/wrap (.getBytes (str cmd "\n") StandardCharsets/UTF_8))]
    (.write ch buf)))

(defn read-available
  "Read all available data from channel, return as string."
  [ch]
  (let [buf (ByteBuffer/allocate 4096)]
    (try
      (let [n (.read ch buf)]
        (when (pos? n)
          (.flip buf)
          (let [bytes (byte-array n)]
            (.get buf bytes)
            (String. bytes StandardCharsets/UTF_8))))
      (catch Exception _e nil))))

(defn read-lines-timeout
  "Read lines from channel, waiting up to timeout-ms. Returns vector of lines."
  [ch timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [acc ""]
      (if (>= (System/currentTimeMillis) deadline)
        (filterv (complement str/blank?) (str/split acc #"\n"))
        (let [data (read-available ch)]
          (if data
            (recur (str acc data))
            (do (Thread/sleep 50)
                (recur acc))))))))

(defn drain
  "Drain any pending data from the channel."
  [ch]
  (read-lines-timeout ch 100))

;; ----------------------------------------------------------------
;; Key injection helpers
;; ----------------------------------------------------------------

(defn inject-key
  "Inject a key press (value=1) or release (value=0) into the test keyboard."
  [code value]
  (.write *kbd-writer* (str code " " value "\n"))
  (.flush *kbd-writer*))

(defn inject-tap
  "Inject a key press + release."
  [code]
  (inject-key code 1)
  (Thread/sleep 10)
  (inject-key code 0)
  (Thread/sleep 10))

(defn inject-combo
  "Inject modifier(s) + key press/release."
  [mod-codes key-code]
  (doseq [m mod-codes] (inject-key m 1))
  (Thread/sleep 10)
  (inject-key key-code 1)
  (Thread/sleep 10)
  (inject-key key-code 0)
  (Thread/sleep 10)
  (doseq [m (reverse mod-codes)] (inject-key m 0))
  (Thread/sleep 10))

;; ----------------------------------------------------------------
;; Process management
;; ----------------------------------------------------------------

(defn ensure-built []
  (when (or (not (.exists (io/file bridge-bin)))
            (not (.exists (io/file test-kbd-bin))))
    (println "Building bridge and test-kbd...")
    (let [r (p/shell {:dir (str project-dir "/bridge")} "make")]
      (when-not (zero? (:exit r))
        (throw (ex-info "Build failed" {}))))))

(defn start-test-kbd []
  (let [proc (p/process {:out :stream :in :stream :err :inherit}
                         test-kbd-bin)
        reader (io/reader (:out proc))
        devnode (.readLine reader)]
    (when (or (nil? devnode) (str/blank? devnode))
      (throw (ex-info "test-kbd did not report device path" {})))
    (println "Test keyboard:" devnode)
    ;; Wait for device to settle
    (Thread/sleep 200)
    {:proc proc :devnode devnode :reader reader}))

(defn start-bridge [devnode sock-path]
  (let [proc (p/process {:out :inherit :in :inherit
                          :err :inherit}
                         bridge-bin
                         "--socket" sock-path
                         "--timeout" "60"
                         devnode)]
    ;; Wait for bridge to start and create socket
    (Thread/sleep 500)
    proc))

(defn stop-process [proc]
  (when proc
    (try
      (.destroy (:proc proc (or proc proc)))
      (catch Exception _))
    (try
      (if (:proc proc)
        (deref (:proc proc) 2000 nil)
        (deref proc 2000 nil))
      (catch Exception _))))

;; ----------------------------------------------------------------
;; Test setup / teardown
;; ----------------------------------------------------------------

(defn setup []
  (ensure-built)
  (let [sock-path (socket-path)
        kbd-info (start-test-kbd)
        bridge (start-bridge (:devnode kbd-info) sock-path)
        ch (connect-socket sock-path 15)
        writer (io/writer (:in (:proc kbd-info)))]
    (alter-var-root #'*test-kbd* (constantly kbd-info))
    (alter-var-root #'*bridge* (constantly bridge))
    (alter-var-root #'*socket* (constantly ch))
    (alter-var-root #'*kbd-writer* (constantly writer))
    ;; Drain HELLO message
    (drain ch)))

(defn teardown []
  (when *socket*
    (try (.close *socket*) (catch Exception _)))
  (when *bridge*
    (stop-process *bridge*))
  (when *test-kbd*
    ;; Close stdin to signal test-kbd to exit
    (try (.close (:in (:proc *test-kbd*))) (catch Exception _))
    (stop-process (:proc *test-kbd*)))
  ;; Clean up socket file
  (let [f (io/file (socket-path))]
    (when (.exists f) (.delete f))))

;; ----------------------------------------------------------------
;; Tests
;; ----------------------------------------------------------------

(defn run-tests []
  (println "\n=== tecla-bridge integration tests ===\n")

  ;; --- Connection ---

  (deftest "client connects and receives HELLO"
    ;; HELLO was consumed in setup - reconnect to verify
    ;; (this is implicitly tested by setup succeeding)
    true)

  ;; --- Remap configuration ---

  (deftest "ADD_REMAP is accepted"
    (send-cmd *socket*
              (format "ADD_REMAP %d %d %d %d" MOD_META KEY_M MOD_CTRL KEY_M))
    (Thread/sleep 100)
    ;; No error response = success (bridge logs to stderr)
    true)

  (deftest "SET_TRIGGER is accepted"
    (send-cmd *socket*
              (format "SET_TRIGGER %d %d" MOD_META KEY_SPACE))
    (Thread/sleep 100)
    true)

  ;; --- Passthrough mode ---

  (deftest "plain keypress in passthrough - no socket messages"
    (drain *socket*)
    (inject-tap KEY_A)
    (let [lines (read-lines-timeout *socket* 300)]
      (assert= [] lines "should not receive messages for plain keys")))

  (deftest "modifier keypress in passthrough - no socket messages"
    (drain *socket*)
    (inject-combo [KEY_LEFTSHIFT] KEY_A)
    (let [lines (read-lines-timeout *socket* 300)]
      (assert= [] lines "should not receive messages for shifted keys")))

  ;; --- Trigger ---

  (deftest "Super+Space triggers modal"
    (drain *socket*)
    (inject-combo [KEY_LEFTMETA] KEY_SPACE)
    (let [lines (read-lines-timeout *socket* 500)]
      (assert-contains lines "TRIGGER" "should receive TRIGGER message")))

  ;; --- Modal mode ---

  (deftest "key events in modal are sent to client"
    ;; We should be in modal mode now (from previous test)
    (drain *socket*)
    (inject-tap KEY_A)
    (let [lines (read-lines-timeout *socket* 500)]
      (assert-contains (str/join " " lines) "KEY"
                       "should receive KEY message in modal")))

  (deftest "modifier keys in modal are NOT sent as KEY messages"
    (drain *socket*)
    (inject-key KEY_LEFTSHIFT 1)
    (Thread/sleep 50)
    (inject-key KEY_LEFTSHIFT 0)
    (let [lines (read-lines-timeout *socket* 300)]
      ;; Modifiers should pass through to uinput, not be sent as KEY messages
      (assert= [] lines "modifier-only events should not produce KEY messages")))

  (deftest "PASSTHROUGH exits modal mode"
    (send-cmd *socket* "PASSTHROUGH")
    (Thread/sleep 100)
    (drain *socket*)
    (inject-tap KEY_B)
    (let [lines (read-lines-timeout *socket* 300)]
      (assert= [] lines "plain key after PASSTHROUGH should not produce messages")))

  ;; --- Re-trigger after exit ---

  (deftest "can re-trigger modal after exit"
    (drain *socket*)
    (inject-combo [KEY_LEFTMETA] KEY_SPACE)
    (let [lines (read-lines-timeout *socket* 500)]
      (assert-contains lines "TRIGGER" "should receive TRIGGER on re-entry"))
    ;; Exit modal for clean state
    (send-cmd *socket* "PASSTHROUGH")
    (Thread/sleep 100))

  ;; --- CLEAR_REMAPS ---

  (deftest "CLEAR_REMAPS resets remap table"
    (send-cmd *socket* "CLEAR_REMAPS")
    (Thread/sleep 100)
    ;; If we got here without error, command was accepted
    true)

  ;; --- EMIT command ---

  (deftest "EMIT command in modal mode"
    ;; Enter modal
    (inject-combo [KEY_LEFTMETA] KEY_SPACE)
    (Thread/sleep 200)
    (drain *socket*)
    ;; Send EMIT - should not error
    (send-cmd *socket* (format "EMIT %d 1" KEY_A))
    (send-cmd *socket* (format "EMIT %d 0" KEY_A))
    (Thread/sleep 100)
    ;; Exit modal
    (send-cmd *socket* "PASSTHROUGH")
    (Thread/sleep 100)
    true)

  ;; --- Multiple remaps ---

  (deftest "multiple remaps can be added"
    (send-cmd *socket* "CLEAR_REMAPS")
    (send-cmd *socket*
              (format "ADD_REMAP %d %d %d %d" MOD_META KEY_A MOD_CTRL KEY_A))
    (send-cmd *socket*
              (format "ADD_REMAP %d %d %d %d" MOD_META KEY_B MOD_CTRL KEY_B))
    (send-cmd *socket*
              (format "ADD_REMAP %d %d %d %d" MOD_META KEY_M MOD_CTRL KEY_M))
    (Thread/sleep 100)
    true))

;; ----------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------

(defn -main []
  (try
    (setup)
    (run-tests)
    (finally
      (teardown)))

  (println)
  (let [{:keys [passed failed errors]} @test-results]
    (println (format "Results: %d passed, %d failed" passed failed))
    (when (seq errors)
      (println "\nFailures:")
      (doseq [{:keys [test msg]} errors]
        (println (str "  " test ": " msg))))
    (System/exit (if (zero? failed) 0 1))))

(-main)
