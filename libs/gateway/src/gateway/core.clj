(ns gateway.core
  (:require [di.core :as di]
            [clojure.core.async :as async]
            [cloudlog-events.core :as ev]))

(defn module [$]
  (di/provide $ identity-set [database-chan]
              (let [database-chan (ev/accumulate-db-chan database-chan)]
                (fn [user relations]
                  (async/go
                    (let [chanmap (into {} (for [rel relations]
                                             [rel (async/chan 2)]))]
                      (doseq [[rel chan] chanmap]
                        (async/>! database-chan [{:kind :fact
                                                  :name rel
                                                  :key user} chan]))
                      (->> (vals chanmap)
                           async/merge
                           list
                           (async/map (fn [ev] (vec (cons (keyword (:name ev)) (:data ev)))))
                           (async/reduce conj #{user})
                           (async/<!))))))))
