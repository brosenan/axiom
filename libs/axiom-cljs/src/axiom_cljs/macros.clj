(ns axiom-cljs.macros
  (:require [clojure.set :as set]))


(defn ^:private symbols [form]
  (clojure.walk/postwalk #(cond (symbol? %) #{%}
                                (sequential? %) (apply clojure.set/union %)
                                :else #{}) form))

(defmacro update-state [state ev args when]
  `(cond ~when
         (swap! ~state update-in [~args (-> ~ev
                                            (dissoc :ts)
                                            (dissoc :change))] (fnil #(+ % (:change ~ev)) 0))))

(defn symbol-map [expr]
  (into {}
        (for [sym (symbols expr)]
          [(keyword sym) sym])))

(defn comparator [fact order-by]
  `(fn [a# b#]
     (compare (let [{:keys [~@(symbols fact)]} a#]
                ~order-by)
              (let [{:keys [~@(symbols fact)]} b#]
                ~order-by))))

(defmacro user [host]
  `@(:identity ~host))

(defmacro defview [name args fact &
                   {:keys [store-in when order-by readers writers]
                    :or {store-in `(atom nil)
                         when true
                         order-by []
                         readers #{}
                         writers #{'$user}}}]
  (let [fact-name (-> fact first str (subs 1))]
    `(defonce ~name
       (let [state# ~store-in
             hosts# (atom #{})]
         (reset! state# {})
         (fn [host# ~@args]
           (when-not (contains? @hosts# host#)
             ((:sub host#) ~fact-name
              (fn [ev#]
                (let [~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
                  (update-state state# ev# ~args ~when))))
             (swap! hosts# conj host#))
           (-> (cond (contains? @state# ~args)
                     (-> (->> (for [[ev# c#] (@state# ~args)
                                    :when (> c# 0)]
                                (let [varmap# (let [~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
                                                ~(symbol-map fact))]
                                  (-> varmap#
                                      (assoc :-readers (:readers ev#))
                                      (assoc :-writers (:writers ev#))
                                      (assoc :del! #((:pub host#)
                                                     (-> ev#
                                                         (assoc :ts ((:time host#)))
                                                         (assoc :change (- c#)))))
                                      (assoc :swap! (fn [func# & args#]
                                                      ((:pub host#)
                                                       (-> ev#
                                                           (assoc :ts ((:time host#)))
                                                           (assoc :change c#)
                                                           (assoc :removed (:data ev#))
                                                           (assoc :data
                                                                  (let [{:keys [~@(symbols (drop 2 fact))]} (apply func# varmap# args#)]
                                                                    ~(vec (drop 2 fact)))))))))))
                              (sort ~(comparator fact order-by)))
                         (ax/merge-meta {:pending false}))
                     :else
                     (do
                       ((:pub host#) {:kind :reg
                                      :name ~fact-name
                                      :key ~(-> fact second)
                                      :get-existing true})
                       (ax/merge-meta '() {:pending true})))
               (ax/merge-meta {:add (fn [{:keys [~@(set/difference (symbols fact) (symbols args))]}]
                                      (let [~'$user (user host#)]
                                        ((:pub host#)
                                         {:kind :fact
                                          :name ~fact-name
                                          :key ~(second fact)
                                          :data [~@(drop 2 fact)]
                                          :ts ((:time host#))
                                          :change 1
                                          :writers ~writers
                                          :readers ~readers})))})))))))

(defn ^:private parse-target-form [[name & args]]
   (let [[in out] (split-with (partial not= '->) args)]
     [name in (rest out)]))

(defmacro defquery [name args query &
                   {:keys [store-in when order-by]
                    :or {store-in `(atom nil)
                         when true
                         order-by []}}]
  (let [[pred-name inputs outputs] (parse-target-form query)
        pred-name (-> pred-name str (subs 1))]
    `(defonce ~name
       (let [id-map# (atom {})
             state# ~store-in
             hosts# (atom #{})]
         (reset! ~store-in {})
         (fn [host# ~@args]
           (when-not (contains? @hosts# host#)
             ((:sub host#) ~(str pred-name "!")
              (fn [ev#]
                (let [args# (@id-map# (:key ev#))
                      [~@outputs] (:data ev#)]
                  (update-state state# ev# args# ~when))))
             (swap! hosts# conj host#))
           (cond (contains? @state# ~args)
                 (-> (->> (for [[ev# c#] (@state# ~args)
                                :when (> c# 0)]
                            (let [[~@outputs] (:data ev#)]
                              ~(symbol-map outputs)))
                          (sort ~(comparator outputs order-by)))
                     (with-meta {:pending false}))
                 :else
                 (let [uuid# ((:uuid host#))]
                   (swap! id-map# assoc uuid# ~args)
                   ((:pub host#) {:kind :reg
                                  :name ~(str pred-name "!")
                                  :key uuid#})
                   ((:pub host#) {:kind :fact
                                  :name ~(str pred-name "?")
                                  :key uuid#
                                  :data [~@inputs]
                                  :ts ((:time host#))
                                  :change 1
                                  :writers #{(user host#)}
                                  :readers #{(user host#)}})
                   (-> '()
                       (with-meta {:pending true})))))))))
