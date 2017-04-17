(ns permacode.publish
  (:require [permacode.validate :as validate]
            [loom.graph :as graph]
            [loom.alg :as alg]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn get-ns [file]
  (with-open [f (java.io.PushbackReader. (io/reader file))]
    (binding [*read-eval* false]
      (read f))))

(defn pair-fn [x y]
  (fn [u]
    [(x u) (y u)]))

(defn build-plan [seq]
  (let [ns-seq (map (pair-fn get-ns identity) seq)
        ns-to-file (into {} (for [name validate/white-listed-ns]
                              [(symbol name) :external]))
        ns-to-file (into ns-to-file (for [[[ns' name & _] file] ns-seq]
                                      [name file]))
        edges (for [[[ns' name & clauses] _] ns-seq
                    [require' & specs] clauses
                    [dep & _] specs]
                [dep name])
        edges (filter (fn [[dep name]] (not (string/starts-with? (str name) "perm."))) edges)
        nodes (set (map second edges))
        edges (filter (fn [[dep name]] (not (string/starts-with? (str dep) "perm."))) edges)
        graph (apply graph/digraph (concat edges nodes))
        sort (alg/topsort graph)]
    (doseq [item sort]
      (when-not (ns-to-file item)
        (throw (Exception. (str "Unmet dependency: " item)))))
    (into [] (comp (map ns-to-file)
                   (filter #(not= % :external))) sort)))

(defn convert-dep [dep hashes]
  (if-let [hash (hashes dep)]
    hash
    ; else
    dep))

(defn convert-clauses [clauses hashes]
  (for [[require' & specs] clauses
        [dep & opts] specs]
    [require' (vec (concat [(convert-dep dep hashes)] opts))]))

(defn hash-file [[hash unhash] file hashes]
  (let [content (-> (str "[" (slurp file)  "]")
                    read-string)
        _ (validate/validate content (concat validate/white-listed-ns (map str (keys hashes))))
        [[ns' name & clauses] & exprs] content
        hash-code (hash (concat [(concat [ns' name] (convert-clauses clauses hashes))] exprs))]
    (symbol (str "perm." hash-code))))

(defn hash-all [hasher dir]
  (let [files (into [] (filter #(re-matches #".*\.clj" (str %))) (file-seq dir))
        plan (build-plan files)]
    (loop [hashes {}
           plan plan]
      (if (empty? plan)
        hashes
        ; else
        (let [current (first plan)
              [ns' name & clauses] (get-ns current)]
          (recur (assoc hashes name (hash-file hasher current hashes)) (rest plan)))))))
