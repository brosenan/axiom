(ns storm.core-test
  (:require [midje.sweet :refer :all]
            [storm.core :refer :all]
            [cloudlog.core :as clg]
            [permacode.core :as perm]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]
             [testing :as st]]))

[[:chapter "Introduction"]]
"[Apache Storm](http://storm.apache.org) is an open-source distributed realtime computation system.
With Storm, a computation is expressed in terms of a *topology*, 
which consists of *spouts* -- data sources, and *bolts* -- data handlers.
Spouts create *tuples* with data, and bolts process this data and provide output.
Many Storm topologies can run on a single Storm cluster."

"This module provides a service that subcribes to `:axiom/rule-ready` events and deploys topologies based on these rules
to a Storm cluster.
Each such topology is a chain of bolts representing the different links in the rule, fed by spouts which emit facts read from a queue.
A last bolt writes the derived facts to a queue."

[[:chapter "topology"]]
"The `topology` function takes a [permacode](permacode.html)-prefixed symbol representing a rule as parameter and a `config` map,
and returns a Storm topology object."

"For this discussion we will refer to the `timeline` rule described [here](cloudlog.html#joins)."
(clg/defrule timeline [user tweet]
     [:test/follows user author] (clg/by-anyone)
     [:test/tweeted author tweet] (clg/by-anyone))

"This rule has two *links*, one responding to `:test/follows` facts and the other -- to `:test/tweeted` facts."

"The corresponding topology will have [two spouts and three bolts](http://storm.apache.org/releases/1.1.0/Concepts.html).
The two spouts correspond to the two fact-streams (`:test/follows` and `:test/tweeted` respectively),
two bolts correspond to the two links that process these facts, and a third link outputs `timeline` entries.
All bolts and spouts take the `config` parameter as their last parameter."
(fact
 (topology 'perm.ABCD1234/timeline ..config..) => ..topology..
 (provided
  ;; We extract the actual function based on the symbol
  (perm/eval-symbol 'perm.ABCD1234/timeline) => timeline
  ;; Then we define the two spouts based on the fact streams
  (fact-spout "test/follows" ..config..) => ..spout0..
  (s/spout-spec ..spout0..) => ..spoutspec0..
  (fact-spout "test/tweeted" ..config..) => ..spout1..
  (s/spout-spec ..spout1..) => ..spoutspec1..
  ;; An initial link bolt based on the initial fact
  (initial-link-bolt 'perm.ABCD1234/timeline ..config..) => ..bolt0..
  (s/bolt-spec {"f0" ["key"]} ..bolt0..) => ..boltspec0..
  ;; and a regular link based on both the second fact and the initial link.
  (link-bolt 'perm.ABCD1234/timeline 1 ..config..) => ..bolt1..
  (s/bolt-spec {"f1" ["key"]
                "l0" ["key"]} ..bolt1..) => ..boltspec1..
  ;; Finally, we add the output bolt
  (output-bolt ..config..) => ..outbolt..
  (s/bolt-spec {"l1" :shuffle} ..outbolt..) => ..outboltspec..
  ;; and create the complete topology
  (s/topology {"f0" ..spoutspec0..
               "f1" ..spoutspec1..}
              {"l0" ..boltspec0..
               "l1" ..boltspec1..
               "out" ..outboltspec..}) => ..topology..))

[[:chapter "initial-link-bolt"]]
"The `initial-link-bolt` is a stateless bolt that transforms facts provided by a `fact-spout` at the beginning of the rule to tuples with similar data
placed in different order or form.
The output of this bolt is input to the `link-bolt` number 1, which also takes input for another `fact-spout`.
The idea is to re-order the data in the tuple so that the key in the output of the `initial-link-bolt` matches the
key in `fact-spout` 1 according to the logic of the rule.
For example, in `timeline` the first fact mentioned in the rule is `:test/follows`, which takes arguments `user` and `author`.
The second fact mentioned is `:test/tweeted`, which takes `author` as its first argument (its *key*).
The `initial-link-bolt` will in this case create a tuple for which the `:key` is the second element in the input tuple (`author`)."
(fact
 (st/with-local-cluster [cluster]
   (let [config {}
         topology (s/topology {"f0" (s/spout-spec (fact-spout "test/follows" config))}
                              {"l0" (s/bolt-spec {"f0" :shuffle} (initial-link-bolt 'storm.core-test/timeline config))})
         result (st/complete-topology cluster topology
                                      :mock-sources
                                      {"f0" [[:fact "test/follows" "alice" ["bob"] 1000 1 #{} #{}]]})]
     (set (st/read-tuples result "l0"))
     => #{[:rule "storm.core-test/timeline!0" "bob" ["alice" "bob"] 1000 1 #{"storm.core-test"} #{}]})))

