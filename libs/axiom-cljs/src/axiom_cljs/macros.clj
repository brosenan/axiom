(ns axiom-cljs.macros
  (:require [cljs.core.async.macros :refer [go go-loop]]))


(defn ^:private symbols [form]
  (clojure.walk/postwalk #(cond (symbol? %) #{%}
                                (sequential? %) (apply clojure.set/union %)
                                :else #{}) form))

(defmacro defview [name args host fact &
                   {:keys [store-in when] :or {store-in `(atom nil)
                                               when true}}]
  (let [fact-name (-> fact first str (subs 1))]
    `(defonce ~name
       (let [sub-chan# (async/chan 1)
             state# ~store-in]
         (reset! state# {})
         (async/sub (:pub ~host) ~fact-name sub-chan#)
         (go-loop []
           (let [ev# (async/<! sub-chan#)
                 ~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
             (cond ~when
                   (swap! state# update-in [~args (-> ev#
                                                      (dissoc :ts)
                                                      (dissoc :change))] (fnil #(+ % (:change ev#)) 0)))
             (recur)))
         (fn ~args
           (cond (contains? @state# ~args)
                 (-> (for [[ev# c#] (@state# ~args)
                           :when (> c# 0)]
                       (-> (let [~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
                             ~(into {}
                                    (for [sym (symbols fact)]
                                      [(keyword sym) sym])))
                           (assoc :-readers (:readers ev#))
                           (assoc :-writers (:writers ev#))
                           (assoc :-delete! #(go
                                               (async/>! (:to-host ~host)
                                                         (-> ev#
                                                             (assoc :ts ((:time ~host)))
                                                             (assoc :change (- c#))))))
                           (assoc :-swap! (fn [func# & args#]
                                            (go
                                              (async/>! (:to-host ~host)
                                                        (-> ev#
                                                            (assoc :ts ((:time ~host)))
                                                            (assoc :change c#)
                                                            (assoc :removed (:data ev#))
                                                            (assoc :data
                                                                   (let [{:keys ~(vec (symbols (drop 2 fact)))} (apply func# ev# args#)]
                                                                     ~(vec (drop 2 fact)))))))))))
                     (with-meta {:pending false
                                 :add (fn [{:keys ~(vec (symbols fact))}]
                                        (go
                                          (async/>! (:to-host ~host) {:kind :fact
                                                                      :name ~fact-name
                                                                      :key ~(second fact)
                                                                      :data ~(vec (drop 2 fact))
                                                                      :ts ((:time ~host))
                                                                      :change 1
                                                                      :writers #{(:identity ~host)}
                                                                      :readers #{}})))}))
                 :else
                 (do
                   (go
                     (cljs.core.async/>! (:to-host ~host) {:kind :reg
                                                           :name ~fact-name
                                                           :key ~(-> fact second)}))
                   (with-meta '() {:pending true}))))))))

