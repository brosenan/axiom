(ns axiom-clj.core
  (:require [di.core :as di]
            [dynamo.core :as dynamo]
            [migrator.core :as migrator]
            [rabbit-microservices.core :as rabbit]
            [s3.core :as s3]
            [storm.core :as storm]
            [zk-plan.core :as zkp]
            [clojure.core.async :as async]))

(defn injector [config]
  (let [$ (di/injector config)]
    (dynamo/module $)
    (migrator/module $)
    (rabbit/module $)
    (s3/module $)
    (storm/module $ config)
    (zkp/module $)
    $))

(defn run [config keep]
  (let [$ (injector config)]
    (di/startup $)
    (async/<!! keep)
    (di/shutdown $)))
