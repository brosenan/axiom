(ns cloudlog.graph
  (:require [permacode.core :as permacode]
            [clojure.set :as set]))

(permacode/pure
 (defn merge-graph [a b]
   (merge-with set/union a b))

 (defn invert [graph]
   (reduce merge-graph {}
           (for [[src dests] graph
                 dest dests]
             {dest #{src}})))

 (defn sources [graph]
   (set/difference (set (keys graph)) (reduce set/union (vals graph))))

 (defn toposort
   ([graph] (toposort graph (invert graph) (sources graph)))
   ([graph inv sources]
    (if (empty? sources)
      []
      ; else
      (let [fst (first sources)
            subgraph {fst (graph fst)}
            graph' (merge-with set/difference graph subgraph)
            inv' (merge-with set/difference inv (invert subgraph))]
        (cons fst (toposort graph' inv' (set/union
                                         (rest sources)
                                         (set (filter (fn [node] (empty? (inv' node))) (graph fst)))))))))))
