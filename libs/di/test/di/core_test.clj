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

"`injector` takes an optional argument with default values for resources."
(fact
 (let [inj (injector {:foo 1
                      :bar 2})]
   (:foo @(inj 0)) => 1
   (:bar @(inj 0)) => 2))

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
 (def wait-chan (async/chan))
 (provide baz inj
          (async/<! baz-chan))
 (:baz @(first inj)) => nil ; not yet...
 ; let's subscribe to the value
 (async/sub (inj 2) :baz wait-chan)
 (async/>!! baz-chan 4)
 (async/alts!! [wait-chan
                (async/timeout 1000)]) => [[:baz 4] wait-chan])

[[:chapter {:title "with-dependencies"}]]
"When we wish to evaluate something that depends on external resources, `with-dependencies` is the way to go.
It can only be used from within a surrounding `go` block.
It needs this `go` block to suspend execution until all requirements are fulfilled."

"Consider the following example.
We create an injector and define resources: `foo` and `bar`, which are provided first.
Then we have a `go` block in which we wait for both `foo` and `bar`, and push their sum into a channel.
Finally, we wait (blocking wait) to get this value from the channel (or time-out if we didn't get it within one second).
We expect that the value we get from the channel be the sum of `foo` and `bar`."
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

"We expect it to work regardless of the order, that is,
we expect `with-dependencies` to block execution until `foo` and `bar` are provided.
In the following example we place the `go` block before providing `foo` and `bar`,
and add a 1 ms delay in the providing of `foo`."
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

[[:chapter {:title "wait-for"}]]
"Sometimes, especially during tests, we wish to get a resource outside a `go` block.
In such cases we wish to block the current thread.
Please note that this should be avoided in `provide` blocks, as it may lead to deadlocks.
In other contexts blocking a thread is unadvised due to performance impact.
However, for testing this may be extremely useful."

"Consider our previous example, with resources `foo` and `bar`, where `bar` depends on `foo`."
(fact
 (def inj (injector))
 (def chan (async/chan))
 (provide foo inj
          (async/<! (async/timeout 1))
          1)
 (provide bar inj
          (with-dependencies inj [foo]
            (inc foo))))
"In the previous example we needed to create a `go` block to capture `bar`'s value from within a `with-dependencies` block.
For this to be visible from the outside we used a channel.
However, it can be much simpler to just assert on the value of `bar` using a blocking call.
`wait-for` gives us just that."
(fact
 (wait-for inj bar) => 2)

[[:chapter {:title "with-dependencies!!"}]]
"Sometimes it using a `go` block is inapplicable in places where dependencies are needed.
One example is if we implement a callback function that depends on dependencies, and the callback
is expected to return after having calculated what it needs to.
For such cases, `with-dependencies!!` acts similarly to `with-dependencies`, only that
it delays the current thread until the value is calculated, and thus it does not have to be called from within a `go` block."
(fact
 (def inj (injector))
 (provide foo inj
          (async/<! (async/timeout 1))
          1)
 (with-dependencies!! inj [foo]
   (+ foo 2)) => 3)

[[:chapter {:title "Best Practices"}]]
"In our opinion, the best way to write modules that use the `di` library for dependency injection is to
wrap all the code that interacts with the injector (i.e., all code that provides and/or depends on resources) in a single function,
which we encourage you to call `module`.
This function will take one argument -- the injector, which will be provided externally."

"For example, imagine we have a module that depends on one resource: `some-number` (a number), and provides two resources:
1. `plus-some-number` -- a function that adds `some-number` to a given number, and
2. `times-some-number` -- a function that multiplies `some-number` by a given number.
We will wrap both providers in a `module` function which will take the injector as argument.
As convention (borrowed from [jQuery](https://jquery.com) plugins), we will call the injector argument `$`."
(defn module [$]
  (provide plus-some-number $
           (with-dependencies $ [some-number]
             (fn [x] (+ x some-number))))
  (provide times-some-number $
           (with-dependencies $ [some-number]
             (fn [x] (* x some-number)))))

"Now we can test our module.
We can call `module` with different injectors, initialized with different `some-number` values, 
to see that our module behaves as it should in different cases."
(fact
 ;; test with 2
 (let [$ (injector {:some-number 2})]
   (module $)
   (let [plus (wait-for $ plus-some-number)
         times (wait-for $ times-some-number)]
     (plus 1) => 3
     (times 1) => 2))
 ;; test with 3
 (let [$ (injector {:some-number 3})]
   (module $)
   (let [plus (wait-for $ plus-some-number)
         times (wait-for $ times-some-number)]
     (plus 1) => 4
     (times 1) => 3)))

"Now imagine another module that complements our own.
Let's imagine a module `module2`, which provides `some-number` as 2, and then provides the resource `six` based on
our functions."
(defn module2 [$]
  (provide some-number $
           2)
  (provide six $
           (with-dependencies $ [plus-some-number
                                 times-some-number
                                 some-number]
             (plus-some-number (times-some-number some-number)))))

"(generally there is only need for placing one `module` function in a namespace, so the function can always be called `module`.
Here we needed a different function to demonstrate how different modules can work together)"

"We can test `module2` by injecting mock functions:"
(fact
 (let [$ (injector {:plus-some-number (fn [x] [:plus x])
                    :times-some-number (fn [x] [:times x])})]
   (module2 $)
   (wait-for $ some-number) => 2
   (wait-for $ six) => [:plus [:times 2]]))

"In production, these two modules need to actually meet each other and cooperate.
We use a single injector and git it to both of them."
(fact
 (let [$ (injector)]
   (module $)
   (module2 $)
   (wait-for $ six) => 6))

"The order in which we inject the different modules does not matter."
(fact
 (let [$ (injector)]
   (module2 $) 
   (module $)
   (wait-for $ six) => 6))
