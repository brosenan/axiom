(ns cloudlog.core
  (:require [permacode.symbols :as symbols]
            [permacode.core]
            [clojure.set :as set]
            [clojure.string :as string]
            [cloudlog.unify :as unify]
            [cloudlog.graph :as graph]
            [cloudlog.interset :as interset]))

(permacode.core/pure
 (declare generate-rule-func)

 (defmacro by [set body]
   `(when (contains? (-> ~'$input$ meta :writers) ~set)
      ~body))

 (defmacro by-anyone [body]
   body)

 (defmulti propagate-symbols (fn [cond symbols] (first cond)) :default :no-bindings)
 (defmethod propagate-symbols :no-bindings [cond symbols]
   symbols)

 (defn binding-symbols [bindings cond]
   (symbols/symbols (map bindings (range 0 (count bindings) 2))))

 (defmethod propagate-symbols 'let [cond symbols]
   (set/union symbols (binding-symbols (second cond) cond)))

 (defmethod propagate-symbols 'for [cond symbols]
   (set/union symbols (binding-symbols (second cond) cond)))

 (defmulti process-conds (fn [conds symbols] (class (first conds))))

 ;; fact
 (defmethod process-conds  clojure.lang.IPersistentVector [conds symbols]
   (let [target (first conds)
         target-name (first target)]
     (if (= (count conds) 1)
       (do ; Target fact
         [[(vec (rest target))] {:target-fact [target-name (count (rest target))]}])
       ;; Continuation
       (let [[func meta] (generate-rule-func (first conds) (rest conds) symbols)
             key (second target)
             params (vec (set/intersection symbols (symbols/symbols func)))
             missing (set/difference (symbols/symbols key) symbols)
             meta {:continuation (with-meta `(fn [[~'$key$ ~@params]] ~func) meta)}]
         (when-not (empty? missing)
           (permacode.core/error "variables " missing " are unbound in the key for " (first target)))
         [`[[~key ~@params]] meta]))))

 ;; guard
 (defmethod process-conds  clojure.lang.ISeq [conds symbols]
   (let [cond (first conds)
         [body meta] (process-conds (rest conds) (propagate-symbols cond symbols))
         body (seq (concat cond [body]))
         meta (if (string/starts-with? (name (first cond)) "by")
                (assoc meta :checked true)
                meta)
         body (if (= (first cond) 'for)
                body
                ; else
                [body])]
     [`(reduce concat ~body) meta]))

 (defn fact-name [fact]
   (if (keyword? fact)
     fact
     ; else
     (if (symbol? fact)
       `(keyword (-> ~fact meta :ns str) (-> ~fact meta :name))
       ; else
       (keyword (-> fact meta :ns str) (-> fact meta name)))))
 
 (defn generate-rule-func [source-fact conds ext-symbols]
   (let [symbols (set/difference (symbols/symbols (rest source-fact)) ext-symbols)
         [body meta] (process-conds conds (set/union symbols ext-symbols))
         source-fact-name (first source-fact)
         meta (merge meta {:source-fact [(fact-name (first source-fact)) (count (rest source-fact))]
                           :checked (or (:checked meta)
                                        (symbol? source-fact-name))})
         vars (set symbols)
         term-has-vars (fn [term]
                         (not (empty? (set/intersection (symbols/symbols term) vars))))
         travmap (unify/traverse (vec (rest source-fact)) (constantly true))
         [conds bindings] (unify/conds-and-bindings (map identity travmap) term-has-vars)
         body (if (symbol? source-fact-name)
                `(when (contains? (-> ~'$input$ meta :writers) (-> ~source-fact-name meta :ns str))
                   ~body)
                ;; else
                body)
         func `(fn [~'$input$]
                 (if (and ~@conds)
                   (let ~bindings ~body)
                   ;; else
                   []))]
     [func meta]))

 (defn validate-rule [metadata]
   (loop [metadata metadata
          link 0]
     (when-not (:checked metadata)
       (permacode.core/error "Rule is insecure. " ((:source-fact metadata) 0) " is not checked."))
     (when (:continuation metadata)
       (recur (-> metadata :continuation meta) (inc link)))))

 (defmacro defrule [rulename args source-fact & body]
   (let [conds (concat body [`[~(keyword (str *ns*) (name rulename)) ~@args]])
         [func meta] (generate-rule-func source-fact conds #{})]
     (validate-rule meta)
     `(def ~rulename (with-meta ~func ~(merge meta {:ns *ns* :name (str rulename)})))))

 (defn append-to-keyword [keywd suffix]
   (keyword (namespace keywd) (str (name keywd) suffix)))

 (defn parse-target-form [[name & args]]
   (let [[in out] (split-with (partial not= '->) args)]
     [name in (rest out)]))

 (defmacro defclause [clausename target-form & body]
   (let [[pred args-in args-out] (parse-target-form target-form)
         source-fact `[~(append-to-keyword pred "?") ~'$unique$ ~@args-in]
         conds (concat body [`[~(append-to-keyword pred "!") ~'$unique$ ~@args-out]])
         [func meta] (generate-rule-func source-fact conds #{})
         meta (assoc meta :checked true)] ; The query is assumed to be checked
     (validate-rule meta)
     `(def ~clausename (with-meta ~func ~(merge meta {:ns *ns* :name (str clausename)})))))

 (defn with* [seq]
   (apply merge-with set/union
          (for [fact seq]
            (let [fact-name (first fact)
                  metadata (meta fact)
                  arity (-> fact rest count)]
              {[fact-name arity] #{(with-meta (vec (rest fact)) metadata)}}))))

 (defn apply-with-conf [rule readers]
   (fn [tuple]
     (let [tuple-readers (-> tuple meta :readers)
           results (rule tuple)]
       (for [res results]
         (with-meta res (merge (meta res) {:readers (interset/intersection readers tuple-readers)}))))))

 (defn simulate* [rule factmap & [readers]]
   (let [writer (-> rule meta :ns str)
         source-fact (-> rule meta :source-fact)
         after-first (->> (factmap source-fact)
                          (map (apply-with-conf rule readers))
                          (reduce concat)
                          (map (fn [x] (with-meta x (merge (meta x) {:writers #{writer}}))))
                          (into #{}))
         cont (-> rule meta :continuation)]
     (if cont
       (let [next-rules (map (fn [t] [(cont t) (-> t meta :readers)]) after-first)]
         (into #{} (reduce concat (for [[next-rule next-readers] next-rules]
                                    (simulate* (with-meta next-rule (merge
                                                                     {:ns (-> rule meta :ns)}
                                                                     (meta cont))) factmap next-readers)))))
       ;else
       after-first)))

 (defn simulate-with [rule & facts]
   (simulate* rule (with* facts)))

 (defmulti fact-table (fn [[name arity]] (class name)))

 (defmethod fact-table clojure.lang.Named [[name arity]]
   (str (namespace name) "/" (clojure.core/name name)))
 (defmethod fact-table clojure.lang.IFn [[name arity]]
   (let [ns (-> name meta :ns)
         name (-> name meta :name)]
     (str ns "/" name)))
 (prefer-method fact-table clojure.lang.Named clojure.lang.IFn)

 (defn rule-cont [rule]
   (loop [func rule
          res nil]
     (if-let [cont (-> func meta :continuation)]
       (recur cont (cons func res))
       ; else
       (reverse (cons func res)))))

 (defn rule-target-fact [rule]
   (let [conts (rule-cont rule)]
     (some identity (map (fn [x] (-> x meta :target-fact)) conts))))

 (defn sort-rules [rules]
   (let [inv-graph (reduce graph/merge-graph {} (for [rule rules]
                                                  (let [conts (rule-cont rule)]
                                                    {(rule-target-fact rule) (set (map (fn [x] (-> x meta :source-fact)) conts))})))
         inv-sort (graph/toposort inv-graph)
         target-fact-map (reduce (partial merge-with set/union) {} (for [rule rules]
                                                                     (let [conts (rule-cont rule)]
                                                                       {(rule-target-fact rule) #{rule}})))]
     (->> (reverse inv-sort)
          (map target-fact-map)
          (reduce concat)
          (filter identity))))

 (defn simulate-rules-with* [rules facts]
      (if (empty? rules)
        facts
        ; else
        (recur (rest rules) (merge-with set/union facts {(rule-target-fact (first rules)) (simulate* (first rules) facts)}))))
 
 (defn simulate-rules-with [rules & facts]
   (simulate-rules-with* (sort-rules rules) (with* facts)))

 (defn f [f & others]
   (with-meta f (loop [l others
                       m {}]
                  (if (empty? l)
                    m
                    ; else
                    (let [[k v & rest] l]
                      (recur rest (assoc m k v)))))))

 (defn preserve-meta [func]
   (fn [t] (with-meta (func t) (meta t))))
 
 (defn run-query [rules query arity writer readers & facts]
   (let [query-head (first query)
         query-body (rest query)
         key [(append-to-keyword query-head "?") (inc (count (rest query)))]
         facts (apply simulate-rules-with rules (vec (concat [(first key) :unique-id] query-body)) facts)]
     (->> [(append-to-keyword query-head "!") (inc arity)]
          facts
          (map (preserve-meta rest))
          (filter (fn [t] (interset/subset? readers (-> t meta :readers))))
          set))))

