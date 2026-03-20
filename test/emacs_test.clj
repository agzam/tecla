#!/usr/bin/env bb

;; Tests for the edit-with-emacs feature (tecla.emacs).
;; Mocks clipboard, bridge, wm, and emacsclient to test the
;; session lifecycle, text capture flow, and IPC handlers.

(require '[tecla.emacs :as emacs]
         '[tecla.bridge :as bridge]
         '[tecla.wm :as wm]
         '[clojure.string :as str])

;; ----------------------------------------------------------------
;; Test framework (same hand-rolled approach as other test files)
;; ----------------------------------------------------------------

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
    (throw (ex-info (str msg " - expected: " (pr-str expected)
                         ", got: " (pr-str actual)) {}))))
(defn assert-true [actual msg]
  (when-not actual
    (throw (ex-info (str msg " - expected truthy, got: " (pr-str actual)) {}))))
(defn assert-nil [actual msg]
  (when (some? actual)
    (throw (ex-info (str msg " - expected nil, got: " (pr-str actual)) {}))))

;; ----------------------------------------------------------------
;; Mock state
;; ----------------------------------------------------------------

(def mock-clipboard (atom nil))
(def mock-combos (atom []))
(def mock-focus-calls (atom []))
(def mock-emacsclient-calls (atom []))
(def mock-clipboard-responses (atom []))

;; ----------------------------------------------------------------
;; Install mocks
;; ----------------------------------------------------------------

;; Bridge
(alter-var-root #'bridge/emit-combo!
                (constantly (fn [mods key]
                              (swap! mock-combos conj {:mods mods :key key}))))
(alter-var-root #'bridge/emit! (constantly (fn [_ _] nil)))

;; WM
(def mock-focused-window
  (fn [& _] {:class "test-app" :title "Test Window" :address "0x123"}))
(alter-var-root #'wm/focused-window (constantly mock-focused-window))
(alter-var-root #'wm/focus-window-by-address
                (constantly (fn [addr]
                              (swap! mock-focus-calls conj {:type :by-address :addr addr}))))
(alter-var-root #'wm/focus-or-launch
                (constantly (fn [opts & _]
                              (let [opts (if (map? opts) opts {:class (str opts)})]
                                (swap! mock-focus-calls conj {:type :focus-or-launch :opts opts})))))

;; Private emacs functions
(alter-var-root (ns-resolve 'tecla.emacs 'clipboard-set)
                (constantly (fn [text] (reset! mock-clipboard text))))
(alter-var-root (ns-resolve 'tecla.emacs 'clipboard-clear!)
                (constantly (fn [] (reset! mock-clipboard nil))))
(alter-var-root (ns-resolve 'tecla.emacs 'run-emacsclient!)
                (constantly (fn [elisp] (swap! mock-emacsclient-calls conj elisp))))
(alter-var-root (ns-resolve 'tecla.emacs 'wait-for-clipboard-content)
                (constantly (fn [& _opts]
                              (let [resp (first @mock-clipboard-responses)]
                                (swap! mock-clipboard-responses #(vec (rest %)))
                                resp))))

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn reset-mocks! []
  (reset! mock-clipboard nil)
  (reset! mock-combos [])
  (reset! mock-focus-calls [])
  (reset! mock-emacsclient-calls [])
  (reset! mock-clipboard-responses [])
  (reset! emacs/sessions {}))

(defn setup-session!
  "Directly create a session in the atom for testing IPC handlers."
  [session-id & {:keys [class title address has-selection original]
                 :or {class "test-app" title "Test Window"
                      address "0x123" has-selection true
                      original "original text"}}]
  (swap! emacs/sessions assoc session-id
         {:class class :title title :address address
          :has-selection has-selection :original original}))

;; ================================================================
(println "\n=== tecla.emacs unit tests ===\n")
;; ================================================================

;; ---- escape-elisp-string ----

(def escape @(ns-resolve 'tecla.emacs 'escape-elisp-string))

(println "-- escape-elisp-string --")

(deftest "escape: plain text unchanged"
  (assert= "hello world" (escape "hello world") "plain"))

(deftest "escape: empty string"
  (assert= "" (escape "") "empty"))

(deftest "escape: backslashes doubled"
  (assert= "a\\\\b" (escape "a\\b") "backslash"))

(deftest "escape: double quotes escaped"
  (assert= "say \\\"hi\\\"" (escape "say \"hi\"") "quotes"))

(deftest "escape: newlines escaped"
  (assert= "line1\\nline2" (escape "line1\nline2") "newlines"))

(deftest "escape: carriage returns escaped"
  (assert= "a\\rb" (escape "a\rb") "cr"))

(deftest "escape: combined special chars"
  (let [result (escape "path\\to\\file\n\"quoted\"\r")]
    (assert-true (str/includes? result "\\\\") "has escaped backslash")
    (assert-true (str/includes? result "\\n") "has escaped newline")
    (assert-true (str/includes? result "\\\"") "has escaped quote")
    (assert-true (str/includes? result "\\r") "has escaped cr")))

;; ---- gen-session-id ----

(def gen-id @(ns-resolve 'tecla.emacs 'gen-session-id))

(println "\n-- gen-session-id --")

(deftest "gen-session-id: returns string"
  (assert-true (string? (gen-id)) "is string"))

(deftest "gen-session-id: starts with ewe-"
  (assert-true (str/starts-with? (gen-id) "ewe-") "prefix"))

(deftest "gen-session-id: unique across calls"
  (let [ids (repeatedly 10 gen-id)]
    (assert= 10 (count (set ids)) "all unique")))

;; ---- cancel-session ----

(println "\n-- cancel-session --")

(deftest "cancel: focuses app by address"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/cancel-session "s1")
  (assert-true (some #(= {:type :by-address :addr "0x123"} %) @mock-focus-calls)
               "focused by address"))

(deftest "cancel: removes session"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/cancel-session "s1")
  (assert-nil (get @emacs/sessions "s1") "session removed"))

(deftest "cancel: returns ok"
  (reset-mocks!)
  (setup-session! "s1")
  (assert= "ok" (emacs/cancel-session "s1") "returns ok"))

(deftest "cancel: returns nil for unknown session"
  (reset-mocks!)
  (assert-nil (emacs/cancel-session "nonexistent") "nil for unknown"))

(deftest "cancel: no clipboard modification"
  (reset-mocks!)
  (reset! mock-clipboard "untouched")
  (setup-session! "s1")
  (emacs/cancel-session "s1")
  (assert= "untouched" @mock-clipboard "clipboard unchanged"))

(deftest "cancel: no key combos emitted"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/cancel-session "s1")
  (assert= [] @mock-combos "no combos"))

