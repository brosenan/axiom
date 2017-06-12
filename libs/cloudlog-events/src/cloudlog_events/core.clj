(ns cloudlog-events.core
  (:require [cloudlog.core :as cloudlog]
            [cloudlog.interset :as interset]
            [clojure.core.async :as async]))

(defn split-atomic-update [ev]
  (cond (:removed ev)
        (let [new-ev (dissoc ev :removed)
              old-ev (-> new-ev
                         (assoc :data (:removed ev))
                         (assoc :change (- (:change ev))))]
          [new-ev old-ev])
        :else [ev]))

(defn matching? [ev1 ev2]
  (and (= (+ (:change ev1) (:change ev2)) 0)
       (= (-> ev1
              (dissoc :data)
              (dissoc :change))
          (-> ev2
              (dissoc :data)
              (dissoc :change)))))

(defn find-atomic-update [ev coll]
  (cond (empty? coll) [nil coll]
        (matching? ev (first coll)) [(first coll) (rest coll)]
        :else
        (let [[match rst] (find-atomic-update ev (rest coll))]
          [match (cons (first coll) rst)])))

(defn join-atomic-updates [events]
  (cond (empty? events)
        events
        :else
        (let [[match rst] (find-atomic-update (first events) (rest events))]
          (cond match
                (let [joined (-> (first events)
                                 (assoc :removed (:data match)))]
                  (cons joined (join-atomic-updates rst)))
                :else
                (cons (first events) (join-atomic-updates (rest events)))))))

(defn emitter [rulefunc & {:keys [link mult readers rule-writers] :or {link 0
                                                                       mult 1
                                                                       readers interset/universe
                                                                       rule-writers #{(-> rulefunc meta :ns str)}}}]
  (let [single-event-emitter (fn [event]
                               (for [data (rulefunc (with-meta (vec (cons (:key event) (:data event)))
                                                      {:writers (:writers event)
                                                       :readers (:readers event)}))]
                                 (merge
                                  (merge event (if (-> rulefunc meta :target-fact)
                                                 {:name (-> rulefunc meta :target-fact cloudlog/fact-table)}
                                        ; else
                                                 {:kind :rule
                                                  :name (str (cloudlog/fact-table [rulefunc]) "!" link)}))
                                  {:key (first data)
                                   :data (rest data)
                                   :writers rule-writers
                                   :change (* (:change event) mult)
                                   :readers (interset/intersection (:readers event) readers)})))]
    (fn [event]
      (let [input-events (split-atomic-update event)]
        (join-atomic-updates (mapcat single-event-emitter input-events))))))

(defn multiplier [rulefunc index]
  (let [cont (loop [i index
                    func rulefunc]
               (if (> i 0)
                 (recur (dec i) (-> func meta :continuation))
                 ;; else
                 func))
        single-event-mult (fn [rule-ev fact-ev]
                            (let [rulefunc' (cont (cons (:key rule-ev) (:data rule-ev)))
                                  em (emitter (with-meta rulefunc' (meta cont))
                                              :link index
                                              :mult (:change rule-ev)
                                              :readers (:readers rule-ev)
                                              :rule-writers (:writers rule-ev))]
                              (em fact-ev)))]
    (fn [rule-ev fact-ev]
      (let [rule-evs (split-atomic-update rule-ev)]
        (join-atomic-updates (mapcat #(single-event-mult % fact-ev) rule-evs))))))


(defn accumulate
  ([] {})
  ([accum ev]
   (let [single-event-accum (fn [accum ev]
                              (merge-with + accum {(dissoc ev :change)
                                                   (:change ev)}))]
     (->> ev
          split-atomic-update
          (reduce single-event-accum accum)))))

(defn accumulated-events [accum]
  (->> (for [[ev ch] accum]
         (assoc ev :change ch))
       (filter #(> (:change %) 0))))

(defn accumulate-db-chan [db-chan]
  (let [my-chan (async/chan)]
    (async/go
      (loop []
        (let [my-resp-chan (async/chan)
              [req resp-chan] (async/<! my-chan)]
          (async/>! db-chan [req my-resp-chan])
          (let [accum (async/reduce accumulate (accumulate) my-resp-chan)
                accum (async/map accumulated-events [accum])
                accum (async/<! accum)]
            (loop [accum accum]
              (when-not (empty? accum)
                (async/>! resp-chan (first accum))
                (recur (rest accum))))
            (async/close! resp-chan)))
        (recur)))
    my-chan))


(defn source-fact-table [rulefunc link]
  (if (> link 0)
    (recur (-> rulefunc meta :continuation) (dec link))
    ;; else
    (-> rulefunc meta :source-fact cloudlog/fact-table)))

(defn matcher [rulefunc link db-chan]
  (let [mult (multiplier rulefunc link)
        db-chan (accumulate-db-chan db-chan)]
    (fn [ev out-chan]
      (async/go
        (let [db-reply-chan (async/chan)]
          (if (= (:kind ev) :fact)
            (do
              (async/>! db-chan [{:kind :rule
                                  :name (str (cloudlog/fact-table [rulefunc]) "!" (dec link))
                                  :key (:key ev)}
                                 db-reply-chan])
              (loop [rule-ev (async/<! db-reply-chan)]
                (when rule-ev
                  (doseq [out-ev (mult rule-ev ev)]
                    (async/>! out-chan out-ev))
                  (recur (async/<! db-reply-chan))))
              (async/close! out-chan))
            ;; else
            (do
              (async/>! db-chan [{:kind :fact
                                  :name (source-fact-table rulefunc link)
                                  :key (:key ev)}
                                 db-reply-chan])
              (loop [fact-ev (async/<! db-reply-chan)]
                (when fact-ev
                  (doseq [out-ev (mult ev fact-ev)]
                    (async/>! out-chan out-ev))
                  (recur (async/<! db-reply-chan))))
              (async/close! out-chan))))))))
