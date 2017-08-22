(ns cloudlog-events.testing
  (:require [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]
            [clojure.set :as set]
            [cloudlog.interset :as interset]))

(defn all-rules []
  (->> (all-ns)
       (mapcat ns-publics)
       (map second)
       (map deref)
       (filter #(-> % meta :source-fact nil? not))
       set
       clg/sort-rules))

(defn to-event [[name key data writers readers]]
  {:kind :fact
   :name name
   :key key
   :data data
   :ts 1
   :change 1
   :writers writers
   :readers readers})

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
          (let [result (->> (index index-key)
                            (map :data)
                            set)]
            (cond (empty? result)
                  {:keys (set (keys index))
                   :rules (->> (all-rules)
                               (map #(keyword (-> % meta :ns str) (-> % meta :name)))
                               set)}
                  :else result))
          :else
          (recur (rest rules) (process-rule (first rules) index)))))

(defn identity-set [user scenario s]
  (loop [groups (interset/enum-groups s)
         id-set #{user}]
    (cond (empty? groups)
          id-set
          (vector? (first groups))
          (let [[name & args] (first groups)]
            (recur (rest groups)
                   (interset/intersection id-set (set (for [res (apply-rules scenario [name user])]
                                                        `[~name ~@res])))))
          :else
          (recur (rest groups) id-set))))

(def ^:dynamic *scenario* nil)
(def ^:dynamic *user* nil)

(defmacro scenario [& body]
  `(binding [*scenario* (atom [])] ~@body))

(defmacro as [user & body]
  `(binding [*user* ~user] ~@body))

(defn emit
  ([fact]
   (when (nil? *user*)
     (throw (Exception. "emit can only be called from within an `as` block")))
   (emit fact #{*user*}))
  ([fact writers]
   (emit fact writers #{}))
  ([[name-kw key & data] writers readers]
   (when (nil? *scenario*)
     (throw (Exception. "emit can only be called from within a scenario"))) 
   (let [id-set (identity-set *user* @*scenario* writers)]
     (when-not (interset/subset? id-set writers)
       (throw (Exception. (str "Cannot emit fact. " *user* " is not a member of " (pr-str writers) ".")))))
   (swap! *scenario* conj [(str (symbol (namespace name-kw) (name name-kw))) key data writers readers])))

(defn query [[pred & args]]
  (when (nil? *user*)
    (throw (Exception. "query can only be called from within an `as` block")))
  (when (nil? *scenario*)
    (throw (Exception. "query can only be called from within a scenario")))
  (let [pred? (keyword (str (namespace pred) "/" (name pred) "?"))
        pred! (keyword (str (namespace pred) "/" (name pred) "!"))
        q [pred? :unique args #{*user*} #{*user*}]
        facts (conj @*scenario* q)]
    (apply-rules facts [pred! :unique])))

