(ns dynamo.srv
  (:require [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [clojure.set :as set]))

(def default-throughput {:read 1 :write 1})

(def ddb-config (atom nil))
(def curr-tables (atom #{}))

(defn init [config]
  (reset! ddb-config config)
  (swap! curr-tables (partial set/union (set (far/list-tables config))))
  nil)

(defn store-fact
  {:reg {:kind :fact}}
  [ev]
  (when-not (@curr-tables (:name ev))
    (far/ensure-table @ddb-config (:name ev) [:key :s] :range-keydef [:ts :n] :throughput default-throughput)
    (swap! curr-tables #(conj % (:name ev))))
  (let [bin (nippy/freeze (dissoc ev :kind :key :name))]
    (far/put-item @ddb-config (:name ev) {:key (pr-str (:key ev))
                                          :ts (:ts ev)
                                          :event bin}))
  nil)
