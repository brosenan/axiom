(ns rabbit-microservices.core
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]))

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
