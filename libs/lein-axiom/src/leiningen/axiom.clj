(ns leiningen.axiom
  (:require [di.core :as di]
            [axiom-clj.core :as axiom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [permacode.publish :as publish]))


(defn deploy
  "Deploy the contents of the project to the configured Axiom instance"
  [project]
  (let [ver (str "dev-" (rand-int 10000000))
        $ (axiom/injector (-> (:axiom-config project)
                              (dissoc :http-config)
                              (dissoc :migrator-config)))]
    (di/startup $)
    (di/do-with! $ [deploy-dir publish println]
                 (deploy-dir ver "." publish)
                 (println (str "Deployed a new version: " ver)))
    (di/shutdown $)
    (println "Add &_ver=" ver " to your URL")))

(defn run
  "Run an Axiom instance"
  [project]
  (let [$ (axiom/injector (:axiom-config project))]
    (di/startup $)
    (prn (-> @$ :resources keys sort))
    (while true
      (Thread/sleep 100))))

(defn all-source-files [project]
  (for [dir (:source-paths project)
        file (file-seq (io/file dir))
        :when (str/ends-with? (str file) ".clj")]
    file))

(defn required-perms [file]
  (let [[_ns _name & clauses] (publish/get-ns file)]
    (for [[_require & deps] clauses
          [dep & args] deps
          :when (str/starts-with? (str dep) "perm.")]
      (-> dep
          str
          (subs 5)))))

(defn create-perm-file [hashcode [hash unhash] src-dir]
  (let [permdir (io/file src-dir "perm")
        content (unhash hashcode)
        file (io/file permdir (str hashcode ".clj"))]
    (cond (-> file .exists)
          false
          :else
          (do
            (println (str file))
            (-> permdir .mkdirs)
            (with-open [file (io/writer file)]
              (doseq [expr content]
                (.write file (pr-str expr))))
            true))))

(defn deps
  "Recursively add permacode dependencies to this project"
  [project & args]
  (let [$ (axiom/injector (:axiom-config project))
        src-dir (io/file (first (:source-paths project)))]
    (di/startup $)
    (di/do-with! $ [hasher]
                 (while (some identity
                              (for [src (all-source-files project)
                                    dep (required-perms src)]
                                (create-perm-file dep hasher src-dir))))))
  nil)

(defn axiom
  "Automating common Axiom tasks"
  {:subtasks [#'deploy #'run #'deps]}
  [project subtask & args]
  (let [task ({"deploy" deploy
               "run" run
               "deps" deps} subtask)]
    (apply task project args)))

