(ns migrator.core
  (:require [permacode.core :as perm]
            [di.core2 :as di]
            [clojure.core.async :as async]
            [zookeeper :as zk]
            [zk-plan.core :as zkp]
            [clojure.string :as str]
            [cloudlog-events.core :as ev]
            [cloudlog.core :as clg]))

(defn fact-declarer [rule link]
  (fn [$ & args]
    (di/do-with! $ [declare-service]
      (let [rulefunc (perm/eval-symbol rule)]
        (declare-service (str "fact-for-rule/" rule "!" link) {:kind :fact
                                                               :name (clg/fact-table (-> rulefunc meta :source-fact))}))
      :not-nil)
    nil))

(defn initial-migrator [rule writers shard shards]
  (fn [$ & args]
    (di/do-with! $ [database-scanner
                               database-event-storage-chan]
      (let [rule (perm/eval-symbol rule)
            em (ev/emitter rule writers)
            inp (async/chan)]
        (async/thread
          (database-scanner (-> rule meta :source-fact clg/fact-table) shard shards inp))
        (loop []
          (let [ev (async/<!! inp)]
            (when-not (nil? ev)
              (let [out (em ev)]
                (doseq [ev out]
                  (async/>!! database-event-storage-chan [ev (async/chan)])))
              (recur)))))
      :not-nil)
    nil))

(defn link-migrator [rule link writers shard shards]
  (fn [$ & args]
    (di/do-with! $ [database-chan
                               database-scanner]
      (let [rule (perm/eval-symbol rule)
            matcher (ev/matcher rule link database-chan)
            scan-chan (async/chan)]
        (async/thread
          (database-scanner (ev/source-fact-table rule link) shard shards scan-chan))
        (loop []
          (let [ev (async/<!! scan-chan)
                out-chan (async/chan)]
            (when-not (nil? ev)
              (matcher ev out-chan)
              (di/do-with! $ [database-event-storage-chan]
                (loop []
                  (let [ev (async/<!! out-chan)]
                    (when-not (nil? ev)
                      (async/>!! database-event-storage-chan [ev (async/chan)])
                      ;; TODO: wait for acks before completing
                      (recur))))
                :not-nil)
              (recur)))))
      :not-nil)

    nil))

(defn migration-end-notifier [rule writers]
  (fn [$ & args]
    (di/do-with! $ [publish]
      (publish {:kind :fact
                :name "axiom/rule-ready"
                :key rule
                :data []
                :ts (.getTime (java.util.Date.))
                :change 1
                :writers writers})
      :not-nil)))

(defn module [$]
  (di/provide $ zookeeper-counter-add [zookeeper]
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
                 (zookeeper-counter-add path change 3))))

  (di/do-with $ [serve]
    (serve (fn extract-version-rules
             [ev publish]
             (let [pubs (perm/module-publics (symbol (:key ev)))]
               (doseq [[k v] pubs]
                 (when (-> v meta :source-fact)
                   (publish {:kind :fact
                             :name "axiom/rule"
                             :key (symbol (:key ev) (str k))
                             :data []})))))

           {:kind :fact
            :name "axiom/version"}))
  (di/do-with $ [zookeeper-counter-add
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
                                              :data []
                                              :change 1}))
                                  (when (and (= new 0)
                                             (< (:change ev) 0))
                                    (publish {:kind :fact
                                              :name "axiom/rule-exists"
                                              :key (:key ev)
                                              :data []
                                              :change -1}))
                                  nil))))
  (di/do-with $ [zk-plan
                 declare-service
                 assign-service
                 migration-config]
              (declare-service "migrator.core/rule-migrator" {:kind :fact
                                                              :name "axiom/rule-exists"})
              (assign-service "migrator.core/rule-migrator"
                              (fn [ev]
                                (let [rule (:key ev)
                                      rulefunc (perm/eval-symbol rule)
                                      shards (:number-of-shards migration-config)
                                      {:keys [create-plan add-task mark-as-ready]} zk-plan
                                      writers (:writers ev)
                                      plan (create-plan (:plan-prefix migration-config))]
                                  (loop [rulefunc rulefunc
                                         link 0
                                         deps []]
                                    (cond (nil? rulefunc)
                                          (add-task plan `(migration-end-notifier '~rule ~writers) deps)
                                          :else
                                          (let [singleton-task (add-task plan `(fact-declarer '~rule ~link) deps)
                                                tasks (for [shard (range shards)]
                                                        (add-task plan
                                                                  (cond (= link 0)
                                                                        `(initial-migrator '~rule ~writers ~shard ~shards)
                                                                        :else
                                                                        `(link-migrator '~rule ~link ~writers ~shard ~shards))
                                                                  [singleton-task]))]
                                            (recur (-> rulefunc meta :continuation) (inc link) (doall tasks)))))
                                  (mark-as-ready plan))
                                nil))))
