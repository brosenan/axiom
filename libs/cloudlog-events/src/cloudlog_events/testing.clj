(ns cloudlog-events.testing
  (:require [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]
            [clojure.set :as set]))

(defn all-rules []
  (->> (all-ns)
       (mapcat ns-publics)
       (map second)
       (map deref)
       (filter #(-> % meta :source-fact nil? not))
       set
       clg/sort-rules))

(defn to-event [[kw key & args]]
  {:kind :fact
   :name (str (namespace kw) "/" (name kw))
   :key key
   :data (take (dec (count args)) args)
   :ts 1
   :change 1
   :writers (last args)
   :readers #{}})

(defn merge-indexes
  ([] {})
  ([a b]
   (merge-with set/union a b)))

(defn index-events [evs]
  (->> evs
       (map (fn [ev]
              {[(keyword (:name ev)) (:key ev)] #{ev}}))
       (reduce merge-indexes)))

(defn process-initial-link [rulefunc index]
  (let [em (ev/emitter rulefunc)]
    (->> (for [[[name key] evs] index
               :when (= name (-> rulefunc meta :source-fact first))
               ev evs]
           (em ev))
         (reduce concat)
         set)))

(defn process-link [linkfunc index rules]
  (let [mult (ev/multiplier linkfunc 0)]
    (->> (for [rule-ev rules
               fact-ev (index [(-> linkfunc meta :source-fact first) (:key rule-ev)])]
           (mult rule-ev fact-ev))
         (reduce concat)
         set)))

(defn process-rule [rulefunc index]
  (->> (loop [rules (process-initial-link rulefunc index)
              link rulefunc]
         (let [cont (-> link meta :continuation)]
           (cond (nil? cont)
                 rules
                 :else
                 (recur (process-link cont index rules) cont))))
       index-events
       (merge-indexes index)))

(defn apply-rules [facts index-key]
  (loop [rules (all-rules)
         index (->> facts
                    (map to-event)
                    index-events)]
    (cond (empty? rules)
          (->> (index index-key)
               (map :data)
               set)
          :else
          (recur (rest rules) (process-rule (first rules) index)))))

(defn query [facts [pred & args]]
  (let [pred? (keyword (str (namespace pred) "/" (name pred) "?"))
        pred! (keyword (str (namespace pred) "/" (name pred) "!"))
        q `[~pred? :unique ~@args #{}]
        facts (conj facts q)]
    (apply-rules facts [pred! :unique])))
