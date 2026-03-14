(ns tecla.core
  "Entry point. All keyboard logic lives here - the bridge is just I/O."
  (:require [tecla.bridge :as bridge]
            [tecla.fsm :as fsm]
            [tecla.keys :as keys]
            [tecla.hud :as hud]
            [babashka.nrepl.server :as nrepl]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(defonce fsm-state (atom nil))
(defonce nrepl-server (atom nil))

;; ----------------------------------------------------------------
;; Keyboard state - all in Clojure, debuggable from REPL
;; ----------------------------------------------------------------

(defonce kbd-state
  (atom {:mod-state 0          ;; physical modifier bitmask
         :buffered-mods 0      ;; mods pressed but not yet emitted
         :consumed-mods 0      ;; mods consumed by remap (don't emit)
         :active-remaps {}     ;; key-code -> remap entry (for release handling)
         :remap-mods 0}))      ;; bitmask of all mods used in any remap

(defn- update-mod-state
  "Track physical modifier state from raw key event."
  [state code value]
  (let [bit (keys/code->mod-bit code)]
    (if (zero? bit)
      state
      (update state :mod-state
              (if (= value 1)
                #(bit-or % bit)
                #(bit-and % (bit-not bit)))))))

(defn- compute-remap-mods
  "Compute bitmask of all modifiers used in any remap rule."
  [config]
  (let [trigger-mod (keys/mods->bits (get-in config [:trigger :mods] []))]
    (reduce (fn [acc [from _]]
              (let [mods (if (vector? from) (butlast from) [])]
                (bit-or acc (keys/mods->bits mods))))
            trigger-mod
            (:remaps config))))

;; ----------------------------------------------------------------
;; Remap logic
;; ----------------------------------------------------------------

(defn- find-remap
  "Find a matching remap for the given modifier state and key code."
  [config mod-state code]
  (let [key-kw (keys/code->key code)]
    (some (fn [[from to]]
            (let [from-mods (if (vector? from) (butlast from) [])
                  from-key (if (vector? from) (last from) from)
                  from-bits (keys/mods->bits from-mods)]
              (when (and (= from-key key-kw) (= from-bits mod-state))
                {:from-mods from-bits
                 :from-key code
                 :to-mods (if (vector? to) (butlast to) [])
                 :to-key (if (vector? to) (last to) to)})))
          (:remaps config))))

(defn- emit-mods! [mods value]
  (doseq [m (if (sequential? mods) mods [mods])]
    (let [kw (keyword (str "left-" (name m)))]
      (when-let [code (keys/key->code kw)]
        (bridge/emit! code value)))))

(defn- handle-remap-press!
  "Handle a key press that matches a remap. Returns true if handled."
  [state config code]
  (let [mod-state (:mod-state @state)
        remap (find-remap config mod-state code)]
    (when remap
      ;; Emit target modifier press + key press
      (emit-mods! (:to-mods remap) 1)
      (bridge/emit! (keys/key->code (:to-key remap)) 1)
      ;; Track consumed modifiers and active remap
      (swap! state (fn [s]
                     (-> s
                         (update :consumed-mods bit-or (:from-mods remap))
                         (update :buffered-mods #(bit-and % (bit-not (:from-mods remap))))
                         (assoc-in [:active-remaps code] remap))))
      true)))

(defn- handle-remap-release!
  "Handle key release for an active remap. Returns true if handled."
  [state code]
  (when-let [remap (get-in @state [:active-remaps code])]
    ;; Release target key + target modifiers
    (bridge/emit! (keys/key->code (:to-key remap)) 0)
    (emit-mods! (:to-mods remap) 0)
    (swap! state update :active-remaps dissoc code)
    true))

(defn- flush-buffered-mods!
  "Forward any buffered modifiers to uinput (no remap matched)."
  [state]
  (let [buf (:buffered-mods @state)]
    (when-not (zero? buf)
      (doseq [bit [1 2 4 8]]
        (when-not (zero? (bit-and buf bit))
          (bridge/emit! (keys/mod-bit->code bit) 1)))
      (swap! state assoc :buffered-mods 0))))

;; ----------------------------------------------------------------
;; Action / effect execution
;; ----------------------------------------------------------------

(defmulti execute-action
  (fn [action] (cond (string? action) :shell
                     (fn? action)     :fn
                     (keyword? action) :keyword
                     (vector? action) :vector
                     :else :unknown)))

(defmethod execute-action :shell [cmd]
  (future (sh "sh" "-c" cmd)))

(defmethod execute-action :fn [f] (future (f)))

(defmethod execute-action :vector [v]
  (let [[type mods key] v]
    (when (= type :key)
      (emit-mods! mods 1)
      (bridge/emit! (keys/key->code key) 1)
      (bridge/emit! (keys/key->code key) 0)
      (emit-mods! mods 0))))

(defmethod execute-action :keyword [k]
  (println "Action registry not yet implemented:" k))

(defmethod execute-action :unknown [a]
  (println "Unknown action:" (pr-str a)))

(defn- show-current-menu! []
  (let [menu (fsm/current-menu @fsm-state)
        title (or (:desc (meta menu)) "")]
    (.start (Thread. (fn [] (try (hud/show! title menu) (catch Throwable _)))))))

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
      :action (execute-action (:action effect))
      :action-and-exit (do (execute-action (:action effect))
                           (handle-effect {:type :exit-modal}))
      nil)))

;; ----------------------------------------------------------------
;; Main key event handler
;; ----------------------------------------------------------------

(defn on-key-event
  "Called for every raw key event from the bridge."
  [code value]
  (let [state kbd-state
        config (:config @fsm-state)
        is-mod (keys/modifier-code? code)
        remap-mods (:remap-mods @state)
        press? (= value 1)
        release? (= value 0)]

    ;; Update physical modifier tracking
    (swap! state update-mod-state code value)

    (cond
      ;; === MODAL MODE ===
      (= :modal (:mode @fsm-state))
      (cond
        ;; Modifiers pass through in modal
        is-mod (bridge/emit! code value)

        ;; Key press: feed to FSM
        press?
        (let [key-kw (keys/code->key code)
              new-fsm (swap! fsm-state fsm/process-event
                             {:type :key :key key-kw :press? true})]
          (handle-effect (:effect new-fsm))
          (swap! fsm-state assoc :effect nil))

        ;; Key release in modal: swallow
        :else nil)

      ;; === PASSTHROUGH MODE ===

      ;; Modifier press: buffer if remap-eligible
      (and is-mod press?)
      (let [bit (keys/code->mod-bit code)]
        (if (pos? (bit-and bit remap-mods))
          (swap! state update :buffered-mods bit-or bit)
          (do (flush-buffered-mods! state)
              (bridge/emit! code value))))

      ;; Modifier release
      (and is-mod release?)
      (let [bit (keys/code->mod-bit code)]
        (cond
          ;; Consumed by remap: drop silently
          (pos? (bit-and bit (:consumed-mods @state)))
          (swap! state update :consumed-mods #(bit-and % (bit-not bit)))

          ;; Buffered but unused: emit as tap
          (pos? (bit-and bit (:buffered-mods @state)))
          (do (bridge/emit! code 1)
              (bridge/emit! code 0)
              (swap! state update :buffered-mods #(bit-and % (bit-not bit))))

          ;; Normal release
          :else (bridge/emit! code value)))

      ;; Non-modifier key press
      press?
      (let [mod-state (:mod-state @state)
            trigger-mods (keys/mods->bits (get-in config [:trigger :mods] []))
            trigger-key (keys/key->code (get-in config [:trigger :key]))]
        (cond
          ;; Trigger match
          (and (= code trigger-key) (= mod-state trigger-mods))
          (do (swap! state (fn [s]
                             (-> s
                                 (update :consumed-mods bit-or
                                         (bit-and trigger-mods (:buffered-mods s)))
                                 (update :buffered-mods
                                         #(bit-and % (bit-not trigger-mods))))))
              (let [new-fsm (swap! fsm-state fsm/process-event {:type :trigger})]
                (handle-effect (:effect new-fsm))
                (swap! fsm-state assoc :effect nil)))

          ;; Remap match
          (handle-remap-press! state config code)
          nil ;; already handled

          ;; No match: flush buffered mods, pass through
          :else
          (do (flush-buffered-mods! state)
              (bridge/emit! code value))))

      ;; Non-modifier key release
      release?
      (if (handle-remap-release! state code)
        nil ;; handled
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
  (swap! kbd-state assoc :remap-mods (compute-remap-mods config)))

(defn update-config! [f & args]
  (let [new-config (apply f (:config @fsm-state) args)]
    (swap! fsm-state assoc :config new-config)
    (apply-config! new-config)))

;; ----------------------------------------------------------------
;; Entry point
;; ----------------------------------------------------------------

(defn stop! []
  (when @nrepl-server
    (nrepl/stop-server! @nrepl-server)
    (reset! nrepl-server nil))
  (bridge/disconnect!)
  (bridge/stop-bridge!))

(defn start!
  [& {:keys [config-path socket-path nrepl-port]
      :or {nrepl-port 7890}}]
  (let [config-path (or config-path (default-config-path))
        config (load-config config-path)
        socket-path (or socket-path bridge/default-socket-path)]

    (println "Config:" config-path)
    (reset! fsm-state (fsm/init config))
    (apply-config! config)

    (bridge/start-bridge! {:socket-path socket-path})
    (bridge/connect! on-key-event :socket-path socket-path)

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
