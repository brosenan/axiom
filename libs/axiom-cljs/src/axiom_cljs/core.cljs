(ns axiom-cljs.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [chord.client :refer [ws-ch]]))

(defn pubsub [f]
  (let [listeners (atom {})]
    {:pub (fn [val]
            (doseq [listener (@listeners (f val))]
              (listener val)))
     :sub (fn [disp f]
            (swap! listeners update disp conj f))}))

(defn connection [url & {:keys [ws-ch]
                         :or {:ws-ch ws-ch}}]
  (go
    (let [ch (-> (ws-ch url)
                 async/<!
                 :ws-channel)
          init (async/<! ch)
          ps (pubsub :name)]
      (go-loop []
        ((:pub ps) (async/<! ch))
        (recur))
      (merge init {:pub (fn [ev] (go
                                   (async/>! ch ev)))
                   :sub (:sub ps)
                   :time (fn [] (.getTime (js/Date.)))
                   :uuid #(str (random-uuid))}))))

