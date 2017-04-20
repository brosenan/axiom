(ns rabbit-microservices.core
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [langohr.exchange  :as le]
            [taoensso.nippy :as nippy]
            [di.core :as di]))


(def facts-exch "facts")

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
        chan (lch/open conn)]
    (le/declare chan facts-exch "topic")
    {:conn conn
     :chan chan
     :alive (atom true)}))

(defn shut-down-service [service]
  (reset! (:alive service) false)
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

(defn arg-count [func]
  (-> func class .getDeclaredMethods first .getParameterTypes alength))

(defn publisher [srv ev]
  (let [bin (nippy/freeze ev)
        rk (event-routing-key ev)]
    (lb/publish (:chan srv) facts-exch rk bin {})
    nil))

(defn my-ack [& args]
  (apply lb/ack args))

(defn handle-event [func alive chan meta-attrs binary]
  (let [event (nippy/thaw binary)
        publish (fn [ev]
                  (when @alive
                    (lb/publish chan facts-exch (event-routing-key ev) (nippy/freeze (merge event ev)) meta-attrs)))
        ack (fn [] (my-ack chan (:delivery-tag meta-attrs)))]
    (case (arg-count func)
      1 (func event)
      2 (func event publish)
      3 (func event publish ack)))
  nil)

(defn register-func [service func reg]
  (let [q (lq/declare-server-named (:chan service))]
    (lq/bind (:chan service) q facts-exch {:routing-key (event-routing-key reg)})
    (lc/subscribe (:chan service) q (partial handle-event func (:alive service)) {:auto-ack (< (arg-count func) 3)})
    nil))

(defn module [inj]
  (di/provide amqp-service inj
              (di/with-dependencies inj [amqp-config]
                (binding [rmq/*default-config* amqp-config]
                  (create-service))))

  (di/provide serve inj
              (di/with-dependencies inj [amqp-service]
                (partial register-func amqp-service)))

  (di/provide publish inj
              (di/with-dependencies inj [amqp-service]
                (partial publisher amqp-service))))

