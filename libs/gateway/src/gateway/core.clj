(ns gateway.core
  (:require [di.core :as di]
            [clojure.core.async :as async]
            [cloudlog-events.core :as ev]
            [cloudlog.interset :as interset]))

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
                      (let [xform (comp
                                   (filter (fn [ev] (contains? (:writers ev) (-> ev
                                                                                 :name
                                                                                 keyword
                                                                                 namespace))))
                                   (filter (fn [ev] (interset/subset? #{user} (:readers ev))))
                                   (map (fn [ev] (vec (cons (keyword (:name ev)) (:data ev))))))
                            trans-chan (async/chan 1 xform)]
                        (async/pipe (async/merge (vals chanmap)) trans-chan)
                        (async/<! (async/reduce conj #{user} trans-chan)))))))))
