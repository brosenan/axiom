(ns cloudlog.core_test_sim
  (:use midje.sweet)
  (:use [cloudlog.core])
  (:use [cloudlog.core_test]
        [clojure.pprint])
  (:require [cloudlog.interset :as interset]))

[[:chapter {:title "f: A Convenience Function to Create Facts" :tag "f"}]]
"In the following we will use the `fact` function, which allows us to create facts."
(fact
 (let [f (f [:test/follows "alice" "bob"] :writers #{[:user= "alice"]} :readers #{"foobar"})]
   f => [:test/follows "alice" "bob"]
   (meta f) => {:writers #{[:user= "alice"]} :readers #{"foobar"}}))

[[:chapter {:title "simulate-with: Evaluate a Rule based on facts" :tag "simulate-with"}]]
"We believe in [TDD](https://en.wikipedia.org/wiki/Test-driven_development) as a \"way of life\" in the software world.
The examples in this very document are tests that are written *before* the corresponding implementation.
Regardless of whether we write the tests before or after the implementation, it is well agreed that automated tests are
key to successful software projects.  App developers using Cloudlog should be no exception.

We therefore provide the `simulate-with` function, which allows rules to be tested independently of
the system implementing Cloudlog, without having to load data to databases, launch clusters etc."

[[:section {:title "simulate-with"}]]
"The `simulate-with` function accepts the following arguments:
1. A single rule function to be simulated,
2. A group identifier to be placed as the [writer-set](#integrity) for derived facts coming out of this rule, and
3. Zero or more facts, making up the test environmnet for the simulation.
It returns a set of tuples, representing the different values the rule gets given the facts.
For example:"
(fact
 (simulate-with timeline :test
                [:test/follows "alice" "bob"]
                [:test/follows "alice" "charlie"]
                [:test/tweeted "bob" "hello"]
                [:test/tweeted "charlie" "hi"]
                [:test/tweeted "david" "boo"])
 => #{["alice" "hello"]
      ["alice" "hi"]})

"Resulting tuples are given a writer-set containing the given group identifier."
(fact
 (-> (simulate-with timeline :test
                    [:test/follows "alice" "bob"]
                    [:test/tweeted "bob" "hello"])
     first meta :writers) => #{:test})

"The simulation calculates the reader-set for the result.
Generally, this is the [intersection](interset.html#intersection) of the reader sets of all participating facts."
(fact
 (let [X #{:a :b}
       Y #{:b :c}]
   (-> (simulate-with timeline :test
                      (f [:test/follows "alice" "bob"] :readers X)
                      (f [:test/tweeted "bob" "hello"] :readers Y))
       first meta :readers) => (interset/intersection X Y)))

[[:section {:title "Under the Hood"}]]
"`simulate-with` is merely a combination of two lower-level functions: `simulate*` and `with*`:"

[[:subsection {:title "with*"}]]
"The `with` is replaced with a call to the `with*` function, which translates a *sequence of facts* to a map from
fact names and arities to sets of value tuples.  For example:"
(fact
 (with* [[:test/follows "alice" "bob"]
         [:test/tweeted "bob" "hello"]
         [:test/follows "bob" "charlie"]])
 => {[:test/follows 2] #{["alice" "bob"]
                         ["bob" "charlie"]}
     [:test/tweeted 2] #{["bob" "hello"]}})

"`with*` moves metadata placed on facts over to the tuples the fact is converted to."
(fact
 (let [with-map (with* [(f [:test/baz 1 2 3] :foo "bar")])]
   (-> (with-map [:test/baz 3]) first meta :foo) => "bar"))


[[:subsection {:title "simulate*"}]]
"This map is then given as a parameter to `simulate*` -- the function that the `simulate` macro evaluates to, along
with the rule function to be simulated.

A simple rule is simulated by applying tuples from the set corresponding to the rule's source-fact,
and then aggregating the results."
(fact
 (simulate* foo-yx {[:test/foo 2] #{[1 2] [3 4]}} :test) => #{[2 1] [4 3]})

"In rules with joins, the continuations are followed."
(fact
 (simulate* timeline (with* [[:test/follows "alice" "bob"]
                             [:test/tweeted "bob" "hello"]]) :test)
 => #{["alice" "hello"]})

[[:chapter {:title "simulate-rules-with: Perform a simulation Based on a Complete Namespace" :tag "simulate-rules-with"}]]
"`simulate-with` is useful for testing a single rule. However, sometimes we are interested in testing the integration
of several rules together.
`simulate-rules-with` is given a collection of rules (and possibly definitions that are not rules and are ignored),
and a set of facts.  It extends this set of facts with derived facts that are produced by applying the rules."

"For example, consider the `trending` rule defined (here)[#derived-facts].
This rule aggregates the timelines of certain *influencers* into a single *trending* timeline."

(fact
 (let [derived (simulate-rules-with [timeline trending] :test
                                    [:test/influencer "alice"]
                                    [:test/follows "alice" "bob"]
                                    [:test/tweeted "bob" "hello"])]
   (derived [:cloudlog.core_test/trending 1]) => #{["hello"]}))


"We topologically-sort the rules so the order in which they appear in the call to `simulate-rules-with`
does not matter."
(fact
 (let [derived (simulate-rules-with [trending timeline] :test
                                    [:test/influencer "alice"]
                                    [:test/follows "alice" "bob"]
                                    [:test/tweeted "bob" "hello"])]
   (derived [:cloudlog.core_test/trending 1]) => #{["hello"]}))

"The second argument (writer group identifier) has the same meaning as in `simulate-with`."
(fact
 (let [derived (simulate-rules-with [trending timeline] :test
                                    [:test/influencer "alice"]
                                    [:test/follows "alice" "bob"]
                                    [:test/tweeted "bob" "hello"])]
   (-> (derived [:cloudlog.core_test/trending 1])
       first
       meta
       :writers) => #{:test}))

"The rule collection can include elements that are not rules, which are ignored."
(fact
 (let [derived (simulate-rules-with [1 "foo" timeline inc] :test
                                    [:test/follows "alice" "bob"]
                                    [:test/tweeted "bob" "hello"])]
   (derived [:cloudlog.core_test/timeline 2]) => #{["alice" "hello"]}))

[[:section {:title "Under the Hood"}]]
"The key to what `simulate-rules-with` is doing is sorting the given rule functions topologically using [graph/toposort](graph.html#toposort).
This is done in the `sort-rules` function, which takes a collection of rules and sorts them by dependencies."

(fact
 (sort-rules [trending timeline]) => [timeline trending])

"Sorting should be arbitrary in the case where two rules (or clauses) have the same target fact, as in the case of clauses
sharing a predicate, as follows:"
(defclause foo-1
  [:test/foo a -> b]
  (let [b (inc a)]))

(defclause foo-2
  [:test/foo a -> b]
  (let [b (* a 2)]))

(fact
 (set (sort-rules [foo-1 foo-2])) => #{foo-1 foo-2})

"Many of the operations here are based on iterating on the continuations of a rule function.
These are returned by the `rule-cont` function."

"Typically, we are not going to use these functions directly, but rather `map` them to their meta properties:"
(fact
 (let [conts (rule-cont timeline)]
   (map #(-> % meta :source-fact) conts) => [[:test/follows 2] [:test/tweeted 2]]))

"The function `rule-target-fact` returns the target fact of a rule."
(fact
 (rule-target-fact timeline) => [:cloudlog.core_test/timeline 2])

[[:chapter {:title "run-query: Applies a Clause on the Facts" :tag "run-query"}]]
"Eventually, users query the state of the application through [clauses](#defclause).
`run-query` takes a collection of rule functions, a query, its output arity, 
a writer identity (intended to be the identity of the application),
a reader set (the identity of whom is making the query),
and any number of facts.  It returs a set of tuples returned from this query."
(fact
 (let [rules (map (fn [[k v]] @v) (ns-publics 'cloudlog.core_test))]
   (run-query rules
              (f [:test/multi-keyword-search ["rant" "politics"]]) 1 :test #{}
              (f [:test/doc 100 "This is a song about love."])
              (f [:test/doc 200 "This is a rant about politics."])
              (f [:test/doc 200 "This is a rant about love."])
              (f [:test/doc 300 "This is a doc about nothing."]))
   => #{["This is a rant about politics."]}))

"The query result will not include elements the user performing the query is not allowed to see."
(fact
 (let [rules (map (fn [[k v]] @v) (ns-publics 'cloudlog.core_test))]
   (run-query rules
              (f [:test/multi-keyword-search ["this" "sentence"]]) 1 :test #{:me}
              (f [:test/doc 100 "This is a sentence"] :readers interset/universe)
              (f [:test/doc 200 "This is another sentence"] :readers #{:someone-else}))
   => #{["This is a sentence"]}))

"`run-query` aggregates the results coming from different clauses of the same prediate.
For example, the following holds with the above definitions of `foo-1` and `foo-2`:"

(fact
 (run-query [foo-1 foo-2]
            (f [:test/foo 2]) 1 :test #{})
 => #{[3] [4]})
