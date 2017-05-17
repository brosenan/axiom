(ns di.core2
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [clojure.set :as set]))

(defn injector
  ([initial]
   (atom
    {:resources initial
     :rules []
     :shut-down []}))
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
           resources (:resources @$)
           shut-down-seq []]
      (cond (empty? funcs)
            (swap! $ #(-> %
                          (assoc :resources resources)
                          (assoc :shut-down shut-down-seq)))
            :else
            (let [func (first funcs)
                  res (-> func meta :resource)
                  deps (-> func meta :deps)]
              (cond (every? (partial contains? resources) deps)
                    (let [val (func resources)
                          [val shut-down-seq] (cond (and (map? val)
                                                         (:resource val)
                                                         (:shut-down val)
                                                         (= (count val) 2))
                                                    [(:resource val) (cons (:shut-down val) shut-down-seq)]
                                                    :else
                                                    [val shut-down-seq])]
                      (recur (rest funcs) (assoc resources res val) shut-down-seq))
                    :else
                    (recur (rest funcs) resources shut-down-seq)))))
    nil))

(defn shut-down [$]
  (doseq [func (:shut-down @$)]
    (func)))

(defmacro do-with! [$ deps & exprs]
  `(let [~'$existing (set (keys (:resources @~$)))
         ~'$missing (set/difference ~(set (map keyword deps))
                                    ~'$existing)]
     (cond (empty? ~'$missing)
           (let [{:keys ~deps} (:resources @~$)]
             ~@exprs)
           :else
           (throw (Exception. (str "Resource(s) " ~'$missing " are not available, but " ~'$existing " are"))))))
