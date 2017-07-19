(ns cloudlog.interset
  (:require [clojure.set :as set]
            [permacode.core]))

(permacode.core/pure
 (def orig-empty? empty?)

 (def universe #{})
 (def empty-set [])

 (defn canonical [s]
   (cond (vector? s)
         s
         :else
         [s]))

 (defn uncanonical [s]
   (cond (and (vector? s) (= (count s) 1))
         (first s)
         :else s))

 (defn disjoint? [a b]
   (let [a' (first (filter string? a))
         b' (first (filter string? b))]
     (and (some? a') (some? b')
          (not= a' b'))))

 (defn intersection [a b]
   (uncanonical (vec (for [a' (canonical a)
                           b' (canonical b)
                           :when (not (disjoint? a' b'))]
                       (set/union a' b')))))

 (defn subset? [a b]
   (let [a (canonical a)
         b (canonical b)]
     (every?
      (fn [a]
        (some? (some
                (fn [b]
                  (set/subset? b a))
                b)))
      a)))

 (defn union [a b]
   (let [a (canonical a)]
     (concat a (for [b' (canonical b)
                     :when (not (subset? b' a))]
                 b'))))
 
 (defn enum-groups [s]
   (mapcat (fn [x] (into [] x)) (canonical s))))
