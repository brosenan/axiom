(ns rabbit-microservices.core
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [langohr.exchange  :as le]
            [taoensso.nippy :as nippy]
            [di.core :as di]
            [clojure.core.async :as async]
            [clojure.repl :as repl]))


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
          data-bytes)))

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

(defn publisher [srv info time ev]
  (let [bin (nippy/freeze (merge ev {:ts (time)
                                     :change 1
                                     :writers #{}
                                     :readers #{}}))
        rk (event-routing-key ev)]
    (info {:source "rabbit"
           :desc (str "Publishing " ev " on " rk)})
    (lb/publish (:chan srv) facts-exch rk bin {})
    nil))

(defn my-ack [& args]
  (apply lb/ack args))

(defn handle-event [func alive info chan meta-attrs binary]
  (let [event (nippy/thaw binary)
        publish (fn [ev]
                  (when @alive
                    (lb/publish chan facts-exch (event-routing-key ev) (nippy/freeze (merge event ev)) meta-attrs)))
        ack (fn [] (my-ack chan (:delivery-tag meta-attrs)))]
    (info {:source "rabbit"
           :desc (str "handling: " event " by: " (repl/demunge (str func)))})
    (case (arg-count func)
      1 (func event)
      2 (func event publish)
      3 (func event publish ack)))
  nil)

(defn func-name [func]
  (->> func
       str
       clojure.repl/demunge
       (re-find #"[^@]*")))

(defn register-events-to-queue* [rabbitmq-service info queue reg]
  (let [rk (event-routing-key reg)]
    (info {:source "rabbit"
           :desc (str "Binding queue " key " to routing key " rk)})
    (lq/bind (:chan rabbitmq-service) queue facts-exch {:routing-key rk})
    nil))

(defn module [$]
  (di/provide $ rabbitmq-service [rabbitmq-config]
              (binding [rmq/*default-config* rabbitmq-config]
                (create-service)))

  (di/provide $ serve [declare-service
                       assign-service]
              (fn [func reg]
                (let [key (func-name func)]
                  (declare-service key reg)
                  (assign-service key func))
                nil))
  
  (defn declare-declare-service [rabbitmq-service info options]
    (fn [key reg]
      (let [chan (:chan rabbitmq-service)]
        (lq/declare chan key options)
        (register-events-to-queue* rabbitmq-service info key reg))
      nil))

  (di/provide $ declare-service [rabbitmq-service info]
              (declare-declare-service rabbitmq-service info {:durable true
                                                              :auto-delete false}))
  (di/provide $ declare-volatile-service [rabbitmq-service info]
              (declare-declare-service rabbitmq-service info {:durable false}))

  (di/provide $ assign-service [rabbitmq-service info]
              (fn [key func]
                (lc/subscribe (:chan rabbitmq-service) key (partial handle-event func (:alive rabbitmq-service) info) {:auto-ack (< (arg-count func) 3)})
                nil))

  (di/provide $ publish [rabbitmq-service info time]
              (partial publisher rabbitmq-service info time))

  (di/provide $ poll-events [rabbitmq-service]
              (fn [queue]
                (loop [res []]
                  (let [resp (lb/get (:chan rabbitmq-service) queue true)]
                    (cond (nil? resp)
                          res
                          :else
                          (let [[metadata payload] resp
                                ev (nippy/thaw payload)]
                            (recur (conj res ev))))))))

  (di/provide $ declare-private-queue [rabbitmq-service]
              (fn []
                (lq/declare-server-named (:chan rabbitmq-service) {:exclusive true})))

  (di/provide $ delete-queue [rabbitmq-service]
              (fn [q]
                (lq/delete (:chan rabbitmq-service) q)
                nil))

  (di/provide $ register-events-to-queue [rabbitmq-service info]
              (partial register-events-to-queue* rabbitmq-service info))

  (di/provide $ event-bridge [publish
                              declare-private-queue
                              assign-service
                              database-chan
                              register-events-to-queue
                              delete-queue]
              (fn [[c2s s2c]]
                (let [queue (declare-private-queue)]
                  (assign-service queue (fn [ev]
                                          (async/>!! s2c ev)))
                  (async/go-loop []
                    (let [ev (async/<! c2s)]
                      (cond (nil? ev)
                            (delete-queue queue)
                            (= (:kind ev) :fact)
                            (do
                              (publish ev)
                              (recur))
                            (= (:kind ev) :reg)
                            (let [query (-> ev
                                            (assoc :kind :fact)
                                            (dissoc :get-existing))]
                              (register-events-to-queue queue query)
                              (when (:get-existing ev)
                                (let [reply-chan (async/chan 10)]
                                  (async/>! database-chan [query reply-chan])
                                  (async/go-loop []
                                    (when-let [ev (async/<! reply-chan)]
                                      (async/>! s2c ev)
                                      (recur)))))
                              (recur))))))
                nil)))
