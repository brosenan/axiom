(ns leiningen.axiom
  (:require [di.core :as di]
            [axiom-clj.core :as axiom]))


(defn deploy
  "Deploy the contents of the project to the configured Axiom instance"
  [project]
  (let [ver (str "dev-" (rand-int 10000000))
        $ (axiom/injector (:axiom-config project))]
    (di/startup $)
    (di/do-with! $ [deploy-dir publish]
                 (deploy-dir ver "." publish))
    (di/shutdown $)))

(defn run
  "Run an Axiom instance"
  [])

(defn axiom
  "Automating common Axiom tasks"
  {:subtasks [#'deploy #'run]}
  [project subtask & args]
  (apply deploy project args))
