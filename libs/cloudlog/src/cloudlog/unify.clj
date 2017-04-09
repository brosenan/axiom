(ns cloudlog.unify
  (:require [permacode.core]
            [permacode.symbols :as sym]
            [clojure.set :as set]))

(permacode.core/pure
 (defn at-path [tree path]
   (if (empty? path)
     tree
     ; else
     (at-path `(~tree ~(first path)) (rest path))))

 (defn traverse [tree pred & [prefix]]
   (let [prefix (or prefix [])]
     (if (and (sequential? tree)
              (pred tree))
       (apply merge (for [i (range (count tree))]
                      (traverse (tree i) pred (concat prefix [i]))))
       ; else
       {prefix tree})))

 (defn conds-and-bindings [traventries var?]
   (if (empty? traventries)
     [[] []]
     ; else
     (let [[conds bindings] (conds-and-bindings (rest traventries) var?)
           [path subtree] (first traventries)]
       (if (var? subtree)
         [conds `[~subtree ~(at-path '$input$ (vec path)) ~@bindings]]
         ; else
         [(cons `(= ~(at-path '$input$ (vec path)) ~subtree) conds) bindings]))))
 
 (defmacro unify-fn [vars unifiable expr]
   (let [vars (set vars)
         term-has-vars (fn [term]
                         (not (empty? (set/intersection (sym/symbols term) vars))))
         travmap (traverse unifiable (constantly true))
         [conds bindings] (conds-and-bindings (map identity travmap) term-has-vars)]
     `(fn [~'$input$]
        (if (and ~@conds)
          (let ~bindings [~expr])
          ; else
          [])))))


