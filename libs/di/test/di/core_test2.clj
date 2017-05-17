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
It places a tuple in the `:rules` list in the injector, containing the name of the resource, the names of the dependencies and a function
from the dependencies to the resource."
(fact
 (let [$ (injector)]
   (provide $ baz [foo bar]
            (+ foo bar))
   (count (:rules @$)) => 1
   (let [tuple ((:rules @$) 0)]
     (count tuple) => 3
     (tuple 0) => :baz
     (tuple 1) => [:foo :bar]
     (let [func (tuple 2)]
       (func 1 2) => 3))))
