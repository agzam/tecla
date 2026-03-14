(ns tecla.hud
  "Which-key overlay using eww. Polls FSM state on its own thread.
   Completely decoupled from key processing - can never interfere."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [babashka.process :as proc]))

(def eww-config-dir
  (str (System/getProperty "user.dir") "/eww"))

(defn- eww! [& args]
  (try (apply sh "eww" "--config" eww-config-dir args) nil
       (catch Throwable _ nil)))

(defn- menu->json [menu]
  (let [items (keep (fn [[k entry]]
                      (when (and (map? entry) (:desc entry))
                        (str "{\"key\":\"" (name k) "\""
                             ",\"desc\":\"" (:desc entry) "\"}")))
                    menu)]
    (str "[" (str/join "," items) "]")))

(defn kill! []
  (try (sh "eww" "--config" eww-config-dir "kill")
       (catch Throwable _)))

(defn- daemon-ready? []
  (try
    (zero? (:exit (sh "eww" "--config" eww-config-dir "get" "current-modal-name")))
    (catch Throwable _ false)))

(defn init!
  "Start the eww daemon and wait until it's ready."
  []
  ;; Spawn daemon in background (don't use sh - it blocks)
  (try
    (proc/process {:out :inherit :err :inherit}
                  "eww" "--config" eww-config-dir "daemon")
    (catch Throwable _))
  ;; Wait for daemon to be responsive
  (loop [n 0]
    (when (and (< n 30) (not (daemon-ready?)))
      (Thread/sleep 200)
      (recur (inc n))))
  ;; Set initial empty values
  (eww! "update" "current-modal-name=")
  (eww! "update" "current-modal-keys=[]"))

(defonce ^:private poller-running (atom false))

(defn start!
  "Start the HUD poller. Watches fsm-state on its own thread,
   updates eww when modal state changes. Never touches the key pipeline."
  [fsm-state-atom current-menu-fn]
  (reset! poller-running true)
  (doto (Thread.
          (fn []
            (let [prev (atom {:mode :idle :stack-id nil})]
              (while @poller-running
                (try
                  (let [s @fsm-state-atom
                        mode (:mode s)
                        stack (:menu-stack s)
                        stack-id (System/identityHashCode stack)
                        p @prev]
                    (cond
                      ;; Entered modal or menu changed
                      (and (= :modal mode)
                           (or (not= :modal (:mode p))
                               (not= stack-id (:stack-id p))))
                      (let [menu (current-menu-fn s)
                            title (or (:desc (meta menu)) "")
                            json (menu->json menu)]
                        (eww! "close" "modal")
                        (eww! "update" (str "current-modal-name=" title))
                        (eww! "update" (str "current-modal-keys=" json))
                        (eww! "open" "modal")
                        (reset! prev {:mode mode :stack-id stack-id}))

                      ;; Left modal
                      (and (= :modal (:mode p)) (not= :modal mode))
                      (do (eww! "close" "modal")
                          (reset! prev {:mode mode :stack-id nil}))

                      ;; No change
                      :else nil))
                  (catch Throwable _))
                (Thread/sleep 50)))))
    (.setDaemon true)
    (.start)))

(defn stop! []
  (reset! poller-running false)
  (eww! "close" "modal"))
