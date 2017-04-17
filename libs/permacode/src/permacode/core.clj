(ns permacode.core
  (:use [clojure.set :exclude [project]])
  (:require [permacode.symbols :refer :all]
            [permacode.validate :as validate]
            [clojure.string :as str]))

(defn ^:private create-bindings [syms env]
  (apply concat (for [sym syms
                      :when (empty? (namespace sym))] [sym `(~env '~sym)])))

(defn error [& args]
  (throw (Exception. (apply str args))))

(defn ^:private alias-map []
  (into {} (for [[alias ns] (ns-aliases *ns*)]
             [alias (symbol (str ns))])))

(defmacro pure [& defs]
  (let [allowed-symbols (clojure.set/union @validate/allowed-symbols
                                           (validate/symbols-for-namespaces (alias-map)))
        res `(do ~@defs)]
    (validate/validate-expr allowed-symbols res)
    res))

(defn eval-symbol [sym]
  (let [ns (symbol (namespace sym))
        local (symbol (name sym))]
    (validate/perm-require ns)
    @((ns-publics ns) local)))

(defn module-publics [ns]
  (validate/perm-require ns)
  (reduce-kv (fn [m k v] (assoc m k @v)) {} (ns-publics ns)))
