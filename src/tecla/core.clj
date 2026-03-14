(ns tecla.core
  "Entry point. Loads config, starts bridge, runs FSM, starts nREPL."
  (:require [tecla.bridge :as bridge]
            [tecla.fsm :as fsm]
            [tecla.keys :as keys]
            [babashka.nrepl.server :as nrepl]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]))

(defonce fsm-state (atom nil))
(defonce nrepl-server (atom nil))

;; ----------------------------------------------------------------
;; Action execution
;; ----------------------------------------------------------------

(defmulti execute-action
  "Execute an action. Dispatches on action type."
  (fn [action] (cond
                 (string? action)  :shell
                 (fn? action)      :fn
                 (keyword? action) :keyword
                 (vector? action)  :vector
                 :else             :unknown)))

(defmethod execute-action :shell [cmd]
  (future (sh "sh" "-c" cmd)))

(defmethod execute-action :fn [f]
  (future (f)))

(defmethod execute-action :vector [v]
  (let [[type & args] v]
    (case type
      :key (let [[mods key] args
                 mod-bits (keys/mods->bits mods)
                 key-code (get keys/key->code key)]
             ;; Press mods, press key, release key, release mods
             (doseq [m mods]
               (when-let [code (get keys/key->code
                                    (keyword (str "left-" (name m))))]
                 (bridge/emit-key! code 1)))
             (bridge/emit-key! key-code 1)
             (bridge/emit-key! key-code 0)
             (doseq [m (reverse mods)]
               (when-let [code (get keys/key->code
                                    (keyword (str "left-" (name m))))]
                 (bridge/emit-key! code 0)))))))

(defmethod execute-action :keyword [k]
  (println "Action registry lookup not yet implemented:" k))

(defmethod execute-action :unknown [a]
  (println "Unknown action type:" (pr-str a)))

;; ----------------------------------------------------------------
;; Effect handler
;; ----------------------------------------------------------------

(defn handle-effect [effect]
  (when effect
    (case (:type effect)
      :enter-modal  nil ;; bridge auto-enters modal on trigger
      :exit-modal   (bridge/exit-modal!)
      :action       (execute-action (:action effect))
      :action-and-exit (do (execute-action (:action effect))
                           (bridge/exit-modal!))
      (println "Unknown effect:" (pr-str effect)))))

;; ----------------------------------------------------------------
;; Bridge event handler
;; ----------------------------------------------------------------

(defn on-bridge-event [event]
  (let [new-state (swap! fsm-state fsm/process-event event)]
    (handle-effect (:effect new-state))
    ;; Clear effect after handling
    (swap! fsm-state assoc :effect nil)))

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

(defn apply-config!
  "Send config to the bridge."
  [config]
  ;; Set trigger
  (when-let [{:keys [mods key]} (:trigger config)]
    (bridge/set-trigger! mods key))

  ;; Set remaps
  (bridge/clear-remaps!)
  (doseq [[from to] (:remaps config)]
    (let [from-mods (if (vector? from) (butlast from) [])
          from-key  (if (vector? from) (last from) from)
          to-mods   (if (vector? to) (butlast to) [])
          to-key    (if (vector? to) (last to) to)]
      (bridge/add-remap! [from-mods from-key] [to-mods to-key]))))

(defn update-config!
  "Update config with a function, re-apply to bridge."
  [f & args]
  (let [new-config (apply f (get-in @fsm-state [:config]) args)]
    (swap! fsm-state assoc :config new-config)
    (apply-config! new-config)))

;; ----------------------------------------------------------------
;; Entry point
;; ----------------------------------------------------------------

(defn start!
  "Start tecla. Options:
     :config-path  - path to config.edn
     :device       - keyboard device name/path
     :socket-path  - bridge socket path
     :nrepl-port   - nREPL port (default 7888)
     :debug?       - enable bridge debug logging"
  [& {:keys [config-path device socket-path nrepl-port debug?]
      :or {nrepl-port 7888}}]
  (let [config-path (or config-path (default-config-path))
        config (load-config config-path)]

    (println "Loaded config from" config-path)

    ;; Initialize FSM
    (reset! fsm-state (fsm/init config))

    ;; Start bridge process
    (bridge/start-bridge! {:device device
                           :socket-path (or socket-path bridge/default-socket-path)
                           :debug? debug?})

    ;; Connect to bridge
    (bridge/connect! on-bridge-event
                     (when socket-path {:socket-path socket-path}))

    ;; Apply config (remaps + trigger)
    (apply-config! config)

    ;; Start nREPL
    (let [srv (nrepl/start-server! {:host "127.0.0.1" :port nrepl-port})]
      (reset! nrepl-server srv)
      (println "nREPL server on port" nrepl-port))

    (println "tecla ready.")

    ;; Block main thread
    @(promise)))

(defn stop! []
  (when @nrepl-server
    (nrepl/stop-server! @nrepl-server)
    (reset! nrepl-server nil))
  (bridge/disconnect!))