;; ---- finish-session ----

(println "\n-- finish-session --")

(deftest "finish: sets clipboard"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/finish-session "s1" "edited text")
  (assert= "edited text" @mock-clipboard "clipboard set"))

(deftest "finish: focuses app"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/finish-session "s1" "edited text")
  (assert-true (some #(= {:type :by-address :addr "0x123"} %) @mock-focus-calls)
               "focused by address"))

(deftest "finish: selection session emits Ctrl+V"
  (reset-mocks!)
  (setup-session! "s1" :has-selection true)
  (emacs/finish-session "s1" "edited text")
  (assert-true (some #(= {:mods [:left-ctrl] :key :v} %) @mock-combos) "Ctrl+V"))

(deftest "finish: full-text session emits Ctrl+A then Ctrl+V"
  (reset-mocks!)
  (setup-session! "s1" :has-selection false)
  (emacs/finish-session "s1" "edited text")
  (let [combos @mock-combos
        a-idx (first (keep-indexed #(when (= {:mods [:left-ctrl] :key :a} %2) %1) combos))
        v-idx (first (keep-indexed #(when (= {:mods [:left-ctrl] :key :v} %2) %1) combos))]
    (assert-true (some? a-idx) "has Ctrl+A")
    (assert-true (some? v-idx) "has Ctrl+V")
    (assert-true (< a-idx v-idx) "A before V")))

(deftest "finish: removes session"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/finish-session "s1" "edited text")
  (assert-nil (get @emacs/sessions "s1") "session removed"))

(deftest "finish: returns ok"
  (reset-mocks!)
  (setup-session! "s1")
  (assert= "ok" (emacs/finish-session "s1" "edited text") "returns ok"))

(deftest "finish: returns nil for unknown session"
  (reset-mocks!)
  (assert-nil (emacs/finish-session "nonexistent" "text") "nil for unknown"))

(deftest "finish: does not focus Emacs"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/finish-session "s1" "edited text")
  (let [emacs-calls (filter #(and (= :focus-or-launch (:type %))
                                  (= "Emacs" (:class (:opts %)))) @mock-focus-calls)]
    (assert= 0 (count emacs-calls) "no Emacs focus")))

;; ---- sync-text ----

(println "\n-- sync-text --")

(deftest "sync: sets clipboard"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/sync-text "s1" "synced text")
  (assert= "synced text" @mock-clipboard "clipboard set"))

(deftest "sync: selection session emits Ctrl+V"
  (reset-mocks!)
  (setup-session! "s1" :has-selection true)
  (emacs/sync-text "s1" "synced text")
  (assert-true (some #(= {:mods [:left-ctrl] :key :v} %) @mock-combos) "Ctrl+V"))

(deftest "sync: full-text session emits Ctrl+A then Ctrl+V"
  (reset-mocks!)
  (setup-session! "s1" :has-selection false)
  (emacs/sync-text "s1" "synced text")
  (let [combos @mock-combos
        a-idx (first (keep-indexed #(when (= {:mods [:left-ctrl] :key :a} %2) %1) combos))
        v-idx (first (keep-indexed #(when (= {:mods [:left-ctrl] :key :v} %2) %1) combos))]
    (assert-true (some? a-idx) "has Ctrl+A")
    (assert-true (some? v-idx) "has Ctrl+V")
    (assert-true (< a-idx v-idx) "A before V")))

(deftest "sync: focuses app then Emacs"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/sync-text "s1" "synced text")
  (let [calls @mock-focus-calls
        app-idx (first (keep-indexed #(when (= :by-address (:type %2)) %1) calls))
        emacs-idx (first (keep-indexed
                          #(when (and (= :focus-or-launch (:type %2))
                                     (= "Emacs" (:class (:opts %2)))) %1) calls))]
    (assert-true (some? app-idx) "app focused")
    (assert-true (some? emacs-idx) "Emacs focused")
    (assert-true (< app-idx emacs-idx) "app before Emacs")))

(deftest "sync: flips has-selection to false after first sync"
  (reset-mocks!)
  (setup-session! "s1" :has-selection true)
  (emacs/sync-text "s1" "first sync")
  (assert= false (get-in @emacs/sessions ["s1" :has-selection]) "now false"))

(deftest "sync: second sync uses Ctrl+A then Ctrl+V"
  (reset-mocks!)
  (setup-session! "s1" :has-selection true)
  (emacs/sync-text "s1" "first sync")
  (reset! mock-combos [])
  (emacs/sync-text "s1" "second sync")
  (let [combos @mock-combos]
    (assert-true (some #(= {:mods [:left-ctrl] :key :a} %) combos) "has Ctrl+A")
    (assert-true (some #(= {:mods [:left-ctrl] :key :v} %) combos) "has Ctrl+V")))

(deftest "sync: keeps session alive"
  (reset-mocks!)
  (setup-session! "s1")
  (emacs/sync-text "s1" "synced text")
  (assert-true (some? (get @emacs/sessions "s1")) "session exists"))

(deftest "sync: returns ok"
  (reset-mocks!)
  (setup-session! "s1")
  (assert= "ok" (emacs/sync-text "s1" "synced text") "returns ok"))

(deftest "sync: returns nil for unknown session"
  (reset-mocks!)
  (assert-nil (emacs/sync-text "nonexistent" "text") "nil for unknown"))

;; ---- edit-with-emacs flow ----

(println "\n-- edit-with-emacs --")

(deftest "ewe: selection captured on first try"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["selected text"])
  (emacs/edit-with-emacs)
  (let [sessions @emacs/sessions
        [_id session] (first sessions)]
    (assert= 1 (count sessions) "one session")
    (assert= true (:has-selection session) "has-selection")
    (assert= "selected text" (:original session) "original text")
    (assert= "test-app" (:class session) "class")
    (assert= "Test Window" (:title session) "title")))

(deftest "ewe: emits Ctrl+C on first try"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["selected text"])
  (emacs/edit-with-emacs)
  (assert-true (some #(= {:mods [:left-ctrl] :key :c} %) @mock-combos) "Ctrl+C emitted"))

(deftest "ewe: calls emacsclient"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["some text"])
  (emacs/edit-with-emacs)
  (assert= 1 (count @mock-emacsclient-calls) "emacsclient called once"))

(deftest "ewe: fallback to Ctrl+A, Ctrl+C when no selection"
  (reset-mocks!)
  (reset! mock-clipboard-responses [nil "full text"])
  (emacs/edit-with-emacs)
  (let [combos @mock-combos
        [_id session] (first @emacs/sessions)]
    (assert-true (some #(= {:mods [:left-ctrl] :key :a} %) combos) "Ctrl+A in fallback")
    (assert= false (:has-selection session) "has-selection=false")
    (assert= "full text" (:original session) "captured full text")))

(deftest "ewe: aborts when both capture attempts fail"
  (reset-mocks!)
  (reset! mock-clipboard-responses [nil nil])
  (emacs/edit-with-emacs)
  (assert= 0 (count @emacs/sessions) "no session created")
  (assert= 0 (count @mock-emacsclient-calls) "no emacsclient call"))

(deftest "ewe: focuses Emacs after buffer creation"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["text"])
  (emacs/edit-with-emacs)
  (assert-true (some #(and (= :focus-or-launch (:type %))
                           (= "Emacs" (:class (:opts %)))) @mock-focus-calls)
               "Emacs focused"))

(deftest "ewe: handles nil window gracefully"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["text"])
  (alter-var-root #'wm/focused-window
                  (constantly (fn [& _] (throw (Exception. "no wm")))))
  (emacs/edit-with-emacs)
  (alter-var-root #'wm/focused-window (constantly mock-focused-window))
  (let [[_id session] (first @emacs/sessions)]
    (assert-nil (:class session) "class is nil")
    (assert= 1 (count @mock-emacsclient-calls) "emacsclient still called")))

;; ---- session state invariants ----

(println "\n-- session state invariants --")

(deftest "sessions start empty"
  (reset-mocks!)
  (assert= {} @emacs/sessions "empty"))

(deftest "multiple sessions can coexist"
  (reset-mocks!)
  (setup-session! "s1" :class "app1")
  (setup-session! "s2" :class "app2")
  (assert= 2 (count @emacs/sessions) "two sessions"))

(deftest "finish removes only its session"
  (reset-mocks!)
  (setup-session! "s1" :class "app1")
  (setup-session! "s2" :class "app2")
  (emacs/finish-session "s1" "text")
  (assert-nil (get @emacs/sessions "s1") "s1 gone")
  (assert-true (some? (get @emacs/sessions "s2")) "s2 remains"))

(deftest "cancel removes only its session"
  (reset-mocks!)
  (setup-session! "s1" :class "app1")
  (setup-session! "s2" :class "app2")
  (emacs/cancel-session "s1")
  (assert-nil (get @emacs/sessions "s1") "s1 gone")
  (assert-true (some? (get @emacs/sessions "s2")) "s2 remains"))

(deftest "rapid re-invocation creates separate sessions"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["text1" "text2"])
  (emacs/edit-with-emacs)
  (emacs/edit-with-emacs)
  (assert= 2 (count @emacs/sessions) "two sessions"))

;; ---- focus-app! fallback ----

(println "\n-- focus-app! fallback --")

(deftest "focus-app: uses address when available"
  (reset-mocks!)
  (setup-session! "s1" :address "0xABC")
  (emacs/cancel-session "s1")
  (assert-true (some #(= {:type :by-address :addr "0xABC"} %) @mock-focus-calls)
               "by address"))

(deftest "focus-app: falls back to class when no address"
  (reset-mocks!)
  (setup-session! "s1" :address nil :class "some-app")
  (emacs/cancel-session "s1")
  (assert-true (some #(and (= :focus-or-launch (:type %))
                           (= "some-app" (:class (:opts %)))) @mock-focus-calls)
               "by class"))

;; ---- emacsclient invocation ----

(println "\n-- emacsclient invocation --")

(deftest "ewe: elisp contains session-id"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["text"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)
        session-id (key (first @emacs/sessions))]
    (assert-true (str/includes? elisp session-id) "has session-id")))

(deftest "ewe: elisp contains captured text"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["hello world"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp "hello world") "has text")))

(deftest "ewe: elisp contains window class"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["text"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp "test-app") "has class")))

(deftest "ewe: selection session passes t for selection-only"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["selected"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp " t ") "selection-only=t")))

(deftest "ewe: full-text session passes nil for selection-only"
  (reset-mocks!)
  (reset! mock-clipboard-responses [nil "full text"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp " nil ") "selection-only=nil")))

;; ---- special chars in text ----

(println "\n-- special chars in text --")

(deftest "ewe: text with quotes survives escaping"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["He said \"hello\""])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp "\\\"hello\\\"") "escaped quotes in elisp")))

(deftest "ewe: text with newlines survives escaping"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["line1\nline2"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp "line1\\nline2") "escaped newlines in elisp")))

(deftest "ewe: text with backslashes survives escaping"
  (reset-mocks!)
  (reset! mock-clipboard-responses ["C:\\Users\\test"])
  (emacs/edit-with-emacs)
  (let [elisp (first @mock-emacsclient-calls)]
    (assert-true (str/includes? elisp "C:\\\\Users\\\\test") "escaped backslashes in elisp")))

;; ================================================================
(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
