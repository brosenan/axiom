(ns di.core2)

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
                                                             [~(keyword resource)
                                                              ~(vec (map keyword deps))
                                                              (fn ~deps ~@exprs)]))))))
