(ns dynamo.core
  (:require [clojure.string :as str]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [clojure.core.async :as async]
            [clojure.set :as set]
            [di.core :as di]))

(defn table-kw [name]
  (-> name
      (str/replace \/ \.)
      (str/replace #"[^0-9a-zA-Z\-.]" "_")
      keyword))



(defn retriever [config req-chan]
  (let [request (async/<!! req-chan)]
    (cond (nil? request)
          false
          :else
          (let [[ev res-chan] request
                items (far/query config (table-kw (:name ev)) {:key [:eq (pr-str (:key  ev))]})]
            (doseq [item items]
              (let [event (-> (nippy/thaw (:event item))
                              (merge {:ts (:ts item)})
                              (merge ev))]
                (async/>!! res-chan event)))
            (async/close! res-chan)
            true))))

(defn scanner [config ensure table shard shards chan]
  (let [kw (table-kw table)]
    (ensure kw)
    (let [items (far/scan config kw {:segment shard
                                     :total-segments shards})]
      (doseq [item items]
        (let [body (nippy/thaw (:event item))]
          (async/>!! chan (merge body {:kind :fact
                                       :name table
                                       :key (read-string (:key item))
                                       :ts (:ts item)}))))
      (async/close! chan))
    nil))

(defn module [$]
  (di/provide database-chan $
              (di/with-dependencies $ [database-retriever
                                       num-database-retriever-threads]
                (let [chan (async/chan 100)]
                  (doseq [_ (range num-database-retriever-threads)]
                    (async/thread
                      (while (database-retriever chan))))
                  chan)))
  (di/provide database-retriever $
              (di/with-dependencies $ [dynamodb-config]
                (partial retriever dynamodb-config)))
  (async/go
    (di/with-dependencies $ [declare-service
                             assign-service
                             database-event-storage]
      (declare-service "database-event-storage"
                       {:kind :fact})
      (assign-service "database-event-storage"
                      database-event-storage)))

  (di/provide dynamodb-get-tables $
              far/list-tables)

  (di/provide database-tables $
              (di/with-dependencies $ [dynamodb-get-tables
                                       dynamodb-config]
                (atom (set (dynamodb-get-tables dynamodb-config)))))
  (di/provide database-scanner $
              (di/with-dependencies $ [dynamodb-config
                                       database-ensure-table]
                (partial scanner dynamodb-config database-ensure-table)))

  (di/provide database-event-storage $
              (di/with-dependencies $ [dynamodb-config
                                       database-ensure-table]
      (fn
        [ev]
        (let [name (:name ev)]
          (cond (or (str/ends-with? name "?")
                    (str/ends-with? name "!"))
                nil
                :else
                (let [table-name (table-kw (:name ev))]
                  (database-ensure-table table-name)
                  (let [bin (nippy/freeze (dissoc ev :kind :name :key :ts))]
                    (far/put-item dynamodb-config table-name {:key (pr-str (:key ev))
                                                              :ts (:ts ev)
                                                              :event bin})))))
        nil)))

  (di/provide database-event-storage-chan $
              (di/with-dependencies $ [database-event-storage
                                       dynamodb-event-storage-num-threads]
                (let [chan (async/chan)]
                  (doseq [_ (range dynamodb-event-storage-num-threads)]
                    (async/thread
                      (loop []
                        (let [[ev ack] (async/<!! chan)]
                          (database-event-storage ev)
                          (async/close! ack)
                          (recur)))))
                  chan)))
  (di/provide database-ensure-table $
              (di/with-dependencies $ [database-tables
                                       dynamodb-default-throughput
                                       dynamodb-config]
                (fn [table]
                  (when-not (contains? @database-tables table)
                    (far/ensure-table dynamodb-config table
                                      [:key :s]
                                      {:range-keydef [:ts :n]
                                       :throughput dynamodb-default-throughput
                                       :block true})
                    (swap! database-tables #(conj % table)))
                  nil))))
