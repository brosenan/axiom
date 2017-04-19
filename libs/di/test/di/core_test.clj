(ns di.core-test
  (:require [midje.sweet :refer :all]
            [di.core :refer :all]
            [clojure.core.async :as async]))

[[:chapter {:title "Intoduction"}]]
"[Dependency Injection](https://en.wikipedia.org/wiki/Dependency_injection) (DI) is a practice by which code assets receive their dependencies
from their surroundings at runtime, as opposed to the practice in which they are compiled against these dependencies.
This allows different modules to be decoupled from one another, making software more modular and easier to modify and test."

"This module provides DI to Clojure projects.
It defines two macros: `provide` and `with-dependencies`.
`provide` defines the algorithm for providing a *resource*.
`with-dependencies` calculates an expression based on one or more resources known as its *dependencies*.
The evaluation of the expression inside `with-dependencies` is delayed until all resources are available."

"`provide` and `with-dependencies` can be used in concert."

[[:chapter {:title "injector"}]]
"`injector` constructs an empty injector, which should be passed to `provide` and `with-dependencies`."
(fact
 (let [inj (injector)]
   @(inj 0) => {} ; Map of fulfilled resources
   (inj 1) => irrelevant ; channel on which newly fulfilled resources are published
   (inj 2) => irrelevant)) ; publication for unfulfilled resources

[[:chapter {:title "provide"}]]
"The `provide` macro defines an algorithm for fulfilling a resource.
It is called eagerly as a side-effect of loading its containing module."
(fact
 (def inj (injector))
 (provide foo inj
          1 ; There can be
          2 ; any number of
          3 ; expressions here, but
          123)) ; the last expression's value is returned.

"The `provide` block evaluates the expressions in a `do` block, and the returned value is placed in the injector as the
value of the provided resource."
(fact
 (:foo @(first inj)) => 123)

"Fulfilled resources are published using the `pub` object that is the third element in the injector tuple.
To wait for them clients can subscribe to the topic they are interested in."
(fact
 (def bar-chan (async/chan))
 (async/sub (inj 2) :bar bar-chan))

"Now, a provider of `bar` will cause `bar-chan` to contain a key/value pair."
(fact
 (provide bar inj
          "This is bar")
 (async/alts!! [bar-chan
                (async/timeout 1000)]) => [[:bar "This is bar"]
                                           bar-chan])

"`provide` executes within the context of a `go` block.
Its underlying expressions can use macros such as `>!` and `<!`."
(fact
 (def baz-chan (async/chan))
 (provide baz inj
          (async/<! baz-chan))
 (:baz @(first inj)) => nil ; not yet...
 (async/>!! baz-chan 4) 
 (:baz @(first inj)) => 4)

[[:chapter {:title "with-dependencies"}]]
"When we wish to evaluate something that depends on external resources, `with-dependencies` is the way to go.
It can only be used from within a surrounding `go` block.
It needs this `go` block to suspend execution until all requirements are fulfilled."

"Consider the following injector.
It has two resources: `foo` and `bar`, which are defined (and are therefore fulfilled) first.
Then we set the value of the `res` atom to be the sum of both resources using `with-dependencies`.
We expect that the value of `res` will be set to the sum of `foo` and `bar` immediately."
(fact
 (let [inj (injector)
       chan (async/chan)]
   (provide foo inj
            1)
   (provide bar inj
            2)
   (async/go
     (async/>! chan (with-dependencies inj [foo bar]
                      (+ foo bar))))
   (async/alts!! [chan
                  (async/timeout 1000)]) => [3 chan]))

"However, if the order were different, we still expect `with-dependencies` to behave the same way."
(fact
 (let [inj (injector)
       chan (async/chan)]
   (async/go
     (async/>! chan (with-dependencies inj [foo bar]
                      (+ foo bar))))
   (provide foo inj
            (async/<! (async/timeout 1))
            1)
   (provide bar inj
            2)
   (async/alts!! [chan
                  (async/timeout 1000)]) => [3 chan]))

"Since the expressions in a `provide` block are evaluated inside a `go` block, we can combine `provide` and `with-dependencies` to get
resources that depend on other resources."
(fact
 (let [inj (injector)
       chan (async/chan)]
   (async/go
     (async/>! chan (with-dependencies inj [bar]
                      bar)))
   (provide foo inj
            (async/<! (async/timeout 1))
            1)
   (provide bar inj
            (with-dependencies inj [foo]
              (inc foo)))
   (async/alts!! [chan
                  (async/timeout 1000)]) => [2 chan]))
