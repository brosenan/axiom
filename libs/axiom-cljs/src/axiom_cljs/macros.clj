(ns axiom-cljs.macros
  (:require [cljs.core.async.macros :refer [go go-loop]]))


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

(defmacro defview [name args host fact &
                   {:keys [store-in when order-by]
                    :or {store-in `(atom nil)
                         when true
                         order-by []}}]
  (let [fact-name (-> fact first str (subs 1))]
    `(defonce ~name
       (let [sub-chan# (async/chan 1)
             state# ~store-in]
         (reset! state# {})
         ((:sub ~host) ~fact-name
          (fn [ev#]
            (let [~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
              (update-state state# ev# ~args ~when))))
         (fn ~args
           (cond (contains? @state# ~args)
                 (-> (->> (for [[ev# c#] (@state# ~args)
                                :when (> c# 0)]
                            (-> (let [~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
                                  ~(symbol-map fact))
                                (assoc :-readers (:readers ev#))
                                (assoc :-writers (:writers ev#))
                                (assoc :-delete! #((:pub ~host)
                                                   (-> ev#
                                                       (assoc :ts ((:time ~host)))
                                                       (assoc :change (- c#)))))
                                (assoc :-swap! (fn [func# & args#]
                                                 ((:pub ~host)
                                                  (-> ev#
                                                      (assoc :ts ((:time ~host)))
                                                      (assoc :change c#)
                                                      (assoc :removed (:data ev#))
                                                      (assoc :data
                                                             (let [{:keys ~(vec (symbols (drop 2 fact)))} (apply func# ev# args#)]
                                                               ~(vec (drop 2 fact))))))))))
                          (sort ~(comparator fact order-by)))
                     (with-meta {:pending false
                                 :add (fn [{:keys ~(vec (symbols fact))}]
                                        ((:pub ~host)
                                         {:kind :fact
                                          :name ~fact-name
                                          :key ~(second fact)
                                          :data ~(vec (drop 2 fact))
                                          :ts ((:time ~host))
                                          :change 1
                                          :writers #{(user ~host)}
                                          :readers #{}}))}))
                 :else
                 (do
                   ((:pub ~host) {:kind :reg
                                  :name ~fact-name
                                  :key ~(-> fact second)})
                   (with-meta '() {:pending true}))))))))

(defn ^:private parse-target-form [[name & args]]
   (let [[in out] (split-with (partial not= '->) args)]
     [name in (rest out)]))

(defmacro defquery [name args host query &
                   {:keys [store-in when order-by]
                    :or {store-in `(atom nil)
                         when true
                         order-by []}}]
  (let [[pred-name inputs outputs] (parse-target-form query)
        pred-name (-> pred-name str (subs 1))]
    `(defonce ~name
       (let [id-map# (atom {})
             sub-chan# (async/chan 1)
             state# ~store-in]
         (reset! ~store-in {})
         ((:sub ~host) ~(str pred-name "!")
          (fn [ev#]
            (let [args# (@id-map# (:key ev#))
                  [~@outputs] (:data ev#)]
              (update-state state# ev# args# ~when))))
         (fn ~args
           (cond (contains? @state# ~args)
                 (-> (->> (for [[ev# c#] (@state# ~args)
                                :when (> c# 0)]
                            (let [[~@outputs] (:data ev#)]
                              ~(symbol-map outputs)))
                          (sort ~(comparator outputs order-by)))
                     (with-meta {:pending false}))
                 :else
                 (let [uuid# ((:uuid ~host))]
                   (swap! id-map# assoc uuid# ~args)
                   ((:pub ~host) {:kind :reg
                                  :name ~(str pred-name "!")
                                  :key uuid#})
                   ((:pub ~host) {:kind :fact
                                  :name ~(str pred-name "?")
                                  :key uuid#
                                  :data [~@inputs]
                                  :ts ((:time ~host))
                                  :change 1
                                  :writers #{(user ~host)}
                                  :readers #{(user ~host)}})
                   (-> '()
                       (with-meta {:pending true})))))))))
