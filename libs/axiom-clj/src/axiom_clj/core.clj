(ns axiom-clj.core
  (:require [di.core :as di]
            [dynamo.core]
            [migrator.core]
            [rabbit-microservices.core]
            [s3.core]
            [storm.core :as storm]
            [zk-plan.core]
            [clojure.core.async :as async]))

(defn injector [config]
  (let [modules ['dynamo.core/module
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
    (di/startup $)
    (async/<!! keep)
    (di/shutdown $)))
