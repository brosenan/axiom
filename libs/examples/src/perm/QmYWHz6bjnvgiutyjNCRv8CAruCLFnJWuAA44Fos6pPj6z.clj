(ns perm.QmYWHz6bjnvgiutyjNCRv8CAruCLFnJWuAA44Fos6pPj6z [:require [clojure.set :as set]] [:require [permacode.core]])(permacode.core/pure (def orig-empty? empty?) (def universe #{}) (defn intersection [& sets] (apply set/union sets)) (defn subset? [a b] (set/subset? b a)) (defn super-union [a b] (set/intersection a b)) (defn partition? [named] (and (vector? named) (= (count named) 2) (clojure.string/ends-with? (named 0) "="))) (defn empty? [set] (or (contains? set :empty) (loop [members (seq set) partitions #{}] (if (orig-empty? members) false (let [member (first members)] (if (partition? member) (if (partitions (member 0)) true (recur (rest members) (set/union partitions #{(member 0)}))) (recur (rest members) partitions))))))))