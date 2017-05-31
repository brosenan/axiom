(ns storm.core
  (:require [permacode.core :as perm]
            [cloudlog-events.core :as ev]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]]))

(def event-fields
  ["kind" "name" "key" "data" "ts" "change" "writers" "readers"])

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
      (s/emit-bolt! collector res)))
  (s/ack! collector tuple))
(s/defbolt link-bolt ["foo"]
  [args collector])

(s/defbolt output-bolt ["foo"]
  [args collector])

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
