(ns axiom-cljs.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]))

(defn connection [& {:keys [ws-ch]}]
  (go
    (let [ch (-> (ws-ch "some-url")
                 async/<!
                 :ws-channel)
          init (async/<! ch)]
      (merge init {:from-host ch
                   :to-host ch}))))
