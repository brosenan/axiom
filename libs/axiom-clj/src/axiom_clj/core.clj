(ns axiom-clj.core
  (:require [di.core :as di]
            [dynamo.core]
            [gateway.core]
            [migrator.core]
            [rabbit-microservices.core]
            [s3.core]
            [storm.core :as storm]
            [zk-plan.core]
            [clojure.core.async :as async]
            [clojure.edn :as edn])
  (:gen-class))

(defn injector [config]
  (let [modules ['dynamo.core/module
                 'gateway.core/module
                 'migrator.core/module
                 'rabbit-microservices.core/module
                 's3.core/module
                 'zk-plan.core/module]
        config (assoc config :modules modules)
        $ (di/injector config)]
    (doseq [module modules]
      ((eval module) $))
    (storm/module $ config)
    $))

(defn run [config keep]
  (let [$ (injector config)]
    (println "Starting up...")
    (di/startup $)
    (prn (-> @$ :resources keys sort))
    (println "Ready to roll.  Use Ctrl+C to stop.")
    (async/<!! keep)
    (println "Shutting down...")
    (di/shutdown $)
    (println "Have a nice day :-)")))

(defn -main [config-file]
  (let [config (-> config-file slurp edn/read-string)
        keep (async/chan 1)]
    (-> (Runtime/getRuntime)
        (.addShutdownHook (Thread. #(async/>!! keep []))))
    (run config keep)))
