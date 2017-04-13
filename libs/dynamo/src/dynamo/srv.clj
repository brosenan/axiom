(ns dynamo.srv
  (:require [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [clojure.set :as set]
            [clojure.string :as str]))

(def default-throughput {:read 1 :write 1})

(def ddb-config (atom nil))
(def curr-tables (atom #{}))

(defn init [config]
  (reset! ddb-config config)
  (swap! curr-tables (partial set/union (set (far/list-tables config))))
  nil)

(defn table-kw [name]
  (-> name
      (str/replace \/ \.)
      (str/replace #"[^0-9a-zA-Z\-.]" "_")
      keyword))

(defn store-fact
  {:reg {:kind :fact}}
  [ev]
  (let [name (:name ev)]
    (cond (or (str/ends-with? name "?")
              (str/ends-with? name "!"))
          nil
          :else
          (let [table-name (table-kw (:name ev))]
            (when-not (@curr-tables name)
              (far/ensure-table @ddb-config table-name [:key :s] :range-keydef [:ts :n] :throughput default-throughput)
              (swap! curr-tables #(conj % name)))
            (let [bin (nippy/freeze (dissoc ev :kind :name :key :ts))]
              (far/put-item @ddb-config table-name {:key (pr-str (:key ev))
                                                    :ts (:ts ev)
                                                    :event bin})))))
  nil)
