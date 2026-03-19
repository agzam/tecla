(ns tecla.emacs
  "Edit-with-Emacs: edit text from any app using Emacs.

   Flow:
   1. User presses Super+Ctrl+O
   2. Tecla copies text via Ctrl+C, waits for clipboard
   3. Calls emacsclient to open an edit buffer
   4. User edits in Emacs, then:
      - C-c C-c (finish): text is pasted back, session ends
      - C-c C-s (sync):   text is pasted back, Emacs regains focus
      - C-c C-k (cancel): returns to app, no changes"
  (:require [tecla.bridge :as bridge]
            [tecla.wm :as wm]
            [clojure.java.shell :refer [sh]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ----------------------------------------------------------------
;; Sessions
;; ----------------------------------------------------------------

(defonce sessions (atom {}))
(defonce ^:private session-counter (atom 0))

(defn- gen-session-id []
  (let [n (swap! session-counter inc)]
    (str "ewe-" n "-" (System/currentTimeMillis))))

;; ----------------------------------------------------------------
;; Clipboard helpers (Wayland only - wl-copy / wl-paste)
;; ----------------------------------------------------------------

(defn- clipboard-get
  "Read clipboard via wl-paste. Returns string or nil."
  []
  (try
    (let [{:keys [exit out]} (sh "wl-paste" "--no-newline")]
      (when (zero? exit) out))
    (catch Exception _ nil)))

(defn- clipboard-set
  "Write text to clipboard via wl-copy."
  [text]
  (try
    (let [proc (.start (ProcessBuilder. ["wl-copy"]))]
      (with-open [w (io/writer (.getOutputStream proc))]
        (.write w ^String text))
      (.waitFor proc 3 java.util.concurrent.TimeUnit/SECONDS))
    (catch Exception e
      (println "clipboard-set error:" (.getMessage e)))))

(defn- clipboard-clear!
  "Clear the clipboard by copying an empty string."
  []
  (try
    (let [proc (.start (ProcessBuilder. ["wl-copy" "--clear"]))]
      (.waitFor proc 2 java.util.concurrent.TimeUnit/SECONDS))
    (catch Exception _ nil)))

(defn- wait-for-clipboard-content
  "Poll wl-paste until non-empty content appears, or retries exhausted.
   Returns the content string or nil."
  [& {:keys [retries interval-ms] :or {retries 20 interval-ms 50}}]
  (loop [n 0]
    (when (< n retries)
      (Thread/sleep interval-ms)
      (let [content (clipboard-get)]
        (if (and content (not (str/blank? content)))
          content
          (recur (inc n)))))))

;; ----------------------------------------------------------------
;; Emacsclient
;; ----------------------------------------------------------------

(defn- escape-elisp-string
  "Escape a string for embedding in an elisp string literal."
  [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")))

(defn- run-emacsclient!
  "Ensure tecla.el is loaded, then evaluate ELISP via emacsclient.
   Blocks up to 5s."
  [elisp]
  (try
    (let [tecla-dir (escape-elisp-string (System/getProperty "user.dir"))
          expr (str "(progn"
                    " (add-to-list 'load-path \"" tecla-dir "\")"
                    " (require 'tecla)"
                    " " elisp ")")
          proc (.start (ProcessBuilder. ["emacsclient" "-e" expr]))]
      (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS))
    (catch Exception e
      (println "emacsclient failed:" (.getMessage e)))))

;; ----------------------------------------------------------------
;; Window focusing
;; ----------------------------------------------------------------

(defn- focus-app!
  "Focus the originating app window."
  [session]
  (try
    (if-let [addr (:address session)]
      (wm/focus-window-by-address addr)
      (when-let [cls (:class session)]
        (wm/focus-or-launch {:class cls})))
    (catch Exception e
      (println "focus-app! error:" (.getMessage e)))))

(defn- focus-emacs! []
  (try
    (wm/focus-or-launch {:class "Emacs" :command "emacs"})
    (catch Exception _)))

;; ----------------------------------------------------------------
;; Session lifecycle
;; ----------------------------------------------------------------

(defn- build-session
  "Create a session and open Emacs for editing."
  [session-id window had-selection clipboard-text]
  (let [session {:class         (:class window)
                 :title         (:title window)
                 :address       (:address window)
                 :has-selection  had-selection
                 :original       clipboard-text}]
    (swap! sessions assoc session-id session)
    (println "EWE session" session-id
             ": class=" (:class window)
             "sel=" had-selection
             "len=" (count clipboard-text))
    (let [title-esc (escape-elisp-string (or (:title window) "unknown"))
          class-esc (escape-elisp-string (or (:class window) ""))
          text-esc  (escape-elisp-string (or clipboard-text ""))
          elisp (str "(tecla-edit-with-emacs "
                     "\"" class-esc "\" "
                     "\"" title-esc "\" "
                     "\"" session-id "\" "
                     (if had-selection "t" "nil") " "
                     "\"" text-esc "\""
                     ")")]
      (run-emacsclient! elisp)
      (focus-emacs!))))

(defn edit-with-emacs
  "Start an edit-with-emacs session.
   Clears clipboard, sends Ctrl+C, polls for new content.
   Called from config as (emacs/edit-with-emacs)."
  []
  (let [window     (try (wm/focused-window) (catch Exception _ nil))
        session-id (gen-session-id)]
    ;; Small delay for focus restoration after modal exit
    (Thread/sleep 50)
    ;; Clear clipboard, send Ctrl+C, wait for content to appear
    (clipboard-clear!)
    (bridge/emit-combo! [:left-ctrl] :c)
    (let [text (wait-for-clipboard-content)]
      (if text
        (build-session session-id window true text)
        ;; No selection - try Ctrl+A, Ctrl+C
        (do
          (clipboard-clear!)
          (bridge/emit-combo! [:left-ctrl] :a)
          (Thread/sleep 30)
          (bridge/emit-combo! [:left-ctrl] :c)
          (let [text2 (wait-for-clipboard-content)]
            (if text2
              (build-session session-id window false text2)
              (println "EWE: could not capture text from"
                       (:class window)))))))))

;; ----------------------------------------------------------------
;; IPC handlers (called from Emacs via nREPL)
;; ----------------------------------------------------------------

(defn sync-text
  "Push text back to the originating app without ending the session.
   Switches to app, pastes, switches back to Emacs."
  [session-id text]
  (when-let [session (get @sessions session-id)]
    (clipboard-set text)
    (focus-app! session)
    (Thread/sleep 100)
    (if (:has-selection session)
      (bridge/emit-combo! [:left-ctrl] :v)
      (do (bridge/emit-combo! [:left-ctrl] :a)
          (Thread/sleep 30)
          (bridge/emit-combo! [:left-ctrl] :v)))
    ;; After first sync, selection is replaced - future syncs replace all
    (swap! sessions assoc-in [session-id :has-selection] false)
    (Thread/sleep 150)
    (focus-emacs!)
    "ok"))

(defn finish-session
  "Write final text to the originating app and end the session."
  [session-id text]
  (when-let [session (get @sessions session-id)]
    (clipboard-set text)
    (focus-app! session)
    (Thread/sleep 100)
    (if (:has-selection session)
      (bridge/emit-combo! [:left-ctrl] :v)
      (do (bridge/emit-combo! [:left-ctrl] :a)
          (Thread/sleep 30)
          (bridge/emit-combo! [:left-ctrl] :v)))
    (swap! sessions dissoc session-id)
    "ok"))

(defn cancel-session
  "Cancel editing - return to the originating app without changes."
  [session-id]
  (when-let [session (get @sessions session-id)]
    (focus-app! session)
    (swap! sessions dissoc session-id)
    "ok"))
