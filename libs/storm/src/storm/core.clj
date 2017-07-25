(ns storm.core
  (:require [permacode.core :as perm]
            [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]]
            [di.core :as di]
            [clojure.core.async :as async]
            [clojure.string :as str]))

(def event-fields
  ["kind" "name" "key" "data" "ts" "change" "writers" "readers"])

(defn task-config [config name]
  (merge (->> (config name)
              :include
              (map (fn [key] [key (config key)]))
              (into {}))
         (:overrides (config name))))

(defn injector [config name]
  (let [$ (di/injector (task-config config name))
        modules (:modules config)]
    (doseq [module modules]
      ((eval module) $))
    (di/startup $)
    $))

(s/defspout fact-spout event-fields
  {:params [q config]}
  [conf context collector]
  (let [$ (injector config :fact-spout)
        read-chan (async/chan)
        acks (atom {})
        next-id (atom 0)]
    (di/do-with! $ [assign-service]
                 (assign-service q (fn [ev pub ack]
                                     (async/>!! read-chan [ev ack])))
                 (s/spout
                  (nextTuple []
                             (let [[val chan] (async/alts!! [read-chan
                                                             (async/timeout 10)])]
                               (when (= chan read-chan)
                                 (let [[ev ack] val
                                       id @next-id]
                                   (s/emit-spout! collector ev :id id)
                                   (swap! acks #(assoc % id ack))
                                   (swap! next-id inc)))))
                  (ack [id]
                       (let [ack (@acks id)]
                         (ack)
                         (swap! acks #(dissoc % id))))
                  (fail [id])
                  (close []
                         (di/shutdown $))))))

(defn keyword-keys [event]
  (->> event
       (map (fn [[k v]] [(keyword k) v]))
       (into {})))

(s/defbolt initial-link-bolt event-fields
  {:params [rulesym config]
   :prepare true}
  [conf context collector]
  (let [$ (injector config :initlal-link-bolt)
        rule (di/do-with! $ [hasher]
                          (binding [permacode.validate/*hasher* hasher]
                            (perm/eval-symbol rulesym)))
        em (ev/emitter rule)]
    (s/bolt
     (execute [tuple]
              (let [{:as event} tuple
                    event (keyword-keys event)
                    events (em event)]
                (doseq [res events]
                  (s/emit-bolt! collector res :anchor tuple))
                (s/ack! collector tuple))))))

(s/defbolt link-bolt event-fields
  {:params [rulesym link config]
   :prepare true}
  [conf context collector]
  (let [$ (injector config :link-bolt)
        rulefunc (di/do-with! $ [hasher]
                              (binding [permacode.validate/*hasher* hasher]
                                (perm/eval-symbol rulesym)))]
    (di/do-with! $ [database-chan]
                 (let [matcher (ev/matcher rulefunc link database-chan)]
                   (s/bolt
                    (execute [tuple]
                             (let [{:as event} tuple
                                   event (keyword-keys event)
                                   resp-chan (async/chan)]
                               (matcher event resp-chan)
                               (async/go
                                 (loop []
                                   (let [out-ev (async/<! resp-chan)]
                                     (when out-ev
                                       (s/emit-bolt! collector out-ev :anchor tuple)
                                       (recur))))
                                 (s/ack! collector tuple)))))))))

(s/defbolt output-bolt []
  {:params [config]
   :prepare true}
  [conf context collector]
  (let [$ (injector config :output-bolt)]
    (di/do-with! $ [publish]
                 (s/bolt
                  (execute [tuple]
                           (let [{:as event} tuple
                                 event (keyword-keys event)]
                             (publish event)
                             (s/ack! collector tuple)))))))

(s/defbolt store-bolt []
  {:params [config]
   :prepare true}
  [conf context collector]
  (let [$ (injector config :store-bolt)]
    (di/do-with! $ [database-event-storage-chan]
                 (s/bolt
                  (execute [tuple]
                           (let [{:as event} tuple
                                 event (keyword-keys event)
                                 ack (async/chan)]
                             (async/go
                               (async/>! database-event-storage-chan [event ack])
                               (async/<! ack)
                               (s/ack! collector tuple))))))))

(defn topology [rulesym config]
  (let [rulefunc (perm/eval-symbol rulesym)]
    (loop [rulefunc rulefunc
           spouts {}
           bolts {}
           index 0]
      (let [spoutspec (s/spout-spec (fact-spout (str "fact-for-rule/" rulesym "!" index) config))
            spouts (assoc spouts (str "f" index) spoutspec)
            boltspec (cond (= index 0)
                           (s/bolt-spec {(str "f" index) ["key"]} (initial-link-bolt rulesym config))
                           :else
                           (s/bolt-spec {(str "f" index) ["key"]
                                         (str "l" (dec index)) ["key"]} (link-bolt rulesym index config)))
            next (-> rulefunc meta :continuation)
            outbolt (cond next
                          (s/bolt-spec {(str "l" index) :shuffle} (store-bolt config))
                          :else
                          (s/bolt-spec {(str "l" index) :shuffle} (output-bolt config)))
            bolts (-> bolts
                      (assoc (str "l" index) boltspec)
                      (assoc (str "o" index) outbolt))]
        (cond next
              (recur next spouts bolts (inc index))
              :else
              (s/topology spouts bolts))))))

(defn convert-topology-name [name]
  (-> name
      (str/replace "." "-")
      (str/replace #"[\\/:]" "_")))

(defn module [$ config]
  (di/provide $ rule-topology [storm-cluster]
              (fn [ev]
                (let [rule (-> ev :data first)
                      topo-name (convert-topology-name (str rule))]
                  (when (> (:change ev) 0)
                    (let [topology (topology rule config)]
                      ((:run storm-cluster) topo-name topology)
                      nil))
                  (when (< (:change ev) 0)
                    ((:kill storm-cluster) topo-name)))
                nil))
  (di/do-with $ [declare-service
                 assign-service
                 rule-topology]
              (declare-service "storm.core/rule-topology" {:kind :fact
                                                           :name "axiom/rule-ready"})
              (assign-service "storm.core/rule-topology"
                              rule-topology))

  (di/do-with $ [database-chan
                 rule-topology]
              (let [database-chan (ev/accumulate-db-chan database-chan)
                    reply-chan (async/chan 2)]
                (async/>!! database-chan [{:kind :fact
                                           :name "axiom/rule-ready"
                                           :key 0} reply-chan])
                (loop []
                  (when-let [reply (async/<!! reply-chan)]
                    (rule-topology reply)
                    (recur)))))
  
  (di/provide $ storm-cluster [local-storm-cluster]
              (let [cluster (org.apache.storm.LocalCluster.)]
                {:resource {:run (fn [name top]
                                   (.submitTopology cluster name {org.apache.storm.config/TOPOLOGY-DEBUG true} top))
                            :kill (fn [name]
                                    (.killTopology cluster name))}
                 :shutdown (fn []
                             (.shutdown cluster))})))
