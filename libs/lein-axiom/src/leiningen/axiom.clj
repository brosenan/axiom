(ns leiningen.axiom
  (:require [di.core :as di]
            [axiom-clj.core :as axiom]))


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
    (di/shutdown $)))

(defn run
  "Run an Axiom instance"
  [project]
  (let [$ (axiom/injector (:axiom-config project))]
    (di/startup $)
    (prn (-> @$ :resources keys sort))
    (while true
      (Thread/sleep 100))))

(defn axiom
  "Automating common Axiom tasks"
  {:subtasks [#'deploy #'run]}
  [project subtask & args]
  (let [task ({"deploy" deploy
               "run" run} subtask)]
    (apply task project args)))
