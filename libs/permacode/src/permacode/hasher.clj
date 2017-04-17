(ns permacode.hasher
  (:require [taoensso.nippy :as nippy]
            [multihash.digest :as digest]
            [multihash.core :as multihash]))

(defn hasher-pair [[ser deser] hash [store retr]]
  [(fn [expr]
     (let [s (ser expr)
           h (hash s)]
       (store h s)
       h))
   (fn [hashcode]
     (-> hashcode retr deser))])

(defn nippy-multi-hasher [storage]
  (let [hash (fn [bin]
               (-> bin digest/sha2-256 multihash/base58))]
    (hasher-pair [nippy/freeze nippy/thaw] hash storage)))

(defn slurp-bytes
  "Slurp the bytes from a slurpable thing.  Taken from http://stackoverflow.com/a/26372677/2168683"
  [x]
  (with-open [out (java.io.ByteArrayOutputStream.)]
    (clojure.java.io/copy (clojure.java.io/input-stream x) out)
    (.toByteArray out)))

(defn file-store [repo]
  [(fn [key content]
     (let [f (java.io.File. repo key)]
       (with-open [output (clojure.java.io/output-stream f)]
         (.write output content)))
     nil)
   (fn [key]
     (let [f (java.io.File. repo key)]
       (slurp-bytes f)))])

(defn atom-store []
  (let [store (atom {})]
    [(fn [key content]
       (swap! store assoc key content)
       nil)
     (fn [key]
       (@store key))]))
