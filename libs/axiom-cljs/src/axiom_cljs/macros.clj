(ns axiom-cljs.macros
  (:require [cljs.core.async.macros :refer [go go-loop]]))


(defn ^:private symbols [form]
  (clojure.walk/postwalk #(cond (symbol? %) #{%}
                                (sequential? %) (apply clojure.set/union %)
                                :else #{}) form))

(defmacro defview [name args host fact & {:keys [store-in] :or {store-in `(atom nil)}}]
  (let [fact-name (-> fact first str (subs 1))]
    `(let [sub-chan# (async/chan 1)
           state# ~store-in]
       (reset! state# {})
       (async/sub (:pub ~host) ~fact-name sub-chan#)
       (defonce ~name (do
                        (go-loop []
                          (let [ev# (async/<! sub-chan#)
                                ~(vec (rest fact)) (cons (:key ev#) (:data ev#))]
                            (swap! state# update-in [~args] #(conj % ~(into {}
                                                                            (for [sym (symbols fact)]
                                                                              [(keyword sym) sym]))))
                            (recur)))
                        (fn ~args
                          (go
                            (cljs.core.async/>! (:to-host ~host) {:kind :reg
                                                                  :name ~fact-name
                                                                  :key ~(-> fact second)}))
                          (with-meta '() {:pending true})))))))
