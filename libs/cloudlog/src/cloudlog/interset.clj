(ns cloudlog.interset
  (:require [clojure.set :as set]
            [permacode.core]))

(permacode.core/pure
 (def orig-empty? empty?)

 (def universe #{})

 (defn intersection [& sets]
   (apply set/union sets))

 (defn subset? [a b]
   (set/subset? b a))

 (defn super-union [a b]
   (set/intersection a b))

 (defn partition? [named]
   (and (vector? named)
        (= (count named) 2)
        (clojure.string/ends-with? (named 0) "=")))

 (defn empty? [set]
   (or (contains? set :empty)
       (loop [members (seq set)
              partitions #{}]
         (if (orig-empty? members)
           false
                                        ; else
           (let [member (first members)]
             (if (partition? member)
               (if (partitions (member 0))
                 true
                                        ; else
                 (recur (rest members) (set/union partitions #{(member 0)})))
                                        ; else
               (recur (rest members) partitions))))))))
