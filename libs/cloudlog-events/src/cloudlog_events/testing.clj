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

(def ^:dynamic *scenario* nil)
(def ^:dynamic *user* nil)

(defn apply-rules [index-key]
  (when (nil? *scenario*)
    (throw (Exception. "apply-rules can only be called from within a scenario")))
  (let [facts @*scenario*]
    (loop [rules (all-rules)
           index (->> facts
                      (map to-event)
                      index-events)]
      (cond (empty? rules)
            (let [result (->> (index index-key)
                              (map #(with-meta (:data %) {:readers (:readers %)}))
                              set)]
              (cond (empty? result)
                    {:keys (set (keys index))
                     :rules (->> (all-rules)
                                 (map #(keyword (-> % meta :ns str) (-> % meta :name)))
                                 set)}
                    :else result))
            :else
            (recur (rest rules) (process-rule (first rules) index))))))

(defn identity-set [user s]
  (loop [groups (interset/enum-groups s)
         id-set #{user}]
    (cond (empty? groups)
          id-set
          (vector? (first groups))
          (let [[name & args] (first groups)
                results (apply-rules [name user])]
            (cond (set? results)
                  (recur (rest groups)
                         (interset/intersection id-set (set (for [res results]
                                                              `[~name ~@res]))))
                  :else
                  (recur (rest groups) id-set)))
          :else
          (recur (rest groups) id-set))))

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
   (let [id-set (identity-set *user* writers)]
     (when-not (interset/subset? id-set writers)
       (throw (Exception. (str "Cannot emit fact. " *user* " is not a member of " (pr-str writers) ".")))))
   (swap! *scenario* conj [(str (symbol (namespace name-kw) (name name-kw))) key data writers readers])))

(def ^:private unique
  (let [next-val (atom 0)]
    (fn [] (str "unique-" (swap! next-val inc)))))

(defn query [[pred & args]]
  (when (nil? *user*)
    (throw (Exception. "query can only be called from within an `as` block")))
  (when (nil? *scenario*)
    (throw (Exception. "query can only be called from within a scenario")))
  (let [pred? (keyword (str (namespace pred) "/" (name pred) "?"))
        pred! (keyword (str (namespace pred) "/" (name pred) "!"))
        u (unique)]
    (emit `[~pred? ~u ~@args] #{*user*} #{*user*})
    (let [tuples (apply-rules [pred! u])]
      (cond (set? tuples)
            (set (filter (fn [tuple]
                           (let [readers (-> tuple meta :readers)
                                 id-set (identity-set *user* readers)]
                             (interset/subset? id-set readers))) tuples))
            :else tuples))))

