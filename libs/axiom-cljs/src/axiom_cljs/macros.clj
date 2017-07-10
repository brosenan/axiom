(ns axiom-cljs.macros)

(defmacro defview [name args host fact]
  `(defonce ~name (fn [])))
