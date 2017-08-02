(ns cloudlog-events.cache
  (:require [clojure.data.finger-tree :as ft]
            [clojure.core.async :as async]))

(defn time-window-cache [retention]
  [{} (ft/double-list) retention])

(defn tw-prune [[evmap evlist retention] ts]
  (let [deadline (- ts retention)]
    (loop [evmap evmap
           evlist evlist]
      (cond (empty? evlist)
            [evmap evlist retention]
            :else
            (let [[ev ev-ts] (first evlist)]
              (cond (< ev-ts deadline)
                    (recur (update evmap [(:name ev) (:key ev)] disj ev) (rest evlist))
                    :else
                    [evmap evlist retention]))))))

(defn tw-update [[evmap evlist retention] ev ts]
  (-> [(-> evmap
           (update [(:name ev) (:key ev)] (-> #(conj % ev)
                                              (fnil #{}))))
       (-> evlist
           (conj [ev ts])) retention]
      (tw-prune ts)))

(defn tw-get [[evmap] part-ev]
  (or (evmap [(:name part-ev) (:key part-ev)])
      #{}))


(defn wrap-time-window [matcher get-time retention]
  (fn [rulefunc link db-chan]
    (let [cache (atom (time-window-cache retention))
          my-db-chan (async/chan 2)
          m (matcher rulefunc link my-db-chan)]
      (async/go-loop []
        (let [[q reply-ch] (async/<! my-db-chan)
              reply-chan' (async/chan 2)]
          (async/>! db-chan [q reply-chan'])
          (async/pipe (async/merge
                       [(async/to-chan (tw-get @cache q))
                        reply-chan']) reply-ch))
        (recur))
      (fn [ev ch]
        (swap! cache tw-update ev (get-time))
        (m ev ch)))))
