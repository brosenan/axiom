(ns rabbit-microservices.core
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]))


;; Adapted from https://gist.github.com/prasincs/827272
(defn get-hash [type data]
  (.digest (java.security.MessageDigest/getInstance type) (.getBytes data) ))

(defn get-hash-str [data-bytes]
  (apply str 
         (map 
          #(.substring 
            (Integer/toString 
             (+ (bit-and % 0xff) 0x100) 16) 1) 
          data-bytes)
         ))

(defn sha1-digest [str]
  (get-hash-str (get-hash "sha1" str)))

(defn create-service []
  (let [conn (rmq/connect)
        chan (lch/open conn)
        qname (lq/declare-server-named chan)]
    {:conn conn
     :chan chan
     :q qname}))

(defn shut-down-service [service]
  (rmq/close (:chan service))
  (rmq/close (:conn service))
  nil)

(defn event-routing-key [ev]
  (when-not (= (:kind ev) :fact)
    (throw (Exception. "Only fact events are supported")))
  (str "f." (cond (:name ev)
                  (str (sha1-digest (:name ev)) "." (cond (:key ev)
                                                          (sha1-digest (pr-str (:key ev)))
                                                          :else
                                                          "#"))
                  :else
                  "#")))
