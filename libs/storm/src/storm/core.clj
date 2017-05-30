(ns storm.core
  (:require [permacode.core :as perm]
            [cloudlog.core :as clg]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]]))

(s/defspout fact-spout ["foo"]
  [conf context collector])

(s/defbolt initial-link-bolt ["foo"]
  [args collector])
(s/defbolt link-bolt ["foo"]
  [args collector])

(s/defbolt output-bolt ["foo"]
  [args collector])

(defn topology [rulesym]
  (let [rulefunc (perm/eval-symbol rulesym)]
    (loop [rulefunc rulefunc
           spouts {}
           bolts {}
           index 0]
      (let [spoutspec (s/spout-spec (fact-spout (-> rulefunc meta :source-fact clg/fact-table)))
            spouts (assoc spouts (str "f" index) spoutspec)
            boltspec (cond (= index 0)
                           (s/bolt-spec {(str "f" index) ["key"]} (initial-link-bolt rulesym))
                           :else
                           (s/bolt-spec {(str "f" index) ["key"]
                                         (str "l" (dec index)) ["key"]} (link-bolt rulesym index)))
            bolts (assoc bolts (str "l" index) boltspec)
            next (-> rulefunc meta :continuation)]
        (cond next
              (recur next spouts bolts (inc index))
              :else
              (let [bolts (assoc bolts "out" (s/bolt-spec {(str "l" index) :shuffle} output-bolt))]
                (s/topology spouts bolts)))))))
