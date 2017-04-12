(ns dynamo.srv
  (:require [taoensso.faraday :as far]
            [clojure.set :as set]))

(def ddb-config (atom nil))
(def curr-tables (atom #{}))

(defn init [config]
  (reset! ddb-config config)
  (swap! curr-tables (partial set/union (set (far/list-tables config))))
  nil)

(defn store-fact
  {:reg {:kind :fact}}
  [ev])
