(ns tecla.hud
  "Which-key overlay using eww. Shows available bindings when modal is active.
   All operations are fire-and-forget - HUD errors never block key processing."
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

(def eww-config-dir
  (str (System/getProperty "user.dir") "/eww"))

(defn- eww!
  "Run eww command synchronously. Returns nil on error."
  [& args]
  (try (apply sh "eww" "--config" eww-config-dir args) nil
       (catch Exception _ nil)))

(defn- menu->json [menu]
  (let [items (keep (fn [[k entry]]
                      (when (:desc entry)
                        (str "{\"key\":\"" (name k) "\""
                             ",\"desc\":\"" (:desc entry) "\"}")))
                    menu)]
    (str "[" (str/join "," items) "]")))

(defn kill! []
  (try (sh "eww" "--config" eww-config-dir "kill")
       (catch Exception _)))

(defn warm!
  "Pre-render the widget so the first real show! is instant.
   The first eww command auto-starts the daemon."
  [root-menu]
  (let [keys-json (menu->json root-menu)]
    ;; First call auto-starts daemon (slow), subsequent calls are fast
    (eww! "update" (str "current-modal-keys=" keys-json))
    (eww! "update" (str "current-modal-name="))
    (eww! "open" "modal")
    (Thread/sleep 300)
    (eww! "close" "modal")))

(defn show! [title menu]
  (let [keys-json (menu->json menu)]
    (eww! "close" "modal")
    (eww! "update" (str "current-modal-name=" (or title "")))
    (eww! "update" (str "current-modal-keys=" keys-json))
    (eww! "open" "modal")))

(defn hide! []
  (eww! "close" "modal"))
