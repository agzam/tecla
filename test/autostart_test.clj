#!/usr/bin/env bb

;; The setup:gnome-autostart task, run against a scratch XDG_CONFIG_HOME.
;; Usage: bb test/autostart_test.clj

(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str])

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

(println "\n=== setup:gnome-autostart tests ===\n")

(def config-home (str (fs/create-temp-dir {:prefix "tecla-autostart-"})))
(def entry-file (str config-home "/autostart/tecla.desktop"))

(p/shell {:extra-env {"XDG_CONFIG_HOME" config-home} :out :string}
         "bb" "setup:gnome-autostart")

(def entry
  (into {} (keep #(when-let [[_ k v] (re-matches #"([^=\[]+)=(.*)" %)] [k v]))
        (str/split-lines (slurp entry-file))))

(deftest "entry runs bb in the repo directory"
  (let [[exe & args] (str/split (get entry "Exec" "") #" ")]
    (assert= true (fs/executable? exe) (str "executable " exe))
    (assert= ["run"] args "arguments")
    (assert= true (fs/exists? (fs/path (get entry "Path") "bb.edn")) "bb.edn in Path")))

(deftest "entry starts on GNOME only"
  (assert= "GNOME;" (get entry "OnlyShowIn") "OnlyShowIn"))

(deftest "entry passes desktop-file-validate"
  (when (fs/which "desktop-file-validate")
    (let [r (p/shell {:continue true :out :string :err :string}
                     "desktop-file-validate" entry-file)]
      (assert= "" (str (:out r) (:err r)) "validator output")
      (assert= 0 (:exit r) "validator exit code"))))

(fs/delete-tree config-home)

(println)
(let [{:keys [passed failed errors]} @test-results]
  (println (format "Results: %d passed, %d failed" passed failed))
  (when (seq errors)
    (println "\nFailures:")
    (doseq [{:keys [test msg]} errors] (println (str "  " test ": " msg))))
  (System/exit (if (zero? failed) 0 1)))
