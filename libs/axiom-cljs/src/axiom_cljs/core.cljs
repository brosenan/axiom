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
  (let [to-host (async/chan 2)
        ps (pubsub :name)
        identity (atom nil)]
    (go
      (let [ch (-> (ws-ch url)
               async/<!
               :ws-channel)]
        (async/pipe to-host ch)
        (go-loop []
          (let [ev (async/<! ch)]
            (prn ev)
            (when (= (:kind ev) :init)
              (reset! identity (:identity ev)))
            ((:pub ps) ev))
          (recur))))
    {:pub (fn [ev] (go
                     (async/>! to-host ev)))
     :sub (:sub ps)
     :time (fn [] (.getTime (js/Date.)))
     :uuid #(str (random-uuid))
     :identity identity}))

