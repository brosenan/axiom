(ns di.core
  (:require [clojure.core.async :as async]))

(defn injector
  ([fulfilled]
   (let [fulfilled (merge fulfilled {:time (fn [] (.getTime (java.util.Date.)))
                                     :format-time str
                                     :println println
                                     :log (fn [level msg]
                                            )
                                     :err (fn [msg])
                                     :warn (fn [msg])
                                     :info (fn [msg])
                                     :debug (fn [msg])
                                     :trace (fn [msg])})
         chan (async/chan)]
     [(atom fulfilled)
      chan
      (async/pub chan first)]))
  ([]
   (injector {})))


(defmacro provide [resource inj & exprs]
  (let [resource (keyword resource)]
    `(async/go
       (when-not (contains? @(first ~inj) ~resource)
         (let [~'$value (do ~@exprs)]
           (when-not (nil? (System/getenv "AXIOM_DI_DEBUG"))
             (println "Providing " ~resource))
           (swap! (first ~inj) #(assoc % ~resource ~'$value))
           (async/>!! (second ~inj) [~resource ~'$value]))))))

(defmacro with-dependencies [inj deps & exprs]
  (let [keys (map keyword deps)]
    `(loop []
       (let [~'$fulfilled @(first ~inj)
             ~'$missing (first (filter #(not (contains? ~'$fulfilled %)) '~keys))]
         (cond (nil? ~'$missing)
               (let [{:keys ~deps} ~'$fulfilled]
                 ~@exprs)
               :else
               (let [~'$chan (async/chan)]
                 (when-not (nil? (System/getenv "AXIOM_DI_DEBUG"))
                   (println "Resource " ~'$missing " is missing.  Waiting..."))
                 (async/sub (~inj 2) ~'$missing ~'$chan)
                 (async/alts! [~'$chan
                               (async/timeout 100)])
                 (recur)))))))


(defmacro wait-for [inj key]
  `(let [~'$chan (async/chan)]
     (async/go
       (async/>! ~'$chan
                 (with-dependencies ~inj [~key]
                   ~key)))
     (async/<!! ~'$chan)))

(defmacro with-dependencies!! [inj deps & exprs]
  `(let [~'$chan (async/chan)]
     (async/go
       (async/>!! ~'$chan [(with-dependencies ~inj ~deps ~@exprs)]))
     (first (async/<!! ~'$chan))))
