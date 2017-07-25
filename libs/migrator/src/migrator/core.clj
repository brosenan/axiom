(ns migrator.core
  (:require [permacode.core :as perm]
            [permacode.publish :as permpub]
            [permacode.validate :as permval]
            [di.core :as di]
            [clojure.core.async :as async]
            [zookeeper :as zk]
            [zk-plan.core :as zkp]
            [clojure.string :as str]
            [cloudlog-events.core :as ev]
            [cloudlog.core :as clg]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn fact-declarer [rule link]
  (fn [$ & args]
    (di/do-with! $ [declare-service hasher]
                 (binding [permval/*hasher* hasher]
                   (let [rulefunc (perm/eval-symbol rule)]
                     (declare-service (str "fact-for-rule/" rule "!" link)
                                      {:kind :fact
                                       :name (ev/source-fact-table rulefunc link)}))))
    nil))

(defn initial-migrator [rule-name shard shards]
  (fn [$ & args]
    (di/do-with! $ [database-scanner
                    database-event-storage-chan
                    hasher]
                 (binding [permval/*hasher* hasher]
                   (let [rule (perm/eval-symbol rule-name)
                         em (ev/emitter rule)
                         inp (async/chan)]
                     (async/thread
                       (database-scanner (-> rule meta :source-fact clg/fact-table) shard shards inp))
                     (loop []
                       (let [ev (async/<!! inp)]
                         (when-not (nil? ev)
                           (let [out (em ev)]
                             (doseq [ev out]
                               (async/>!! database-event-storage-chan [ev (async/chan)])))
                           (recur)))))))
    nil))

(defn link-migrator [rule link shard shards]
  (fn [$ & args]
    (di/do-with! $ [database-chan
                    database-scanner
                    hasher]
                 (binding [permval/*hasher* hasher]
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
                                              (recur)))))
                           (recur)))))))
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

(defn extract-version-rules [version]
  (->> (perm/module-publics version)
       (filter (fn [[k v]] (let [src (-> v meta :source-fact)]
                             (and src
                                  (not (str/ends-with? (first src) "?"))))))
       vals))

(defn extract-version-clauses [version]
  (->> (perm/module-publics version)
       (filter (fn [[k v]] (let [src (-> v meta :source-fact)]
                             (and src
                                  (str/ends-with? (first src) "?")))))
       vals))


(defn to-bin-seq [f]
  (let [len (.length f)
        b (byte-array len)]
    (with-open [inp (clojure.java.io/input-stream f)]
      (let [len-read (.read inp b 0 len)]
        (when (not= len-read len)
          (throw (Exception. (str "Problem reading from file " (.getAbsolutePath f) ": expected " len " bytes, but got " len-read))))))
    b))

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
                           (zk/create-all zookeeper path :persistent? true)
                           (zkp/set-initial-clj-data zookeeper path change)
                           change))))
                ([path change]
                 (zookeeper-counter-add path change 3))))
  (di/do-with $ [zookeeper-counter-add
                 declare-service
                 assign-service]
              (declare-service "migrator.core/perm-tracker" {:kind :fact :name "axiom/perm-versions"})
              (assign-service "migrator.core/perm-tracker"
                              (fn perm-tracker
                                [ev publish]
                                (let [ver (-> ev :key)
                                      perms (-> ev :data first vals set)]
                                  (loop [perms (seq perms)
                                         new-perms #{}
                                         removed-perms #{}]
                                    (cond (empty? perms)
                                          (do
                                            (when-not (empty? new-perms)
                                                (publish {:kind :fact
                                                          :name "axiom/perms-exist"
                                                          :key ver
                                                          :data [new-perms]
                                                          :change 1}))
                                            (when-not (empty? removed-perms)
                                              (publish {:kind :fact
                                                        :name "axiom/perms-exist"
                                                        :key ver
                                                        :data [removed-perms]
                                                        :change -1})))
                                          :else
                                          (let [perm (first perms)
                                                path (str "/perms/" perm)
                                                new-val (zookeeper-counter-add path (:change ev))]
                                            (cond (and (= new-val 0)
                                                       (< (:change ev) 0))
                                                  (recur (rest perms) new-perms (conj removed-perms perm))
                                                  (and (= new-val (:change ev))
                                                       (> (:change ev) 0))
                                                  (recur (rest perms) (conj new-perms perm) removed-perms)
                                                  :else
                                                  (recur (rest perms) new-perms removed-perms)))))
                                  nil))))
  (di/do-with $ [zk-plan
                 declare-service
                 assign-service
                 migration-config
                 hasher
                 info]
              (declare-service "migrator.core/rule-migrator" {:kind :fact
                                                              :name "axiom/perms-exist"})
              (assign-service "migrator.core/rule-migrator"
                              (fn [ev]
                                (binding [permval/*hasher* hasher]
                                  (let [gitver (:key ev)
                                        [perms] (:data ev)
                                        ruleset (apply set/union (map extract-version-rules perms))
                                        writers (:writers ev)
                                        {:keys [create-plan add-task mark-as-ready]} zk-plan
                                        plan (create-plan (:plan-prefix migration-config))
                                        shards (:number-of-shards migration-config)]
                                    (loop [ruleseq (clg/sort-rules (seq ruleset))
                                           last-task nil]
                                      (when-not (empty? ruleseq)
                                        (let [rulefunc (first ruleseq)
                                              rule (-> rulefunc clg/rule-target-fact first str (subs 1) symbol)
                                              _ (info {:source "migrator"
                                                       :desc (str "migrating rule " rule)})
                                              last-task (loop [rulefunc rulefunc
                                                               link 0
                                                               deps (cond last-task
                                                                          [last-task]
                                                                          :else
                                                                          [])]
                                                          (cond (nil? rulefunc)
                                                                (add-task plan `(migration-end-notifier '~rule ~writers) deps)
                                                                :else
                                                                (let [singleton-task (add-task plan `(fact-declarer '~rule ~link) deps)
                                                                      tasks (for [shard (range shards)]
                                                                              (add-task plan
                                                                                        (cond (= link 0)
                                                                                              `(initial-migrator '~rule ~shard ~shards)
                                                                                              :else
                                                                                              `(link-migrator '~rule ~link ~shard ~shards))
                                                                                        [singleton-task]))]
                                                                  (recur (-> rulefunc meta :continuation) (inc link) (doall tasks)))))]
                                          (recur (rest ruleseq) last-task))))
                                    (mark-as-ready plan)))
                                nil)))
  (di/do-with $ [declare-service assign-service sh migration-config deploy-dir]
              (declare-service "migrator.core/push-handler"
                               {:kind :fact
                                :name "axiom/app-version"})
              (assign-service "migrator.core/push-handler"
                              (fn [ev publish]
                                (let [unique (rand-int 1000000000)
                                      dir (str (:clone-location migration-config) "/repo" unique)
                                      [version] (:data ev)]
                                  (sh "git" "clone" "--depth"
                                      (str (:clone-depth migration-config))
                                      (:key ev)
                                      dir)
                                  (sh "git" "checkout" version :dir dir)
                                  (deploy-dir version dir publish)
                                  (sh "rm" "-rf" dir))
                                nil)))

  (di/do-with $ [publish declare-service assign-service]
              (declare-service "migrator.core/clause-migrator" {:kind :fact
                                                                :name "axiom/perms-exist"})
              (assign-service "migrator.core/clause-migrator"
                              (fn [{:keys [data]}]
                                (let [[perms] data
                                      clauses (mapcat extract-version-clauses perms)]
                                  (doseq [clause clauses]
                                    (loop [link clause
                                           n 0]
                                      (when link
                                        (declare-service (str "fact-for-rule/" (-> clause meta :ns str) "/" (-> clause meta :name) "!" n)
                                                         {:kind :fact
                                                          :name (-> link meta :source-fact first str)})
                                        (recur (-> link meta :continuation) (inc n))))
                                    (publish {:kind :fact
                                              :name "axiom/rule-ready"
                                              :key (symbol (-> clause meta :ns str) (-> clause meta :name))
                                              :data []}))))))
  
  (di/provide $ hash-static-file [hasher]
              (let [[hash unhash] hasher]
                (fn [f]
                  (hash (to-bin-seq f)))))

  (di/provide $ hash-static-files [hash-static-file]
              (fn [root]
                (let [root-path-len (count (.getPath root))]
                  (->> (for [f (file-seq root)
                             :when (.isFile f)]
                         [(subs (.getPath f) root-path-len) (hash-static-file f)])
                       (into {})))))

  (di/provide $ deploy-dir [hasher
                            hash-static-files]
              (fn [ver dir publish]
                (let [perms (permpub/hash-all hasher (io/file dir "src"))
                      statics (hash-static-files (io/file dir "static"))]
                  (publish {:kind :fact
                            :name "axiom/perm-versions"
                            :key ver
                            :data [perms statics]})
                  nil))))
