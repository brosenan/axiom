(ns storm.core
  (:require [permacode.core :as perm]
            [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]]
            [di.core :as di]
            [clojure.core.async :as async]))

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
  {:params [name config]}
  [conf context collector])

(defn keyword-keys [event]
  (->> event
       (map (fn [[k v]] [(keyword k) v]))
       (into {})))

(s/defbolt initial-link-bolt event-fields
  {:params [rulesym config]}
  [tuple collector]
  (let [rule (perm/eval-symbol rulesym)
        em (ev/emitter rule)
        {:as event} tuple
        event (keyword-keys event)]
    (doseq [res (em event)]
      (s/emit-bolt! collector res :anchor tuple)))
  (s/ack! collector tuple))

(s/defbolt link-bolt event-fields
  {:params [rulesym link config]
   :prepare true}
  [conf context collector]
  (let [$ (injector config :link-bolt)
        rulefunc (perm/eval-symbol rulesym)]
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
      (let [spoutspec (s/spout-spec (fact-spout (-> rulefunc meta :source-fact clg/fact-table) config))
            spouts (assoc spouts (str "f" index) spoutspec)
            boltspec (cond (= index 0)
                           (s/bolt-spec {(str "f" index) ["key"]} (initial-link-bolt rulesym config))
                           :else
                           (s/bolt-spec {(str "f" index) ["key"]
                                         (str "l" (dec index)) ["key"]} (link-bolt rulesym index config)))
            bolts (assoc bolts (str "l" index) boltspec)
            next (-> rulefunc meta :continuation)]
        (cond next
              (recur next spouts bolts (inc index))
              :else
              (let [bolts (assoc bolts "out" (s/bolt-spec {(str "l" index) :shuffle} (output-bolt config)))]
                (s/topology spouts bolts)))))))
