(ns permacode.validate-test
  (:require [permacode.core :refer :all]
            [permacode.validate :refer :all]
            [permacode.hasher :as hasher]
            [midje.sweet :refer :all]
            [clojure.string :as string]))

[[:chapter {:title "Introduction"}]]
"Permacode validation is intended to make sure that a give Clojure source file conforms to the
permacode sub-language."

"Validation is a two-step process.  Its first part is done on a source file that has been read somehow,
and makes sure that it has all the safeguards necessary for the second part to kick in.
The second part is done with the `pure` macro, at compile time.
It makes sure that only allowed language constructs are used."

[[:chapter {:title "validate-ns: Validating the ns Expression" :tag "validate-ns"}]]
"`validate-ns` takes an `ns` expression and a list of allowed namespaces, and succeeds (returns nil)
if this `ns` expression is valid in the sense that it only `requires` namespaces for that list."

"For a trivial `ns` expression it succeeds"
(fact
 (validate-ns '(ns foo.bar) white-listed-ns) => nil)

"Simple `:require` clauses are allowed."
(fact
 (validate-ns '(ns foo.bar
                 (:require [clojure.string]
                           [clojure.set])
                 (:require [my.proj.core])) (concat white-listed-ns ["my.proj.core"]))
 => nil)

"And so are `:require` clauses with `:as`:"
(fact
 (validate-ns '(ns foo.bar
                 (:require [clojure.string :as string])) white-listed-ns)
 => nil)

"Other forms are not supported."
(fact
 (validate-ns '(ns foo.bar
                 (:require [clojure.string :refer :all])) white-listed-ns)
 => (throws ":refer is not allowed in ns expressions in permacode modules"))
