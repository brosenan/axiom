(ns di.core
  (:require [clojure.core.async :as async]))

(defn injector []
  (let [chan (async/chan)]
    [(atom {})
     chan
     (async/pub chan first)]))


(defmacro provide [resource inj & exprs]
  (let [resource (keyword resource)]
    `(async/go
       (let [~'$value (do ~@exprs)]
         (swap! (first ~inj) #(assoc % ~resource ~'$value))
         (async/>!! (second ~inj) [~resource ~'$value])))))

(defmacro with-dependencies [inj deps & exprs]
  (let [keys (map keyword deps)]
    `(loop []
       (let [~'$fulfilled @(first ~inj)
             ~'$missing (some #(not (contains? ~'$fulfilled %)) '~keys)]
         (cond (nil? ~'$missing)
               (let [{:keys ~deps} ~'$fulfilled]
                 ~@exprs)
               :else
               (let [~'$chan (async/chan)]
                 (async/sub (~inj 2) ~'$missing ~'$chan)
                 (async/alts! [~'$chan
                               (async/timeout 100)])
                 (recur)))))))


(def the-inj (injector))
