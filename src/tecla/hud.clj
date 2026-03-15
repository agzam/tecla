(ns tecla.hud
  "Which-key overlay via GTK4 (gjs). Polls FSM state on its own thread.
   Uses gtk4-layer-shell on wlr compositors, plain GTK4 window on GNOME.
   Completely decoupled from key processing - can never interfere."
  (:require [clojure.string :as str]
            [babashka.process :as proc]))

(def ^:private hud-script
  (str (System/getProperty "user.dir") "/hud/tecla-hud-gtk.js"))

(defonce ^:private hud-proc (atom nil))
(defonce ^:private poller-running (atom false))

;; ----------------------------------------------------------------
;; IPC: write JSON lines to gjs stdin
;; ----------------------------------------------------------------

(defn- send! [json-str]
  (when-let [p @hud-proc]
    (when (.isAlive ^java.lang.Process (:proc p))
      (try
        (let [out ^java.io.OutputStream (:in p)]
          (.write out (.getBytes (str json-str "\n") "UTF-8"))
          (.flush out))
        (catch Throwable _)))))

(defn- key-label
  "Human-readable label for a menu key: :b -> \"b\", [:shift :b] -> \"shift+b\"."
  [k]
  (if (vector? k)
    (str/join "+" (map name k))
    (name k)))

(defn- menu->keys-json [menu]
  (let [items (keep (fn [[k entry]]
                      (when (and (map? entry) (:desc entry))
                        (str "{\"key\":\"" (key-label k) "\""
                             ",\"desc\":\"" (:desc entry) "\"}")))
                    menu)]
    (str "[" (str/join "," items) "]")))

(defn- show-menu! [menu]
  (let [title (or (:desc (meta menu)) "")
        keys-json (menu->keys-json menu)]
    (send! (str "{\"show\":true,\"name\":\"" title
                "\",\"keys\":" keys-json "}"))))

(defn- hide-menu! []
  (send! "{\"show\":false}"))

;; ----------------------------------------------------------------
;; Poller
;; ----------------------------------------------------------------

(defn- start-poller! [fsm-state-atom current-menu-fn]
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
                      (let [menu (current-menu-fn s)]
                        (show-menu! menu)
                        (reset! prev {:mode mode :stack-id stack-id}))

                      ;; Left modal
                      (and (= :modal (:mode p)) (not= :modal mode))
                      (do (hide-menu!)
                          (reset! prev {:mode mode :stack-id nil}))

                      ;; No change
                      :else nil))
                  (catch Throwable _))
                (Thread/sleep 50)))))
    (.setDaemon true)
    (.start)))

;; ----------------------------------------------------------------
;; Lifecycle
;; ----------------------------------------------------------------

(defn kill! []
  (when-let [p @hud-proc]
    (try (send! "{\"quit\":true}") (catch Throwable _))
    (try (.destroyForcibly ^java.lang.Process (:proc p)) (catch Throwable _))
    (reset! hud-proc nil)))

(defn stop! []
  (reset! poller-running false)
  (try (hide-menu!) (catch Throwable _))
  (kill!))

(defn start!
  "Start the GTK HUD process and poller thread. Watches fsm-state,
   sends show/hide commands. Never touches the key pipeline."
  [fsm-state-atom current-menu-fn]
  (kill!)
  (let [p (proc/process {:in :pipe :out :inherit :err :inherit}
                        "gjs" hud-script)]
    (reset! hud-proc p)
    (Thread/sleep 300))
  (start-poller! fsm-state-atom current-menu-fn))
