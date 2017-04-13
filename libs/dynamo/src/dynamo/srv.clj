(ns dynamo.srv
  (:require [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [clojure.set :as set]
            [clojure.string :as str]
            [dynamo.core :as core]))

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
  (let [name (:name ev)]
    (cond (or (str/ends-with? name "?")
              (str/ends-with? name "!"))
          nil
          :else
          (let [table-name (core/table-kw (:name ev))]
            (when-not (@curr-tables table-name)
              (far/ensure-table @ddb-config table-name [:key :s]
                                {:range-keydef [:ts :n]
                                 :throughput default-throughput
                                 :block true})
              (swap! curr-tables #(conj % table-name)))
            (let [bin (nippy/freeze (dissoc ev :kind :name :key :ts))]
              (far/put-item @ddb-config table-name {:key (pr-str (:key ev))
                                                    :ts (:ts ev)
                                                    :event bin})))))
  nil)
