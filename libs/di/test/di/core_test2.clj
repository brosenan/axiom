(ns di.core-test2
  (:require [midje.sweet :refer :all]
            [di.core2 :refer :all]))

[[:chapter {:title "Intoduction"}]]
"[Dependency Injection](https://en.wikipedia.org/wiki/Dependency_injection) (DI) is a practice by which code assets receive their dependencies
from their surroundings at runtime, as opposed to the practice in which they are compiled against these dependencies.
This allows different modules to be decoupled from one another, making software more modular and easier to modify and test."

"The basic building block in DI is a *resource*.
In our implementation, a resource can be any Clojure value.
Such a value could be a function that does something, a configuration value such as a number or a string,
an object representing a connection to an external server or anything else."

"Resources are defined by *modules*, which are Clojure functions (typically named `module`), that contribute rules that provide and use resources."

"The state of the injection is maintained by an *injector*.
An empty injector is created using the [injector](#injector) function.
`module` functions take an injector and [provide](#provide) resources to it.
Modules can also define initialization operations that do not result in a resource, using the `do-with` macro."

"The initializations defined by modules do not take effect until the [startup](#startup) function is called.
This function executes all initialization operations in order.
Once `startup` has completed, all initialization operations have been performed."

"Modules can also associate resources with shut-down operations.
The [shut-down](#shut-down) function performs a shut-down sequence, executed in reverse order relative to the strtup sequence 
to make sure no dependencies are dropped before they are shut down properly."

[[:chapter {:title "injector"}]]
"`injector` creates an empty injector.
By convention, injectors are bound to the variable `$`.
The injector is an atom holding a map.
It has the following fields:
1. `:resources`: A map containing the currently initialized resources.
1. `:rule`: An atom holding a sequence of rules contributed by modules.
2. `:shutdown`: An atom holding a sequence of shutdown operations to be executed."
(fact
 (let [$ (injector)]
   @$ => map?
   (:resources @$) => map?
   (:rules @$) => []
   (:shut-down @$) => []))

"An optional argument provides a map with initial resource values."
(fact
 (let [$ (injector {:foo 1
                    :bar 2})
       res (:resources @$)]
   (:foo res) => 1
   (:bar res) => 2))

[[:chapter {:title "provide"}]]
"The `provide` macro provides a rule for providing a resource that may or may not depend on other resources."

"It takes an injector, the name of the new resource, a list of dependencies and code that evaluates the resource.
It places a function in the `:rules` list in the injector, that when called with the resource map as its parameter, 
returns the value of the new resource.
The function is given two meta fields: `:resource`, containing the name of the target resource, and
`:deps`, containing the dependencies.
Both `:resource` and `:deps` hold resource names as keywords."
(fact
 (let [$ (injector)]
   (provide $ baz [foo bar]
            (+ foo bar))
   (count (:rules @$)) => 1
   (let [func ((:rules @$) 0)]
     (func {:foo 1
            :bar 2}) => 3
     (-> func meta :resource) => :baz
     (-> func meta :deps) => [:foo :bar])))


[[:chapter {:title "do-with"}]]
"Modules can also define actions with side effects that do not result in a new resource.
`do-with` is a macro similar to [provide](#provide), that adds a rule to the injector, but it is not given
a resource name, and therefore does not set the `:resource` meta field in the function."
(fact
 (let [res (atom nil)
       $ (injector)]
   (do-with $ [foo bar]
            (reset! res (+ foo bar)))
   (count (:rules @$)) => 1
   (let [func ((:rules @$) 0)]
     (-> func meta :deps) => [:foo :bar]
     (func {:foo 1
            :bar 2})
     @res => 3)))

[[:chapter {:title "startup"}]]
"After all modules contributed rules to the injector, the `startup` function runs a startup sequence based on these rules."

"For resources without dependencies, action is performed in arbitrary order."
(fact
 (let [res (transient #{})
       $ (injector)]
   (provide $ foo []
            (conj! res :foo)
            1)
   (provide $ bar []
            (conj! res :bar)
            2)
   (startup $)
   (persistent! res) => #{:foo :bar}))

"When depndencies exist, functions are applied according to dependency order."
(fact
 (let [res (atom nil)
       $ (injector)]
   (do-with $ [foo bar]
            (reset! res (str "foo is " foo " and bar is " bar)))
   (provide $ bar [foo]
            (inc foo))
   (provide $ foo []
            1)
   (startup $)
   @res => "foo is 1 and bar is 2"))

"A function is only executed if all its dependencies are present."
(fact
 (let [$ (injector)]
   (provide $ foo [resource-that-does-not-exist]
            (throw (Exception. "This code should not run")))
   (startup $) => nil))

"The resource values provided by rules are updated in the injector's `:resources` map."
(fact
          (let [$ (injector)]
            (provide $ foo []
                     7)
            (startup $)
            (-> @$ :resources :foo) => 7))

[[:chapter {:title "shut-down"}]]
"When [provide](#provide)ing a resource, it is possible to also provide a `:shut-down` function.
This is done by returning an object containing only these two fields:
1. `:resource`: The resource value, and
2. `:shut-down`: A function which, when called, cleans up the resource."
(fact
 (let [$ (injector)
       func (fn [])]
   (provide $ foo []
            {:resource 2
             :shut-down (fn [])})
   (provide $ bar []
            {:resource 3
             :shut-down func
             :something-else 7})
   (startup $)
   (-> @$ :resources :foo) => 2
   (-> @$ :resources :bar) => {:resource 3
                               :shut-down func
                               :something-else 7}))

"The `:shut-down` functions are accumulated in reverse order in the injector's `:shut-down` sequence."
(fact
 (let [$ (injector)]
   (provide $ foo []
            {:resource 1
             :shut-down :some-func1})
   (provide $ bar [foo]
            {:resource 2
             :shut-down :some-func2})
   (provide $ baz [bar]
            {:resource 3
             :shut-down :some-func3})
   (startup $)
   (:shut-down @$) => [:some-func3 :some-func2 :some-func1]))

"The `shut-down` function executes the injector's shut-down sequence."
(fact
 (let [res (transient [])
       $ (injector)]
   (provide $ foo []
            {:resource 1
             :shut-down (fn [] (conj! res :foo))})
   (provide $ bar [foo]
            {:resource 2
             :shut-down (fn [] (conj! res :bar))})
   (provide $ baz [bar]
            {:resource 3
             :shut-down (fn [] (conj! res :baz))})
   (startup $)
   (shut-down $)
   (persistent! res) => [:baz :bar :foo]))

[[:chapter {:title "do-with!"}]]
"The `do-with!` macro has similar semantics to [do-with](#do-with), but instead of providing a rule to the injector prior to `startup`,
`do-with!` perform the enclosed operation (and returns its value) assuming `startup` has already been called."
(fact
 (let [$ (injector)]
   (provide $ foo [] 1)
   (startup $)
   (do-with! $ [foo]
             (inc foo)) => 2))

"If one or more required resources is not available, an exception is thrown."
(fact
 (let [$ (injector)]
   (provide $ foo [] 1)
   (startup $)
   (do-with! $ [bar]
             (inc bar)) => (throws "Resource(s) #{:bar} are not available, but #{:foo} are")))

[[:chapter {:title "Under the Hood"}]]
"`rule-edges` converts a function representing a rule to a collection of edges in a dependency graph.
Each edge is represented by a `[src dest]` tuple. 
The nodes in that graph are either resources (keywords) or functions representing actions to be performed."

"A single rule provides `n` or `n+1` edges, where `n` is the number of dependencies in the rule.
For each dependency `d` we get a rule `[d func]`, where `func` is the rule's function."
(fact
 (let [func (with-meta
              (fn [res])
              {:deps [:foo :bar]})]
   (rule-edges func) => [[:foo func] [:bar func]]))

"In addition, if `func` has a `:resource` meta field with value `r`, a `[func r]` edge is added."
(fact
 (let [func (with-meta (fn [res]) {:resource :baz
                                   :deps [:foo :bar]})]
   (rule-edges func) => [[func :baz] [:foo func] [:bar func]]))
