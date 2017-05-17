(ns di.core2
  (:require [loom.graph :as graph]
            [loom.alg :as alg]))

(defn injector
  ([initial]
   (atom
    {:resources initial
     :rules []
     :shutdown []}))
  ([]
   (injector {})))


(defmacro provide [$ resource deps & exprs]
  `(swap! ~$ (fn [~'$inj] (update-in ~'$inj [:rules] (fn [~'$rules]
                                                       (conj ~'$rules
                                                              (with-meta
                                                                (fn [{:keys ~deps}] ~@exprs)
                                                                {:resource ~(keyword resource)
                                                                 :deps ~(vec (map keyword deps))})))))))

(defmacro do-with [$ deps & exprs]
  `(swap! ~$ (fn [~'$inj] (update-in ~'$inj [:rules] (fn [~'$rules]
                                                       (conj ~'$rules
                                                             (with-meta
                                                               (fn [{:keys ~deps}] ~@exprs)
                                                               {:deps ~(vec (map keyword deps))})))))))

(defn rule-edges [func]
  (let [deps (-> func meta :deps)
        deps-edges (for [dep deps]
                     [dep func])
        resource (-> func meta :resource)]
    (cond resource
          (conj deps-edges [func (keyword resource)])
          :else
          deps-edges)))

(defn startup [$]
  (let [edges (mapcat rule-edges (:rules @$))
        g (apply graph/digraph edges)
        ordered (alg/topsort g)
        funcs (filter fn? ordered)]
    (loop [funcs funcs
           resources (:resources @$)]
      (cond (empty? funcs)
            nil
            :else
            (let [func (first funcs)
                  res (-> func meta :resource)
                  deps (-> func meta :deps)]
              (cond (every? (partial contains? resources) deps)
                    (recur (rest funcs) (assoc resources res (func resources)))
                    :else
                    (recur (rest funcs) resources)))))))
