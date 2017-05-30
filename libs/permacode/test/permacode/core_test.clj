(ns permacode.core-test
  (:require [permacode.core :refer :all]
            [permacode.hasher :as hasher]
            [permacode.hasher-test :as hasher-test]
            [permacode.publish :as publish]
            [permacode.validate :as validate]
            [midje.sweet :refer :all]
            [clojure.java.io :as io])
  (:require [permacode.symbols :as symbols]
            [clojure.set :as set]
            [clojure.string :as string]))

[[:chapter {:title "Introduction"}]]
"Just like the term *permalink* refers to a URL that will always link to the same content,
*permacode* refers to code that will always behave the same way."

"There are a few challenges in acheiving this in general code:
- State changes.  Imperative code can store state which affects its own execution.
- The world changes.  Code that queries the state of the world (e.g., `(System/currentTimeMillis)`) will give different results when the state of the world changes.
- Code changes.  One version of the code will not behave the same way as its predecessors.
"

"Making permacode possible in the context of Clojure is a two-step process.
First, we need to extract the *purely-functional core* of Clojure, to avoid
the first to issues.
Purely-functional code is not affected by the outer world and does not affect it,
guaranteeing that the same code will always run the same way.
To address the second problem we add *content addressing* to replace Clojure's module system."

[[:section {:title "A Purely-Functional Clojure"}]]
"permacode attempts to create a purely-functional dialect of Clojure.
This is a stripped-down Clojure, with only the purely-functional parts, including:
- Functions (well duh...)
- `let`, `loop`, `if` etc.
- maps, sets, vectors and lists
- A subset of the `clojure.core` library functions and macros, as well as
- `clojure.string`, `clojure.set` and potentially other pure libraries.

It does not include:
- Variables
- Atoms, refs, agents, etc.
- Java interop
- Imperative functions from `clojure.core`
- Arbitrary (non-permacode) libraries"

[[:section {:title "Content Addressing"}]]
"The term *content addressing* means that some objects (in our case, permacode modules)
are *addressed* by their *content*, as opposed to addressing them by *name*, which is
the way Clojure works by default."

"In Clojure, a `:require` clause states a name of a module, relative to the classpath
of the containing project.  This has two problems as far as permacode is concerned.
First, it relies on the concept of a *project*, so in two different projects there
could be a different module with the same name.
Second, while a name is stated explicitly, the *version* is not.
It is stated in the project description, but that could just as well be a `SNAPSHOT`
version, which is not a version at all."

"In Permacode, we would like code artifacts to be explicitly stated.
Content addressing allows us to do that, because instead of referring to modules
by their names, we refer to them by their *content*."

"Someone reading these lines could think, what is the point of having modules in the first
place, if we refer to them by their content, that  is, including them instead if `:require`ing them?
Well, the answer is that we do not *include* one module in another, we just use a *cryptographic hash*
over its content as its name.
This is similar to how git represents objects like directories and commits.
A directory in git is merely a list of hash codes for versions of underlying files and sub-dirs.
A commit contains the hash code of the root directory at the time of the commit.
This data structure is called a [Merkle Tree](https://en.wikipedia.org/wiki/Merkle_tree)."

"To support this, we use an abstraction of a *hasher*, some data-store that can either *hash*
an s-expression (serialize, hash and store its content, returning the hash code), and
*unhash* a hash-code (retrieve from the data-store, de-serialize and return the s-expression)."

"With such a hasher in place we can:
1. Given a set of Clojure files (typically the source tree of a Clojure project),
  - Validate that all *.clj files conform with permacode, and if so --
  - Hash all these files, modifying them to reference each other by hash rather than by name.
  - Return a map from module name to module hash.
2. Given a module hash, load the module.
3. Given a fully-qualified symbol (where the namespace is a permacode hash), return the value of that symbol.  This value is guaranteed to always be the same, regardless of where and when it is evaluated."

[[:chapter {:title "pure: Compile-time Verification"}]]
"A permacode module should be wrapped in a `permacode.core/pure` macro.
This macro validates the underlying code under the module's own namespace.
Validation involves macro expansion, and macros are expanded based on how they are defined
relative to that module."

"A valid set of definitions evaluates to a `do` block with the same content."
(fact
 (pure
  (defn foo [x y]
    (+ x y))
  (defn bar [x y]
    (* x y)))
 =expands-to=> (do
                 (defn foo [x y]
                   (+ x y))
                 (defn bar [x y]
                   (* x y))))

"For definitions that use disallowed symbols `pure` throws an exception during macro expansion."
(fact
 (macroexpand '(pure
                (def some-atom (atom 0))))
 => (throws "symbols #{atom} are not allowed"))

