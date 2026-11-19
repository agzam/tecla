#!/usr/bin/env bb

;; Integration tests on real evdev/uinput devices: the tecla-bridge protocol
;; with a raw socket client, then the whole pipeline with tecla.core as the
;; client (test keyboard -> bridge -> core -> bridge -> virtual keyboard).
;; Usage: bb test/bridge_test.clj

(require '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.java.io :as io]
         '[tecla.bridge :as bridge]
         '[tecla.core :as core]
         '[tecla.fsm :as fsm])

(import [java.net StandardProtocolFamily UnixDomainSocketAddress]
        [java.nio.channels SocketChannel]
        [java.nio ByteBuffer]
        [java.nio.charset StandardCharsets])

(def KEY_ESC 1)
(def KEY_A 30)
(def KEY_B 48)
(def KEY_C 46)
(def KEY_H 35)
(def KEY_M 50)
(def KEY_SPACE 57)
(def KEY_LEFTCTRL 29)
(def KEY_LEFTSHIFT 42)
(def KEY_RIGHTSHIFT 54)
(def KEY_RIGHTALT 100)
(def KEY_LEFTMETA 125)
(def KEY_RIGHTMETA 126)
(def KEY_MUTE 113)          ;; not a key of tecla-test-keyboard
(def KEY_EMOJI_PICKER 585)  ;; above the BTN_* range

(def project-dir (.getCanonicalPath (io/file ".")))
(def bridge-bin (str project-dir "/bridge/tecla-bridge"))
(def test-kbd-bin (str project-dir "/bridge/tecla-test-kbd"))
(def test-monitor-bin (str project-dir "/bridge/tecla-test-monitor"))

(def ^:dynamic *test-kbd* nil)
(def ^:dynamic *bridge* nil)
(def ^:dynamic *monitor* nil)
(def ^:dynamic *socket* nil)
(def ^:dynamic *kbd-writer* nil)
(def ^:dynamic *monitor-reader* nil)
(def ^:dynamic *bridge-devnode* nil)

(def test-results (atom {:passed 0 :failed 0 :errors []}))

(defn pass! [n] (swap! test-results update :passed inc) (println (str "  \u2713 " n)))
(defn fail! [n msg]
  (swap! test-results #(-> % (update :failed inc) (update :errors conj {:test n :msg msg})))
  (println (str "  \u2717 " n ": " msg)))

(defmacro deftest [name & body]
  `(try ~@body (pass! ~(str name))
        (catch Exception e# (fail! ~(str name) (.getMessage e#)))))

(defn assert= [expected actual msg]
  (when (not= expected actual)
    (throw (ex-info (str msg " - expected: " (pr-str expected) ", got: " (pr-str actual)) {}))))

(defn assert-contains [h n msg]
  (when-not (str/includes? (str h) (str n))
    (throw (ex-info (str msg " - not found: " (pr-str n)) {}))))

;; ---- Socket helpers ----

(defn socket-path [] (str (or (System/getenv "XDG_RUNTIME_DIR") "/tmp") "/tecla-test.sock"))

(defn connect-socket [path retries]
  (loop [i 0]
    (let [r (try (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
                   (.connect ch (UnixDomainSocketAddress/of path))
                   (.configureBlocking ch false) ch)
                 (catch Exception e (when (>= i retries) (throw e)) nil))]
      (or r (do (Thread/sleep 200) (recur (inc i)))))))

(defn send-cmd [ch cmd]
  (.write ch (ByteBuffer/wrap (.getBytes (str cmd "\n") StandardCharsets/UTF_8))))

(defn read-lines-timeout [ch ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [acc ""]
      (if (>= (System/currentTimeMillis) deadline)
        (filterv (complement str/blank?) (str/split acc #"\n"))
        (let [buf (ByteBuffer/allocate 4096)
              n (try (.read ch buf) (catch Exception _ 0))]
          (if (pos? n)
            (do (.flip buf) (let [b (byte-array n)] (.get buf b) (recur (str acc (String. b StandardCharsets/UTF_8)))))
            (do (Thread/sleep 30) (recur acc))))))))

(defn drain [ch] (read-lines-timeout ch 100))

;; ---- Key injection ----

(defn inject-key [code value]
  (.write *kbd-writer* (str code " " value "\n")) (.flush *kbd-writer*))

(defn inject-tap [code]
  (inject-key code 1) (Thread/sleep 10) (inject-key code 0) (Thread/sleep 10))

(defn inject-combo [mods key-code]
  (doseq [m mods] (inject-key m 1)) (Thread/sleep 10)
  (inject-key key-code 1) (Thread/sleep 10)
  (inject-key key-code 0) (Thread/sleep 10)
  (doseq [m (reverse mods)] (inject-key m 0)) (Thread/sleep 10))

;; ---- Monitor helpers ----

(defn read-monitor-events [ms]
  (let [deadline (+ (System/currentTimeMillis) ms) events (atom [])]
    (loop []
      (if (>= (System/currentTimeMillis) deadline) @events
          (if (.ready *monitor-reader*)
            (let [line (.readLine *monitor-reader*)]
              (when (and line (not (str/blank? line)) (not= line "READY"))
                (let [parts (str/split (str/trim line) #"\s+")]
                  (when (= 2 (count parts))
                    (swap! events conj [(parse-long (first parts)) (parse-long (second parts))]))))
              (recur))
            (do (Thread/sleep 20) (recur)))))))

(defn drain-monitor [] (read-monitor-events 100))
(defn ev? [evts code val] (some (fn [[c v]] (and (= c code) (= v val))) evts))
(defn no-ev? [evts code val] (not (ev? evts code val)))

(defn held-on-output
  "Keycodes left down on the bridge's virtual keyboard after `evts`."
  [evts]
  (reduce (fn [down [code value]]
            (if (= 1 value) (conj down code) (disj down code)))
          #{} evts))

;; ---- Process management ----

(defn ensure-built []
  (when (some #(not (.exists (io/file %))) [bridge-bin test-kbd-bin test-monitor-bin])
    (let [r (p/shell {:dir (str project-dir "/bridge")} "make")]
      (when-not (zero? (:exit r)) (throw (ex-info "Build failed" {}))))))

(defn start-test-kbd []
  (let [proc (p/process {:out :stream :in :stream :err :inherit} test-kbd-bin "--mouse")
        reader (io/reader (:out proc))
        devnode (.readLine reader)
        mouse (.readLine reader)]
    (when (or (str/blank? devnode) (str/blank? mouse))
      (throw (ex-info "test-kbd: no device path" {})))
    (println "Test keyboard:" devnode "mouse:" mouse)
    (Thread/sleep 200)
    {:proc proc :devnode devnode :mouse mouse :reader reader}))

(defn start-bridge
  "--device keeps the bridge off every other virtual device (Espanso,
   ydotool) when the tests run on a desktop."
  [sock-path devices]
  (let [proc (apply p/process {:out :inherit :in :inherit :err :inherit}
                    bridge-bin "--allow-virtual"
                    "--socket" sock-path "--timeout" "120"
                    (mapcat (fn [d] ["--device" d]) devices))]
    (Thread/sleep 500) proc))

(defn start-monitor [devnode]
  (let [proc (p/process {:out :stream :in :inherit :err :inherit} test-monitor-bin devnode)
        reader (io/reader (:out proc))
        ready (.readLine reader)]
    (when (not= ready "READY") (throw (ex-info (str "monitor not ready: " ready) {})))
    (println "Monitor:" devnode)
    {:proc proc :reader reader}))

(defn stop-proc [p]
  (let [proc (if (map? p) (:proc p) p)]
    (try (.destroy proc) (catch Exception _))
    (try (deref proc 2000 nil) (catch Exception _))))

;; ---- Setup / teardown ----

(defn setup []
  (ensure-built)
  (let [sp (socket-path)
        kbd (start-test-kbd)
        bridge (start-bridge sp [(:devnode kbd) (:mouse kbd)])
        ch (connect-socket sp 15)
        writer (io/writer (:in (:proc kbd)))]
    (alter-var-root #'*test-kbd* (constantly kbd))
    (alter-var-root #'*bridge* (constantly bridge))
    (alter-var-root #'*socket* (constantly ch))
    (alter-var-root #'*kbd-writer* (constantly writer))
    (let [lines (read-lines-timeout ch 1000)
          dn-line (first (filter #(str/starts-with? % "DEVNODE") lines))
          dn (when dn-line (str/trim (subs dn-line 8)))]
      (when-not dn (throw (ex-info "No DEVNODE" {:lines lines})))
      (alter-var-root #'*bridge-devnode* (constantly dn))
      (println "Bridge virtual device:" dn)
      (Thread/sleep 200)
      (let [mon (start-monitor dn)]
        (alter-var-root #'*monitor* (constantly mon))
        (alter-var-root #'*monitor-reader* (constantly (:reader mon)))))))

(defn teardown []
  (when *socket* (try (.close *socket*) (catch Exception _)))
  ;; Bridge first: its virtual keyboard goes away while the monitor still grabs it
  (when *bridge* (stop-proc *bridge*))
  (when *monitor* (stop-proc (:proc *monitor*)))
  (when *test-kbd*
    (try (.close (:in (:proc *test-kbd*))) (catch Exception _))
    (stop-proc (:proc *test-kbd*)))
  (let [f (io/file (socket-path))] (when (.exists f) (.delete f))))

;; ---- Tests ----

(defn run-tests []
  (println "\n=== tecla-bridge integration tests ===\n")

  (deftest "connects and receives HELLO + DEVNODE"
    (assert-contains *bridge-devnode* "/dev/input/event" "device path"))

  ;; -- EV forwarding (bridge sends all key events to client) --

  (deftest "plain keypress produces EV message"
    (drain *socket*)
    (inject-tap KEY_A)
    (let [lines (read-lines-timeout *socket* 500)]
      (assert-contains (str/join " " lines) "EV 30 1" "A press")
      (assert-contains (str/join " " lines) "EV 30 0" "A release")))

  (deftest "modifier keypress produces EV message"
    (drain *socket*)
    (inject-key KEY_LEFTMETA 1) (Thread/sleep 20) (inject-key KEY_LEFTMETA 0)
    (let [lines (read-lines-timeout *socket* 500)]
      (assert-contains (str/join " " lines) "EV 125 1" "Meta press")
      (assert-contains (str/join " " lines) "EV 125 0" "Meta release")))

  (deftest "combo keypress produces EV messages"
    (drain *socket*)
    (inject-combo [KEY_LEFTMETA] KEY_M)
    (let [lines (read-lines-timeout *socket* 500)
          joined (str/join " " lines)]
      (assert-contains joined "EV 125 1" "Meta press")
      (assert-contains joined "EV 50 1" "M press")
      (assert-contains joined "EV 50 0" "M release")
      (assert-contains joined "EV 125 0" "Meta release")))

  ;; -- EM commands (client tells bridge what to emit) --

  (deftest "EM command produces key on output"
    (drain-monitor)
    (send-cmd *socket* "EM 30 1") (send-cmd *socket* "EM 30 0")
    (Thread/sleep 200)
    (let [events (read-monitor-events 500)]
      (assert= true (ev? events KEY_A 1) "A press on output")
      (assert= true (ev? events KEY_A 0) "A release on output")))

  (deftest "EM modifier + key combo on output"
    (drain-monitor)
    (send-cmd *socket* "EM 29 1")  ;; Ctrl press
    (send-cmd *socket* "EM 50 1")  ;; M press
    (send-cmd *socket* "EM 50 0")  ;; M release
    (send-cmd *socket* "EM 29 0")  ;; Ctrl release
    (Thread/sleep 200)
    (let [events (read-monitor-events 500)]
      (assert= true (ev? events KEY_LEFTCTRL 1) "Ctrl press")
      (assert= true (ev? events KEY_M 1) "M press")
      (assert= true (ev? events KEY_M 0) "M release")
      (assert= true (ev? events KEY_LEFTCTRL 0) "Ctrl release")))

  ;; -- No passthrough when client connected --

  (deftest "bridge does NOT passthrough keys when client connected"
    (drain-monitor)
    (inject-tap KEY_B)
    (Thread/sleep 200)
    (let [events (read-monitor-events 300)]
      (assert= true (no-ev? events KEY_B 1)
               "B should NOT appear on output (client handles it)")))

  ;; -- Passthrough when no client --
  ;; (Can't easily test disconnect/reconnect in this harness, skip for now)

  ;; -- Fast path: after PASSTHROUGH the bridge emits unbound keys itself --

  (deftest "fast path emits an unbound key without the client"
    (send-cmd *socket* "BIND 8 57") (send-cmd *socket* "PASSTHROUGH")
    (Thread/sleep 50)
    (drain *socket*) (drain-monitor)
    (inject-tap KEY_A)
    (let [events (read-monitor-events 300)
          lines (read-lines-timeout *socket* 200)]
      (assert= [[KEY_A 1] [KEY_A 0]] events "output")
      (assert= [] lines "lines sent to the client")))

  (deftest "key pressed on the fast path is released after INTERCEPT"
    (drain-monitor)
    (inject-key KEY_LEFTSHIFT 1) (Thread/sleep 30)
    (send-cmd *socket* "INTERCEPT") (Thread/sleep 50)
    (inject-key KEY_LEFTSHIFT 0)
    (let [events (read-monitor-events 300)]
      (send-cmd *socket* (str "EM " KEY_LEFTSHIFT " 0"))
      (assert= [[KEY_LEFTSHIFT 1] [KEY_LEFTSHIFT 0]] events "output")))

  ;; -- Virtual devices --

  (deftest "EM reaches the output for a key the grabbed keyboard lacks"
    (drain-monitor)
    (send-cmd *socket* (str "EM " KEY_MUTE " 1"))
    (send-cmd *socket* (str "EM " KEY_MUTE " 0"))
    (assert= [[KEY_MUTE 1] [KEY_MUTE 0]] (read-monitor-events 300) "output"))

  (deftest "bridge grabs the test mouse, whatever its event number"
    ;; a second grab fails while the bridge holds the device
    (let [probe (p/process {:out :string :err :string}
                           test-monitor-bin (:mouse *test-kbd*))
          exit (:exit (deref probe 1000 nil))]
      (when-not exit (p/destroy probe))
      (assert= 1 exit "exit code of a monitor on the mouse")))

  (deftest "extended key code goes to the keyboard while a mouse is grabbed"
    (drain-monitor)
    (send-cmd *socket* (str "EM " KEY_EMOJI_PICKER " 1"))
    (send-cmd *socket* (str "EM " KEY_EMOJI_PICKER " 0"))
    (assert= [[KEY_EMOJI_PICKER 1] [KEY_EMOJI_PICKER 0]]
             (read-monitor-events 300) "output")))

;; ---- Pipeline: test keyboard -> bridge -> tecla.core -> bridge -> output ----

(def fired (atom 0))

(def pipeline-config
  {:global-keymap
   {[:super :c] [:ctrl :c]
    [:alt :backspace] [:ctrl :backspace]
    [:super :space]
    {:desc "Main"
     :keys {:a {:desc "Apps"
                :keys {[:shift :b] {:desc "Brave Private"
                                    :action (fn [] (swap! fired inc))}}}}}}})

(defn pipeline-events
  "Reset the client, type `steps` and return what reached the output. The
   pause after each key lets the client switch the bridge between passthrough
   and intercept. Keys still down are released, so a failure stays local."
  [steps]
  (reset! core/fsm-state (fsm/init pipeline-config))
  (reset! core/kbd-state {:mod-state 0 :held-mods #{} :buffered-mods 0
                          :consumed-mods 0 :active-sims {} :buffer-mods 0})
  (core/update-config! identity)
  (Thread/sleep 100)
  (drain-monitor)
  (doseq [[code value] steps]
    (inject-key code value)
    (Thread/sleep 60))
  (let [events (read-monitor-events 300)]
    (doseq [code (held-on-output events)] (bridge/emit! code 0))
    events))

(defn run-pipeline-tests []
  (println "\n=== pipeline tests (bridge + tecla.core) ===\n")
  (.close *socket*)
  (bridge/connect! core/on-key-event :socket-path (socket-path))

  (deftest "remap: Super+C comes out as Ctrl+C"
    (assert= [[KEY_LEFTCTRL 1] [KEY_C 1] [KEY_C 0] [KEY_LEFTCTRL 0]]
             (pipeline-events [[KEY_LEFTMETA 1] [KEY_C 1] [KEY_C 0] [KEY_LEFTMETA 0]])
             "output"))

  (deftest "Super+Space, a, right Shift+b leaves no key down"
    (reset! fired 0)
    (let [events (pipeline-events
                  [[KEY_LEFTMETA 1] [KEY_SPACE 1] [KEY_SPACE 0] [KEY_LEFTMETA 0]
                   [KEY_A 1] [KEY_A 0]
                   [KEY_RIGHTSHIFT 1] [KEY_B 1] [KEY_B 0] [KEY_RIGHTSHIFT 0]])]
      (assert= 1 @fired "times the menu action ran")
      (assert= #{} (held-on-output events) "keys left down")
      (assert= true (no-ev? events KEY_LEFTSHIFT 1) "no left Shift press")))

  (deftest "right Alt and right Super come out as themselves"
    (doseq [m [KEY_RIGHTALT KEY_RIGHTMETA]]
      (assert= [[m 1] [KEY_H 1] [KEY_H 0] [m 0]]
               (pipeline-events [[m 1] [KEY_H 1] [KEY_H 0] [m 0]])
               (str "output for " m))))

  (deftest "Shift held while the modal opens is released on output"
    (assert= [[KEY_LEFTSHIFT 1] [KEY_LEFTSHIFT 0]]
             (pipeline-events
              [[KEY_LEFTSHIFT 1]
               [KEY_LEFTMETA 1] [KEY_SPACE 1] [KEY_SPACE 0] [KEY_LEFTMETA 0]
               [KEY_LEFTSHIFT 0]
               [KEY_ESC 1] [KEY_ESC 0]])
             "output"))

  (bridge/disconnect!))

;; ---- Main ----

(defn -main []
  (try (setup) (run-tests) (run-pipeline-tests) (finally (teardown)))
  (println)
  (let [{:keys [passed failed errors]} @test-results]
    (println (format "Results: %d passed, %d failed" passed failed))
    (when (seq errors)
      (println "\nFailures:")
      (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
    (System/exit (if (zero? failed) 0 1))))

(-main)
