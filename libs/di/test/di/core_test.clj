(ns di.core-test
  (:require [midje.sweet :refer :all]
            [di.core :refer :all]))

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

"Modules can also associate resources with shutdown operations.
The [shutdown](#shutdown) function performs a shutdown sequence, executed in reverse order relative to the strtup sequence 
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
   (:rules @$) => sequential?
   (:shutdown @$) => []))

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
   (let [func (last (:rules @$))]
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
   (let [func (last (:rules @$))]
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

"If a resource was defined during the initialization of the injector, the operation is skipped."
(fact
 (let [$ (injector {:to-skip 8})]
   (provide $ to-skip []
            (throw (Exception. "This should not be called")))
   (startup $)
   (-> @$ :resources :to-skip) => 8))

[[:chapter {:title "shutdown"}]]
"When [provide](#provide)ing a resource, it is possible to also provide a `:shutdown` function.
This is done by returning an object containing only these two fields:
1. `:resource`: The resource value, and
2. `:shutdown`: A function which, when called, cleans up the resource."
(fact
 (let [$ (injector)
       func (fn [])]
   (provide $ foo []
            {:resource 2
             :shutdown (fn [])})
   (provide $ bar []
            {:resource 3
             :shutdown func
             :something-else 7})
   (startup $)
   (-> @$ :resources :foo) => 2
   (-> @$ :resources :bar) => {:resource 3
                               :shutdown func
                               :something-else 7}))

"The `:shutdown` functions are accumulated in reverse order in the injector's `:shutdown` sequence."
(fact
 (let [$ (injector)]
   (provide $ foo []
            {:resource 1
             :shutdown :some-func1})
   (provide $ bar [foo]
            {:resource 2
             :shutdown :some-func2})
   (provide $ baz [bar]
            {:resource 3
             :shutdown :some-func3})
   (startup $)
   (:shutdown @$) => [:some-func3 :some-func2 :some-func1]))

"The `shutdown` function executes the injector's shutdown sequence."
(fact
 (let [res (transient [])
       $ (injector)]
   (provide $ foo []
            {:resource 1
             :shutdown (fn [] (conj! res :foo))})
   (provide $ bar [foo]
            {:resource 2
             :shutdown (fn [] (conj! res :bar))})
   (provide $ baz [bar]
            {:resource 3
             :shutdown (fn [] (conj! res :baz))})
   (startup $)
   (shutdown $)
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
             (inc bar)) => (throws #"Resource\(s\) \#\{:bar\} are not available, but \#.*:foo.* are")))

[[:chapter {:title "do-with-default!"}]]
"In cases when we do not know if a certain resource exists in the injector,
we wish to be able to get this resource if it exists or take another default value if it does not.
`do-with-default!` allows us to do just that.
Its structure resembles that of a `let` form.
It takes an injector (`$`), a vector consisting of a single resource name and a default value for this resource,
and zero or more expressions to be evaluated."

"If the resource exists, its name inside the expression is bound to its value, as in [do-with!](#do-with-0)."
(fact
 (let [$ (injector {:foo 2})]
   (startup $)
   (do-with-default! $ [foo 4]
                     (inc foo)) => 3))

"If the resource does not exist, the name is bound to the provided default value."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with-default! $ [foo 4]
                     (inc foo)) => 5))

"If the second argument is not a vector, a compile-time exception is thrown."
(fact
 (macroexpand `(do-with-default! $ :not-a-vector foo bar baz))
 => (throws "The second argument of do-with-default! must be a vector. Given: :not-a-vector"))

"If the second argument is not of size 2, a compile-time exception is thrown."
(fact
 (macroexpand `(do-with-default! $ [:not :of :size :two] foo bar baz))
 => (throws "The second argument of do-with-default! must be of size 2. Given: [:not :of :size :two]"))

[[:chapter {:title "Default Rules"}]]
"An empty injector comes with some rules pre-loaded.
These rules are intended to provide a set of basic capabilities to be used in modules."

[[:section {:title "time"}]]
"`time` is a function that returns the current time in milliseconds."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [time]
             (let [t1 (time)]
               t1 => number?
               (Thread/sleep 1)
               (let [t2 (time)]
                 (> t2 t1) => true)))))

[[:section {:title "println"}]]
"`println` prints a line of text to the stndard output."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [println]
             (with-out-str (println "foobar")) => "foobar\n")))

[[:section {:title "format-time"}]]
"`format-time` is a function that formats a timestamp (as given by `time`) in human-readable form."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [format-time]
             ;; The format is sentitive to timezone, so we match against a regex
             (format-time 1495299001266) => #"May 2. 2017 ..:50:01:266 ...")))

[[:section {:title "logging"}]]
"`log` is a function that logs an event.
An event is given as a map of properties.
It expects a `:format` field specifying the format of the logging event.
The `:format` field is a tuple consisting of a string (input to `format`),
and a vector of field names where the values to the format should come from.
`log` uses [time](#time), [format-time](#format-time) and [println](#println) to print the formatted event
along with a timestamp."
(fact
 (let [$ (injector {:time (constantly 1234)
                    :format-time str})]
   (startup $)
   (do-with! $ [log]
             (with-out-str (log {:format ["[%s] [%d]" [:foo :bar]]
                                 :foo "hello"
                                 :bar 3})) => "1234 [hello] [3]\n")))

"A default format refers to the properties `:severity`, `:source` and `:desc`."
(fact
 (let [$ (injector {:time (constantly 1234)
                    :format-time str})]
   (startup $)
   (do-with! $ [log]
             (with-out-str (log {:severity "II"
                                 :source "my-service"
                                 :desc "Service is shutting down"})) => "1234 [II] [my-service] Service is shutting down\n")))

"The convenience functions `err`, `warn` and `info` call [log](#log) with a corresponding `:severity` value."
(fact
 (let [$ (injector {:time (constantly 1234)
                    :format-time str})]
   (startup $)
   (do-with! $ [err warn info]
             (with-out-str (err {:source "my-service"
                                 :desc "Something went wrong"}))
             => "1234 [EE] [my-service] Something went wrong\n"
             (with-out-str (warn {:source "my-service"
                                  :desc "Something probably went wrong"}))
             => "1234 [WW] [my-service] Something probably went wrong\n"
             (with-out-str (info {:source "my-service"
                                  :desc "Something is going on"}))
             => "1234 [II] [my-service] Something is going on\n")))

[[:section {:title "sh"}]]
"The `sh` function executes a command given as its arguments, and returns an `:exit` code as an integer,
and the content of the standard `:out` and `:err` as strings."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [sh]
             (sh "echo" "foo") => {:exit 0
                                   :out "foo\n"
                                   :err ""})))

[[:section {:title "uuid"}]]
"A Universally-Unique IDentifier is a sequence of characters that is guaranteed (beyond any practical probability) to be unique.
There are different flavours and algorithms for UUIDs and their creation, but any of those will provide this basic property -- that every one is unique."

"The `uuid` resource is a function that whenever called returns a new UUID string."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [uuid]
             (-> (for [_ (range 100)]
                   (uuid))
                 set
                 count) => 100)))

"The default `uuid` implementation leverages Java's [UUID class](https://docs.oracle.com/javase/7/docs/api/java/util/UUID.html),
which implements the [RFC-4122](https://tools.ietf.org/html/rfc4122) variant."
(fact
 (let [$ (injector)]
   (startup $)
   (do-with! $ [uuid]
             (uuid)
             => #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

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