"Allowed symbols should be available unqualified or fully-qualified."
(fact
 (pure
  (def x (+ 1 2))
  (def y (clojure.core/+ 2 3)))
 =expands-to=> (do
                 (def x (+ 1 2))
                 (def y (clojure.core/+ 2 3))))

"Some namespaces, such as `clojure.string` and `clojure.set` are white-listed, and all their publics are allowed."
(fact
 (pure
  (def x (clojure.string/join ", " (clojure.set/union #{1 2} #{3})))))

"When namespaces are aliased (like `clojure.string` is aliased to `string` in this file),
symbols referring to the alias are also allowed."
(fact
 (pure
  (def x (string/join ", " (set/union #{1 2} #{3})))))

[[:section {:title "Stress Testing"}]]
"The following is a usage example coming from [cloudlog](cloudlog.html).
It is supposed to be all pure, so it's a good test case..."

(permacode.core/pure
 (declare generate-rule-func)

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

                                        ; fact
 (defmethod process-conds  clojure.lang.IPersistentVector [conds symbols]
   (let [target (first conds)
         target-name (first target)]
     (if (= (count conds) 1)
       (do ; Target fact
         [[(vec (rest target))] {:target-fact [target-name (count (rest target))]}])
                                        ; Continuation
       (let [[func meta] (generate-rule-func (first conds) (rest conds) symbols)
             key (second target)
             params (vec (set/intersection symbols (symbols/symbols func)))
             missing (set/difference (symbols/symbols key) symbols)
             meta {:continuation (with-meta `(fn [[key# ~@params]] ~func) meta)}]
         (when-not (empty? missing)
           (permacode.core/error "variables " missing " are unbound in the key for " (first target)))
         [`[[~key ~@params]] meta]))))

                                        ; guard
 (defmethod process-conds  clojure.lang.ISeq [conds symbols]
   (let [cond (first conds)
         [body meta] (process-conds (rest conds) (propagate-symbols cond symbols))
         body (seq (concat cond [body]))
         meta (if (string/starts-with? (str (first cond)) "by")
                (assoc meta :checked true)
                meta)]
     (if (= (first cond) 'for)
       [`(apply concat ~body) meta]
                                        ; else
       [body meta])))

 (defn generate-rule-func [source-fact conds ext-symbols]
   (let [symbols (set/difference (symbols/symbols (rest source-fact)) ext-symbols)
         [body meta] (process-conds conds (set/union symbols ext-symbols))
         meta (merge meta {:source-fact [(first source-fact) (count (rest source-fact))]})
         vars (vec symbols)
         func `(fn [input#]
                 ~(if (empty? vars)
                                        ; No unbound variables
                    `(if (= input# [~@(rest source-fact)])
                       ~body
                       [])
                                        ; vars contains the unbound variables
                    `(let [poss# ((unify/unify-fn ~vars [~@(rest source-fact)] ~vars) input#)]
                       (apply concat (for [~vars poss#] 
                                       ~body)))))]
     [func meta]))

 (defn validate-rule [metadata]
   (loop [metadata metadata
          link 0]
     (when-not (:checked metadata)
       (permacode.core/error "Rule is insecure. Link " link " is not checked."))
     (when (:continuation metadata)
       (recur (-> metadata :continuation meta) (inc link)))))

 (defmacro defrule [rulename args source-fact & body]
   (let [conds (concat body [`[~(keyword (str *ns*) (name rulename)) ~@args]])
         [func meta] (generate-rule-func source-fact conds #{})]
     (validate-rule meta)
     `(def ~rulename (with-meta ~func ~(merge meta {:ns *ns* :name (str rulename)})))))

 (defn append-to-keyword [keywd suffix]
   (keyword (namespace keywd) (str (name keywd) suffix)))

 (defmacro defclause [clausename pred args-in args-out & body]
   (let [source-fact `[~(append-to-keyword pred "?") unique# ~@args-in]
         conds (concat body [`[~(append-to-keyword pred "!") unique# ~@args-out]])
         [func meta] (generate-rule-func source-fact conds #{})]
     `(def ~clausename (with-meta ~func ~(merge meta {:ns *ns* :name (str clausename)})))))

 (defn with* [seq]
   (apply merge-with set/union
          (for [fact seq]
            (let [fact-name (first fact)
                  metadata (meta fact)
                  arity (-> fact rest count)]
              {[fact-name arity] #{(with-meta (vec (rest fact)) metadata)}}))))

 (defn simulate* [rule factmap]
   (let [source-fact (-> rule meta :source-fact)
         input-set (factmap source-fact)
         after-first (into #{} (apply concat (map rule input-set)))
         cont (-> rule meta :continuation)]
     (if cont
       (let [next-rules (map cont after-first)]
         (into #{} (apply concat (for [next-rule next-rules]
                                   (simulate* (with-meta next-rule (meta cont)) factmap)))))
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

 (defmacro by [set body]
   `(when (contains? (-> input# meta :writers) ~set)
      ~body))

 (defmacro by-anyone [body]
   body))

[[:chapter {:title "error: A Replacement for throw"}]]
"We do not allow pure code to `throw`, because throwing exceptions involves creating Java classes.
Instead, we provide the `error` function, which throws an `Exception` with the given string."
(fact
 (error 1000 " bottles of beer on the wall") => (throws #"1000 bottles of beer"))

[[:chapter {:title "eval-symbol: Evaluate a Permacode Definition" :tag "eval-symbol"}]]
"At the end, all we want is to be able to take a certain definition and evaluate it.
For example, consider the following Permacode module:"
(def my-module
  '[(ns example.my-module
      (:require [clojure.string :as str]
                [permacode.core]))
    (permacode.core/pure
     (defn tokenize [text]
       (str/split text #"[ ,.!?:]+")))])

(def my-other-module
  '[(ns example.my-other-module
      (:require [perm.QmXGe3DdhRGKfLgs1Dp9sNa2VFktfqj32XicKRcUeLXMxG :as mine]
                [permacode.core]))
    (permacode.core/pure
     (defn extract-hashtags [text]
       (->> text mine/tokenize (filter (fn [x] (clojure.string/starts-with? x "#")))))
     (def foo (with-meta [2] {:some :meta})))])

"Let's save these to files."
(fact
  (def example-dir (io/file "/tmp/permacode.core/example"))
  (.mkdirs example-dir)
  (with-open [f (io/writer (io/file example-dir "my_module.clj"))]
    (doseq [expr my-module]
      (.write f (pr-str expr))))
  (with-open [f (io/writer (io/file example-dir "my_other_module.clj"))]
    (doseq [expr my-other-module]
      (.write f (pr-str expr)))))

"Now let's publish the `example` directory."
(fact
 (remove-ns 'perm.QmXGe3DdhRGKfLgs1Dp9sNa2VFktfqj32XicKRcUeLXMxG)
 (remove-ns 'perm.QmVxcd8ooha7JtZvh5AD3QhAiLMm7Zjm5YAqkXitVbVSfo)
 (def hasher (hasher/nippy-multi-hasher (hasher/atom-store)))
  (def published
    (publish/hash-all hasher example-dir))
  published => map?
  published =not=> empty?)

"Now we want to use the `extrat-hashtags` function.  To do so we use `eval-symbol`:"
(fact
 (published 'example.my-module) => 'perm.QmXGe3DdhRGKfLgs1Dp9sNa2VFktfqj32XicKRcUeLXMxG
 (published 'example.my-other-module) => 'perm.QmVxcd8ooha7JtZvh5AD3QhAiLMm7Zjm5YAqkXitVbVSfo
 (binding [validate/*hasher* hasher]
   (let [extract-hashtags (eval-symbol 'perm.QmVxcd8ooha7JtZvh5AD3QhAiLMm7Zjm5YAqkXitVbVSfo/extract-hashtags)]
     (extract-hashtags "These #days #tweets are all about #hashtags...") => ["#days" "#tweets" "#hashtags"])))

"Metadata is maintained (the actual value is returned, not a variable pointing to it)."
(fact
 (binding [validate/*hasher* hasher]
   (let [foo (eval-symbol 'perm.QmVxcd8ooha7JtZvh5AD3QhAiLMm7Zjm5YAqkXitVbVSfo/foo)]
     (-> foo meta :some) => :meta)))

[[:chapter {:title "module-publics: Get All Symbols" :tag "module-publics"}]]
"`module-publics` (similar to `ns-publics` in `clojure.core`) returns all the public definitions in a permacode module
in form of a map."
(fact
 (binding [validate/*hasher* hasher]
   (let [pubs (module-publics 'perm.QmVxcd8ooha7JtZvh5AD3QhAiLMm7Zjm5YAqkXitVbVSfo)]
     pubs => map?
     (pubs 'foo) => [2])))
