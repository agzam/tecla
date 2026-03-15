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
            [clojure.java.shell :refer [sh]]))

(defonce fsm-state (atom nil))
(defonce nrepl-server (atom nil))

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
  "Get the effective keymap: app-local merged on top of global."
  [config]
  ;; TODO: merge app-local-keymap when compositor adapter is ready
  (:global-keymap config))

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

(defn- dispatch-form!
  "Dispatch a list form like (wm/focus-left) or (my.actions/launch \"kitty\")."
  [form]
  (let [sym (first form)
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
  "Handle a keymap match that simulates keys. Track for release."
  [state mod-state code combo]
  ;; Consume source modifiers, emit target combo
  (swap! state (fn [s]
                 (-> s
                     (update :consumed-mods bit-or (bit-and mod-state (:buffered-mods s)))
                     (update :buffered-mods #(bit-and % (bit-not mod-state)))
                     (assoc-in [:active-sims code]
                               {:from-mods mod-state :to-combo combo}))))
  (simulate-keys! combo))

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
;; Effect handling
;; ----------------------------------------------------------------

(defn handle-effect [effect]
  (when effect
    (case (:type effect)
      :enter-modal nil
      :exit-modal (do
                    (swap! kbd-state assoc :buffered-mods 0 :consumed-mods 0)
                    (let [ms (:mod-state @kbd-state)]
                      (doseq [bit [1 2 4 8]]
                        (when (pos? (bit-and ms bit))
                          (bridge/emit! (keys/mod-bit->code bit) 1)))))
      :action (dispatch-value! (:action effect))
      :action-and-exit (do (dispatch-value! (:action effect))
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
        is-mod (bridge/emit! code value)

        press?
        (let [key-kw (keys/code->key code)
              new-fsm (swap! fsm-state fsm/process-event
                             {:type :key :key key-kw :press? true})]
          (handle-effect (:effect new-fsm))
          (swap! fsm-state assoc :effect nil))

        :else nil)

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

(defn load-config [path]
  (if (.exists (io/file path))
    (edn/read-string (slurp path))
    (throw (ex-info (str "Config not found: " path) {:path path}))))

(defn apply-config! [config]
  (let [keymap (effective-keymap config)]
    (swap! kbd-state assoc :buffer-mods (compute-buffer-mods keymap))))

(defn update-config! [f & args]
  (let [new-config (apply f (:config @fsm-state) args)]
    (swap! fsm-state assoc :config new-config)
    (apply-config! new-config)))

;; ----------------------------------------------------------------
;; Entry point
;; ----------------------------------------------------------------

(defn stop! []
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

    (hud/start! fsm-state fsm/current-menu)

    (let [srv (try (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port})
                   (catch java.net.BindException _
                     (nrepl/start-server! {:host "127.0.0.1"
                                           :port (inc nrepl-port)})))]
      (reset! nrepl-server srv)
      (println "nREPL started"))

    (println "tecla ready.")

    (let [latch (java.util.concurrent.CountDownLatch. 1)]
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn [] (println "\nShutting down...")
                                   (stop!)
                                   (.countDown latch))))
      (.await latch))))
