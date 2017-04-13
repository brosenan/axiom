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
  (let [[ev res-chan] (async/<!! req-chan)
        items (far/query config (table-kw (:name ev)) {:key [:eq (pr-str (:key  ev))]})]
    (doseq [item items]
      (let [event (-> (nippy/thaw (:event item))
                      (merge {:ts (:ts item)})
                      (merge ev))]
        (async/>!! res-chan event)))
    (async/close! res-chan)))
