(ns dynamo.core
  (:require [clojure.string :as str]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [clojure.core.async :as async]))

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

(defn scanner [config table shard shards chan]
  (let [kw (table-kw table)
        items (far/scan config kw {:segment shard
                                   :total-segments shards})]
    (doseq [item items]
      (let [body (nippy/thaw (:event item))]
        (async/>!! chan (merge body {:kind :fact
                                     :name table
                                     :key (read-string (:key item))
                                     :ts (:ts item)}))))))
