(ns migrator.core
  (:require [permacode.core :as perm]))

(def _config (atom {}))

(defn init [config]
  (reset! _config config)
  nil)

(defn extract-version-rules
  {:reg {:kind :fact
         :name "axiom/version"}}
  [ev publish]
  (let [pubs (perm/module-publics (symbol (:key ev)))]
    (doseq [[k v] pubs]
      (when (-> v meta :source-fact)
        (publish {:name "axiom/rule"
                  :key (symbol (:key ev) (str k))})))))
