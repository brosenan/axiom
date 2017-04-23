(ns migrator.core
  (:require [permacode.core :as perm]
            [di.core :as di]
            [clojure.core.async :as async]
            [zookeeper :as zk]
            [zk-plan.core :as zkp]))

(defn module [$]
  (di/provide zookeeper-counter-add $
              (di/with-dependencies $ [zookeeper]
                (fn [path change]
                  (cond (zk/exists zookeeper path)
                        (let [old (zkp/get-clj-data zookeeper path)
                              new (+ old change)]
                          new)
                        :else
                        (do
                          (zk/create zookeeper path :persistent? true)
                          (zkp/set-initial-clj-data zookeeper path change)
                          change)))))

  (async/go
    (di/with-dependencies $ [serve]
      (serve (fn
               [ev publish]
               (let [pubs (perm/module-publics (symbol (:key ev)))]
                 (doseq [[k v] pubs]
                   (when (-> v meta :source-fact)
                     (publish {:name "axiom/rule"
                               :key (symbol (:key ev) (str k))})))))

             {:kind :fact
              :name "axiom/version"}))))
