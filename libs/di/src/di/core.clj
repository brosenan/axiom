(ns di.core
  (:require [clojure.core.async :as async]))

(defn injector
  ([fulfilled]
   (let [chan (async/chan)]
     [(atom fulfilled)
      chan
      (async/pub chan first)]))
  ([]
   (injector {})))


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

(defmacro wait-for [inj key]
  `(let [~'$chan (async/chan)]
     (async/go
       (async/>! ~'$chan
                 (with-dependencies ~inj [~key]
                   ~key)))
     (async/<!! ~'$chan)))
