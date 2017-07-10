(ns axiom-cljs.tests
  (:require [devcards.core :refer [deftest]]))

(defmacro fact [& body]
  `(deftest ~@body))

