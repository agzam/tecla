(ns tecla.core
  "Entry point. Unified keymap - all keyboard logic here."
  (:require [tecla.bridge :as bridge]
            [tecla.fsm :as fsm]
            [tecla.keys :as keys]
            [tecla.hud :as hud]
            [tecla.wm :as wm]
            [babashka.nrepl.server :as nrepl]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(defonce fsm-state (atom nil))
(defonce nrepl-server (atom nil))
(defonce config-watcher (atom nil))
(defonce focused-app (atom nil))
(defonce pre-modal-window (atom nil))

;; Software key-repeat for transient menus (bridge drops hardware autorepeat)
(defonce ^:private repeat-state (atom {:key-code nil :future nil}))

;; ----------------------------------------------------------------
;; Keyboard state
;; ----------------------------------------------------------------

(defonce kbd-state
  (atom {:mod-state 0
         :buffered-mods 0
         :consumed-mods 0
         :active-sims {}      ;; code -> {:from-mods :to-combo} for key release
         :buffer-mods 0}))    ;; bitmask of mods to buffer (from keymap scan)

(defn- update-mod-state [state code value]
  (let [bit (keys/code->mod-bit code)]
    (if (zero? bit)
      state
      (update state :mod-state
              (if (= value 1)
                #(bit-or % bit)
                #(bit-and % (bit-not bit)))))))

(defn compute-buffer-mods
  "Scan keymap for modifier bits that need buffering."
  [keymap]
  (reduce (fn [acc [combo _]]
            (if (vector? combo)
              (let [mods (butlast combo)]
                (bit-or acc (keys/mods->bits mods)))
              acc))
          0
          keymap))

;; ----------------------------------------------------------------
;; Keymap lookup
;; ----------------------------------------------------------------

(defn- find-binding
  "Look up a key combo in a keymap. Returns the value or nil."
  [keymap mod-state code]
  (let [key-kw (keys/code->key code)]
    (when key-kw
      (some (fn [[combo value]]
              (when (vector? combo)
                (let [mods (butlast combo)
                      key (last combo)
                      bits (keys/mods->bits mods)]
                  (when (and (= key key-kw) (= bits mod-state))
                    value))))
            keymap))))

(defn- effective-keymap
  "Get the effective keymap: app-local merged on top of global.
   When a focused app matches a key in :app-local-keymap, those
   bindings override the global ones."
  [config]
  (let [global (:global-keymap config)
        app-class (some-> @focused-app str/lower-case)
        local (when app-class
                (some (fn [[k v]]
                        (when (= (str/lower-case (name k)) app-class)
                          v))
                      (:app-local-keymap config)))]
    (if local
      (merge global local)
      global)))

;; ----------------------------------------------------------------
;; Value dispatch - the "smart" part
;; ----------------------------------------------------------------

(defn- simulate-keys!
  "Simulate a key combo like [:ctrl :c] or [:ctrl :shift :tab]."
  [combo]
  (let [mods (butlast combo)
        key (last combo)]
    (doseq [m mods]
      (when-let [code (keys/key->code (keyword (str "left-" (name m))))]
        (bridge/emit! code 1)))
    (when-let [code (keys/key->code key)]
      (bridge/emit! code 1)
      (bridge/emit! code 0))
    (doseq [m (reverse mods)]
      (when-let [code (keys/key->code (keyword (str "left-" (name m))))]
        (bridge/emit! code 0)))))

(def ^:private form-aliases
  "Short namespace aliases for config EDN forms."
  {"wm" "tecla.wm"
   "media" "tecla.media"})

(defn- expand-sym
  "Expand short aliases: wm/focus-left -> tecla.wm/focus-left"
  [sym]
  (if-let [ns-part (namespace sym)]
    (if-let [full-ns (form-aliases ns-part)]
      (symbol full-ns (name sym))
      sym)
    sym))

(defn- dispatch-form!
  "Dispatch a list form like (wm/focus-left) or (my.ns/launch \"kitty\")."
  [form]
  (let [sym (expand-sym (first form))
        args (rest form)]
    (if-let [resolved (try (requiring-resolve sym) (catch Exception _ nil))]
      (future (apply resolved args))
      (println "Cannot resolve:" sym))))

(defn dispatch-value!
  "Dispatch an action value based on its type."
  [value]
  (cond
    (vector? value)  (simulate-keys! value)
    (string? value)  (future (sh "sh" "-c" value))
    (list? value)    (dispatch-form! value)
    (fn? value)      (future (value))
    :else            (println "Unknown action:" (pr-str value))))

;; ----------------------------------------------------------------
;; Key simulation tracking (for proper release handling)
;; ----------------------------------------------------------------

(defn- handle-sim-press!
  "Handle a keymap match that remaps keys. Press target mods + key,
   track for release in handle-sim-release!."
  [state mod-state code combo]
  (swap! state (fn [s]
                 (-> s
                     (update :consumed-mods bit-or (bit-and mod-state (:buffered-mods s)))
                     (update :buffered-mods #(bit-and % (bit-not mod-state)))
                     (assoc-in [:active-sims code]
                               {:from-mods mod-state :to-combo combo}))))
  ;; Press only - release happens in handle-sim-release!
  (let [mods (butlast combo)
        key (last combo)]
    (doseq [m mods]
      (when-let [kc (keys/key->code (keyword (str "left-" (name m))))]
        (bridge/emit! kc 1)))
    (when-let [kc (keys/key->code key)]
      (bridge/emit! kc 1))))

(defn- handle-sim-release!
  "Handle key release for an active simulation. Returns true if handled."
  [state code]
  (when-let [{:keys [to-combo]} (get-in @state [:active-sims code])]
    ;; Release target key + mods
    (let [mods (butlast to-combo)
          key (last to-combo)]
      (when-let [kc (keys/key->code key)]
        (bridge/emit! kc 0))
      (doseq [m (reverse mods)]
        (when-let [kc (keys/key->code (keyword (str "left-" (name m))))]
          (bridge/emit! kc 0))))
    (swap! state update :active-sims dissoc code)
    true))

;; ----------------------------------------------------------------
;; Bridge keymap bindings (must precede handle-effect)
;; ----------------------------------------------------------------

(defn- send-keymap-bindings!
  "Push keymap bindings to the bridge for fast-path passthrough."
  [keymap]
  (bridge/send-clear!)
  (doseq [[combo _] keymap]
    (when (vector? combo)
      (let [mods    (butlast combo)
            key     (last combo)
            mod-bits (keys/mods->bits mods)
            keycode  (keys/key->code key)]
        (when keycode
          (bridge/send-binding! mod-bits keycode)))))
  (bridge/set-passthrough!))

;; ----------------------------------------------------------------
;; Effect handling
;; ----------------------------------------------------------------

(defn- restore-pre-modal-focus!
  "Refocus the window that was active before entering modal mode.
   Ensures WM actions target the right window, not the HUD."
  []
  (when-let [{:keys [address]} @pre-modal-window]
    (wm/focus-window-by-address address)))

;; ----------------------------------------------------------------
;; Transient menu key repeat
;; ----------------------------------------------------------------

(defn- stop-repeat! []
  (when-let [f (:future @repeat-state)]
    (future-cancel f))
  (reset! repeat-state {:key-code nil :future nil}))

(defn- start-repeat!
  "Begin software auto-repeat for a transient menu action.
   After an initial delay, repeatedly dispatches `action` until the
   key is released (stop-repeat!) or modal exits."
  [code action]
  (stop-repeat!)
  (let [delay-ms    300
        interval-ms 50
        f (future
            (try
              (Thread/sleep delay-ms)
              (loop []
                (when (= code (:key-code @repeat-state))
                  (restore-pre-modal-focus!)
                  (dispatch-value! action)
                  (Thread/sleep interval-ms)
                  (recur)))
              (catch InterruptedException _)
              (catch Exception e
                (println "Repeat error:" (.getMessage e)))))]
    (reset! repeat-state {:key-code code :future f})))

(defn handle-effect [effect]
  (when effect
    (case (:type effect)
      :enter-modal (do (reset! pre-modal-window
                               (try (wm/focused-window)
                                    (catch Exception _ nil)))
                       (bridge/set-intercept!))
      :exit-modal (do
                    (stop-repeat!)
                    (reset! pre-modal-window nil)
                    (swap! kbd-state assoc :buffered-mods 0 :consumed-mods 0)
                    (let [ms (:mod-state @kbd-state)]
                      (doseq [bit [1 2 4 8]]
                        (when (pos? (bit-and ms bit))
                          (bridge/emit! (keys/mod-bit->code bit) 1))))
                    ;; Rebind for the (possibly changed) focused app,
                    ;; also sets passthrough mode
                    (let [keymap (effective-keymap (:config @fsm-state))]
                      (swap! kbd-state assoc :buffer-mods (compute-buffer-mods keymap))
                      (send-keymap-bindings! keymap)))
      :action (do (restore-pre-modal-focus!)
                  (dispatch-value! (:action effect)))
      :action-and-exit (do (restore-pre-modal-focus!)
                           (dispatch-value! (:action effect))
                           (handle-effect {:type :exit-modal}))
      nil)))

;; ----------------------------------------------------------------
;; Flush buffered modifiers
;; ----------------------------------------------------------------

(defn- flush-buffered-mods! [state]
  (let [buf (:buffered-mods @state)]
    (when-not (zero? buf)
      (doseq [bit [1 2 4 8]]
        (when-not (zero? (bit-and buf bit))
          (bridge/emit! (keys/mod-bit->code bit) 1)))
      (swap! state assoc :buffered-mods 0))))

;; ----------------------------------------------------------------
;; Main key event handler
;; ----------------------------------------------------------------

(defn on-key-event
  [code value]
  (let [state kbd-state
        config (:config @fsm-state)
        is-mod (keys/modifier-code? code)
        buffer-mods (:buffer-mods @state)
        press? (= value 1)
        release? (= value 0)]

    ;; Track physical modifier state
    (swap! state update-mod-state code value)

    (cond
      ;; === MODAL MODE ===
      (= :modal (:mode @fsm-state))
      (cond
        ;; Modifiers are tracked (mod-state updated above) but NOT
        ;; emitted during modal mode. They are only used for matching
        ;; modifier+key menu entries like [:shift :b]. On exit-modal,
        ;; any still-held modifiers are re-emitted for passthrough.
        is-mod nil

        press?
        (let [key-kw (keys/code->key code)
              mods   (:mod-state @state)
              new-fsm (swap! fsm-state fsm/process-event
                             {:type :key :key key-kw
                              :mods mods :press? true})
              effect (:effect new-fsm)]
          (when (= :action (:type effect))
            (start-repeat! code (:action effect)))
          (handle-effect effect)
          (swap! fsm-state assoc :effect nil))

        release?
        (stop-repeat!))

      ;; === PASSTHROUGH MODE ===

      ;; Modifier press: buffer if it appears in any keymap combo
      (and is-mod press?)
      (let [bit (keys/code->mod-bit code)]
        (if (pos? (bit-and bit buffer-mods))
          (swap! state update :buffered-mods bit-or bit)
          (do (flush-buffered-mods! state)
              (bridge/emit! code value))))

      ;; Modifier release
      (and is-mod release?)
      (let [bit (keys/code->mod-bit code)]
        (cond
          (pos? (bit-and bit (:consumed-mods @state)))
          (swap! state update :consumed-mods #(bit-and % (bit-not bit)))

          (pos? (bit-and bit (:buffered-mods @state)))
          (do (bridge/emit! code 1) (bridge/emit! code 0)
              (swap! state update :buffered-mods #(bit-and % (bit-not bit))))

          :else (bridge/emit! code value)))

      ;; Non-modifier key press
      press?
      (let [mod-state (:mod-state @state)
            keymap (effective-keymap config)
            binding (find-binding keymap mod-state code)]
        (cond
          ;; No match -> passthrough
          (nil? binding)
          (do (flush-buffered-mods! state)
              (bridge/emit! code value))

          ;; Vector -> simulate keys (remap)
          (vector? binding)
          (handle-sim-press! state mod-state code binding)

          ;; Map with :keys -> enter modal
          (and (map? binding) (:keys binding))
          (do (swap! state (fn [s]
                             (-> s
                                 (update :consumed-mods bit-or
                                         (bit-and mod-state (:buffered-mods s)))
                                 (update :buffered-mods
                                         #(bit-and % (bit-not mod-state))))))
              (let [new-fsm (swap! fsm-state fsm/process-event
                                   {:type :enter-modal :entry binding})]
                (handle-effect (:effect new-fsm))
                (swap! fsm-state assoc :effect nil)))

          ;; String, list, fn -> execute directly
          :else
          (do (swap! state (fn [s]
                             (-> s
                                 (update :consumed-mods bit-or
                                         (bit-and mod-state (:buffered-mods s)))
                                 (update :buffered-mods
                                         #(bit-and % (bit-not mod-state))))))
              (dispatch-value! binding))))

      ;; Non-modifier key release
      release?
      (if (handle-sim-release! state code)
        nil
        (bridge/emit! code value)))))

;; ----------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------

(defn default-config-path []
  (str (or (System/getenv "XDG_CONFIG_HOME")
           (str (System/getenv "HOME") "/.config"))
       "/tecla/config.edn"))

(defn- ensure-config!
  "Copy config.example.edn to `path` if it doesn't exist yet."
  [path]
  (when-not (.exists (io/file path))
    (let [example (io/file (System/getProperty "user.dir") "config.example.edn")]
      (when (.exists example)
        (.mkdirs (.getParentFile (io/file path)))
        (spit path (slurp example))
        (println "Created" path "from config.example.edn")))))

(defn- read-edn-ordered
  "Read one EDN form, using array-maps for all map literals so that
   key order from the source file is preserved (HUD display order).
   Non-map forms delegate to clojure.edn/read."
  [s]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. s) 16)]
    (letfn [(skip-ws []
              (loop []
                (let [ch (.read rdr)]
                  (cond
                    (neg? ch) nil
                    (or (Character/isWhitespace (char ch))
                        (= (char ch) \,))
                    (recur)
                    (= (char ch) \;)
                    (do (loop []
                          (let [c (.read rdr)]
                            (when (and (>= c 0) (not= (char c) \newline))
                              (recur))))
                        (recur))
                    :else (.unread rdr ch)))))
            (read-form []
              (skip-ws)
              (let [ch (.read rdr)]
                (cond
                  (neg? ch) ::eof
                  (= (char ch) \{) (read-map)
                  :else (do (.unread rdr ch)
                            (edn/read {:eof ::eof} rdr)))))
            (read-map []
              (loop [kvs []]
                (skip-ws)
                (let [ch (.read rdr)]
                  (if (or (neg? ch) (= (char ch) \}))
                    (apply array-map kvs)
                    (do (.unread rdr ch)
                        (recur (conj kvs (read-form) (read-form))))))))]
      (read-form))))

(defn load-config [path]
  (ensure-config! path)
  (if (.exists (io/file path))
    (read-edn-ordered (slurp path))
    (throw (ex-info (str "Config not found: " path) {:path path}))))

(defn apply-config! [config]
  (let [keymap (effective-keymap config)]
    (swap! kbd-state assoc :buffer-mods (compute-buffer-mods keymap))))

(defn update-config! [f & args]
  (let [new-config (apply f (:config @fsm-state) args)]
    (swap! fsm-state assoc :config new-config)
    (apply-config! new-config)
    (send-keymap-bindings! (effective-keymap new-config))))

;; ----------------------------------------------------------------
;; Config file watcher
;; ----------------------------------------------------------------

(defn- dir-fingerprint
  "Max lastModified across all files in dir. Fast, no allocations on
   the hot path - just stat calls in a daemon thread."
  [^java.io.File dir]
  (reduce (fn [mx ^java.io.File f]
            (if (.isFile f) (max mx (.lastModified f)) mx))
          0 (.listFiles dir)))

(defn reload-config!
  "Re-read config from disk and apply without restarting the bridge."
  [path]
  (let [config (load-config path)
        keymap (effective-keymap config)]
    (when-let [c (:compositor config)]
      (wm/set-compositor! c))
    ;; Exit modal if active so the new keymap takes effect cleanly
    (when (= :modal (:mode @fsm-state))
      (let [new-fsm (swap! fsm-state fsm/process-event
                           {:type :key :key :esc :press? true})]
        (handle-effect (:effect new-fsm))
        (swap! fsm-state assoc :effect nil)))
    (swap! fsm-state assoc :config config)
    (apply-config! config)
    (send-keymap-bindings! keymap)
    (println "Config reloaded:" path)))

(defn- on-focus-change!
  "Called by the WM event listener when the focused window changes.
   Updates focused-app atom always, but only rebinds the bridge when
   NOT in modal mode (rebinding calls set-passthrough! which would
   knock the bridge out of intercept mode)."
  [app-class]
  (let [prev @focused-app
        config (:config @fsm-state)]
    (reset! focused-app app-class)
    (when (and config (not= :modal (:mode @fsm-state)))
      (let [lk (fn [c] (some-> c str/lower-case keyword))
            prev-local (get-in config [:app-local-keymap (lk prev)])
            curr-local (get-in config [:app-local-keymap (lk app-class)])]
        (when (or prev-local curr-local)
          (let [keymap (effective-keymap config)]
            (swap! kbd-state assoc :buffer-mods (compute-buffer-mods keymap))
            (send-keymap-bindings! keymap)))))))

(defn- start-config-watcher!
  "Poll the config directory every 2s. When any file changes, reload.
   Runs in a daemon thread - never touches the key processing path."
  [config-path]
  (let [dir (.getParentFile (io/file config-path))
        fp  (atom (dir-fingerprint dir))]
    (reset! config-watcher true)
    (doto (Thread.
            (fn []
              (while @config-watcher
                (Thread/sleep 2000)
                (try
                  (let [new-fp (dir-fingerprint dir)]
                    (when (not= new-fp @fp)
                      (reset! fp new-fp)
                      ;; Brief pause for editors that do write-rename
                      (Thread/sleep 200)
                      (reload-config! config-path)))
                  (catch Exception e
                    (println "Config watcher error:" (.getMessage e))))))
            "tecla-config-watcher")
      (.setDaemon true)
      (.start))
    (println "Watching" (.getPath dir) "for changes")))

(defn- stop-config-watcher! []
  (reset! config-watcher nil))

;; ----------------------------------------------------------------
;; Entry point
;; ----------------------------------------------------------------

(defn stop! []
  (stop-config-watcher!)
  (wm/stop-focus-listener!)
  (hud/stop!)
  (hud/kill!)
  (when @nrepl-server
    (nrepl/stop-server! @nrepl-server)
    (reset! nrepl-server nil))
  (bridge/disconnect!)
  (bridge/stop-bridge!))

(defn start!
  [& {:keys [config-path socket-path nrepl-port]
      :or {nrepl-port 7890}}]
  ;; Add user's src to classpath
  (let [user-src (str (or (System/getenv "XDG_CONFIG_HOME")
                          (str (System/getenv "HOME") "/.config"))
                      "/tecla/src")]
    (when (.isDirectory (io/file user-src))
      (babashka.classpath/add-classpath user-src)
      (println "User classpath:" user-src)))

  (let [config-path (or config-path (default-config-path))
        config (load-config config-path)
        socket-path (or socket-path bridge/default-socket-path)]

    (println "Config:" config-path)

    ;; Detect or set compositor
    (if-let [c (:compositor config)]
      (wm/set-compositor! c)
      (wm/detect-compositor!))

    (reset! fsm-state (fsm/init config))
    (apply-config! config)

    (bridge/start-bridge! {:socket-path socket-path})
    (bridge/connect! on-key-event :socket-path socket-path)
    (send-keymap-bindings! (effective-keymap config))

    (hud/start! fsm-state fsm/current-menu)
    (wm/start-focus-listener! on-focus-change!)

    (let [srv (try (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port})
                   (catch java.net.BindException _
                     (nrepl/start-server! {:host "127.0.0.1"
                                           :port (inc nrepl-port)})))]
      (reset! nrepl-server srv)
      (println "nREPL started"))

    (start-config-watcher! config-path)

    (println "tecla ready.")

    (let [latch (java.util.concurrent.CountDownLatch. 1)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (println "\nShutting down...")
                                   (stop!)
                                   (.countDown latch))))
      (.await latch))))
