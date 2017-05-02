(ns migrator.core
  (:require [permacode.core :as perm]
            [di.core :as di]
            [clojure.core.async :as async]
            [zookeeper :as zk]
            [zk-plan.core :as zkp]
            [clojure.string :as str]))

(defn module [$]
  (di/provide zookeeper-counter-add $
              (di/with-dependencies $ [zookeeper]
                (fn zookeeper-counter-add
                  ([path change retries]
                   (let [exists (zk/exists zookeeper path)]
                     (cond exists
                           (let [old (zkp/get-clj-data zookeeper path)
                                 new (+ old change)
                                 new-bin (zkp/to-bytes (pr-str new))]
                             (try
                               (zk/set-data zookeeper path new-bin (:version exists))
                               new
                               (catch Throwable e
                                 (cond (> retries 1)
                                       (zookeeper-counter-add path change (dec retries))
                                       :else
                                       (throw e)))))
                           :else
                           (do
                             (zk/create zookeeper path :persistent? true)
                             (zkp/set-initial-clj-data zookeeper path change)
                             change))))
                  ([path change]
                   (zookeeper-counter-add path change 3)))))

  (async/go
    (di/with-dependencies $ [serve]
      (serve (fn extract-version-rules
               [ev publish]
               (let [pubs (perm/module-publics (symbol (:key ev)))]
                 (doseq [[k v] pubs]
                   (when (-> v meta :source-fact)
                     (publish {:name "axiom/rule"
                               :key (symbol (:key ev) (str k))})))))

             {:kind :fact
              :name "axiom/version"})))
  (async/go
    (di/with-dependencies $ [zookeeper-counter-add
                             declare-service
                             assign-service]
      (declare-service "migrator.core/rule-tracker" {:kind :fact :name "axiom/rule"})
      (assign-service "migrator.core/rule-tracker"
                      (fn rule-tracker
                        [ev publish]
                        (let [path (str "/rules/" (str/replace (:key ev) \/ \.))
                              new (zookeeper-counter-add path (:change ev))]
                          (when (and (= new (:change ev))
                                     (> new 0))
                            (publish {:kind :fact
                                      :name "axiom/rule-exists"
                                      :key (:key ev)
                                      :change 1}))
                          (when (and (= new 0)
                                     (< (:change ev) 0))
                            (publish {:kind :fact
                                      :name "axiom/rule-exists"
                                      :key (:key ev)
                                      :change -1}))
                          nil))))))