"If a `:require` refers to a namespace that is not in the environment, an exception is thrown."
(fact
 (validate-ns '(ns foo.bar
                 (:require [clojure.core.async])) white-listed-ns)
 => (throws #"Namespace clojure.core.async is not approved for permacode"))

"`validate-ns` also throws an exception if the expression it is given is not an `ns` expression."
(fact
 (validate-ns '(not-an-ns-expr foo.bar) white-listed-ns)
 => (throws #"The first expression in a permacode source file must be an ns.  not-an-ns-expr given."))

"Permacode modules can `:require` other permacode modules by naming them as `perm.*` where `*` is replaecd
with their hash code.
We consider `:require`ing such a module safe."
(fact
 (validate-ns '(ns foo.bar
                 (:require [perm.ABCD1234])) white-listed-ns)
 =not=> (throws))

"When the `permacode.core` namespace is `:require`d and is given an alias, `validate-ns` returns that alias."
(fact
 (validate-ns '(ns foo.bar
                 (:require [perm.ABCD1234 :as foo]
                           [permacode.core :as bar]
                           [clojure.string :as string])) white-listed-ns)
 => 'bar)

[[:chapter {:title "validate-expr: Validate a Body Expression" :tag "validate-expr"}]]
"Given a set of allowed symbols we can validate a body  expression.
A declaration adds a symbol to the environment."
(fact
 (let [new-env (validate-expr #{'foo 'bar} '(def quux))]
   (new-env 'quux) => 'quux))

"The same works with definitions as well."
(fact
 (let [new-env (validate-expr #{'foo 'bar} '(def quux 123))]
   (new-env 'quux) => 'quux))

"The environment provided to the body contains the variable being `def`ed, so that recursive
definitions are possible."
(fact
  (validate-expr #{} '(def quux [1 quux]))
  => #{'quux})
"Macros are expanded.  For example. `defn` is interpreted as a `def` with `fn`."
(fact
 (let [new-env (validate-expr #{'foo 'bar} '(defn quux [x] x))]
   (new-env 'quux) => 'quux))

"When a form is not a valid top-level form, an exception is thrown."
(fact
 (validate-expr #{'foo 'bar} '(let [x 2] (+ x 3)))
 => (throws "let* is not a valid top-level form."))

"For a `do`, we recurse to validate the child forms."
(fact
 (validate-expr #{} '(do
                       (def foo 1)
                       (def bar 2)))
 => #{'foo 'bar})

"`defmacro`, `defmulti` and `defmethod` use Java interop when expanded.
We allow them in permacode, by making special cases out of them."
(fact
 (validate-expr #{'clojure.core/concat 'clojure.core/list 'clojure.core/seq} ; used by the syntax-quote
                '(defmacro foo [x y z] `(~x ~y ~z)))
 => #{'foo 'clojure.core/concat 'clojure.core/list 'clojure.core/seq}
 (validate-expr #{'first}
                '(defmulti bar first))
 => #{'first 'bar}
 (validate-expr #{}
                '(defmulti bar first))
 => (throws "symbols #{first} are not allowed")
 (validate-expr #{'+}
                '(defmethod bar :x [a b]
                   (+ a b)))
 => #{'+ 'bar}
 (validate-expr #{}
                '(defmethod bar c [a b]
                   (+ a b)))
 => (throws "symbols #{c +} are not allowed"))

"To support multimethods, `prefer-method` is allowed at the top-level."
(fact
 (validate-expr #{'foo 'a 'b}
                '(prefer-method foo a b)) => #{'foo 'a 'b}
  (validate-expr #{}
                '(prefer-method foo a b)) => (throws "symbols #{a foo b} are not allowed"))

"Comments (that expand to `nil`) are allowed as top-level expressions."
(fact
 (validate-expr #{} '(comment
                       (foo bar)))
 => #{})

[[:section {:title "Validating Values"}]]
"In definitions, the values are validated to only refer to allowed symbols."
(fact
 (validate-expr #{'foo} '(def bar (+ foo 2)))
 => (throws "symbols #{+} are not allowed")
 (validate-expr #{'foo '+} '(def bar (+ foo 2)))
 => #{'foo 'bar '+})

"In `do` blocks, symbols defined (or even declared) in previous definitions can be used in succeeding definitions."
(fact
 (validate-expr #{} '(do
                       (declare foo)
                       (def bar foo)))
 => #{'bar 'foo})

"While we do not allow Java interop in permacode, we do allow one small part of it: class literals.
We do allow class names (symbols with dots in the middle) in expressions.
The assumption is that alone (e.g., without possibility of instantiation or calling static methods)
classes are harmless.  And they are useful.  Often, calsses are the best way to distinguish between
different kinds of Clojure elements."
(fact
 (validate-expr #{} '(def bar foo.bar.MyClass))
 => #{'bar}
 (validate-expr #{} '(def bar (foo.bar.MyClass.)))
 => (throws "symbols #{new} are not allowed"))

[[:chapter {:title "validate: Validate an Entire Module" :tag "validate"}]]
"A module is valid if:
1. Its first expression is `ns`, and it is valid based on [validate-ns](#validate-ns).
2. All its other expressions are `pure` expressions.  The `pure` macro validates its own contents."

"`validate` takes the content of a module (as a seq of expressions) and and a list of allowed namespaces,
and succeeds (does not throw exceptions) if valid."
(fact
 (validate '[(ns foo.bar
               (:require [permacode.core]
                         [clojure.string :as str]
                         [perm.FOOBAR123 :as some-other-module]))
             (permacode.core/pure
              (def x 3))] white-listed-ns)
 => nil)

"If something is wrong in the `ns` expression, it throws."
(fact
 (validate '[(ns foo.bar
               (:require [permacode.core]
                         [some.bad.module]
                         [clojure.string :as str]
                         [perm.FOOBAR123 :as some-other-module]))
             (permacode.core/pure
              (def x 3))] white-listed-ns)
 => (throws))

"If one of the other expressions in the module is not wrapped in a `pure`, an exception is thrown."
(fact
 (validate '[(ns foo.bar
               (:require [permacode.core]
                         [clojure.string :as str]
                         [perm.FOOBAR123 :as some-other-module]))
             (permacode.core/pure
              (def x 3))
             (def y 5)] white-listed-ns)
 => (throws "Expression (def y 5) must be wrapped in a pure macro"))

"In case `permacode.core` is aliased to some name, `validate` allowes `pure` to be used from within that alias."
(fact
 (validate '[(ns foo.bar
               (:require [permacode.core :as perm]
                         [clojure.string :as str]
                         [perm.FOOBAR123 :as some-other-module]))
             (perm/pure
              (def x 3))] white-listed-ns)
 => nil)
[[:chapter {:title "perm-require: Load a Hashed Namespace"}]]
"When we have a hash-code that represents a permacode namespace, we need to `perm-require` it so that it becomes available
for programs."

"The `perm-require` function takes a hash code and possibly an alias, and loads the module along with its dependencies,
similar to the `clojure.core/require` function."

"If the hash's namespace is already loaded, we return."
(fact
 (binding [*hasher* (hasher/nippy-multi-hasher (hasher/atom-store))]
   (perm-require 'perm.abcd) => nil
   (provided
    (find-ns 'perm.abcd) => :something)))

"If the namespace if not loaded, the code is retrieved from the hasher."
(def hash-to-require (symbol (str "perm.abcd" (rand-int 10000))))
(def unhash-called (atom false))
(def mock-content '[(ns foo
                      (:require [permacode.core :as perm]))
                    (perm/pure
                     (defn f [x] (+ 1 x)))])
(defn mock-unhash [hash]
  (assert (= hash (-> hash-to-require str (string/replace-first "perm." ""))))
  (swap! unhash-called not)
  mock-content)

"After retrieving the code it is `eval`uated in the new namespace."
(fact
 (binding [*hasher* [nil mock-unhash]]
   (perm-require hash-to-require) => nil
   (provided
    (find-ns hash-to-require) => nil)
   @unhash-called => true
   (find-ns hash-to-require) =not=> nil?
   ((ns-aliases hash-to-require) 'perm) =not=> nil?
   (let [f @((ns-publics hash-to-require) 'f)]
     (f 2)) => 3))

"If the header requires a forbidden module, an exception is thrown."
(def mock-content '[(ns foo
                      (:require [clojure.java.io]))])
(def hash-to-require (symbol (str "abcd" (rand-int 10000)))) ; Fresh namespace
(fact
 (binding [*hasher* [nil mock-unhash]]
   (perm-require hash-to-require) => (throws "Namespace clojure.java.io is not approved for permacode")))

"When a module `:require`s some other permacode module, `perm-require` recursively loads it."
(def mock-content '[(ns foo
                      (:require [perm.FOOBAR1234]))])
(def hash-to-require (symbol (str "abcd" (rand-int 10000)))) ; Fresh namespace
(fact
 (binding [*hasher* [nil mock-unhash]]
   (perm-require hash-to-require) => nil
   (provided
    (find-ns hash-to-require) => nil
    (find-ns 'perm.FOOBAR1234) => :something)))

"`perm-require` accepts an optional `:as` keyword argument, and creates an alias accordingly."
(fact
 (let [alias (symbol (str "foobar" (rand-int 100000)))]
   (binding [*hasher* [nil mock-unhash]]
     (perm-require hash-to-require :as alias) => nil
     ((ns-aliases *ns*) alias) =not=> nil?)))
