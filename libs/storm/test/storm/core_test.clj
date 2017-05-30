(ns storm.core-test
  (:require [midje.sweet :refer :all]
            [storm.core :refer :all]
            [cloudlog.core :as clg]
            [permacode.core :as perm]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]]))

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
"The `topology` function takes a [permacode](permacode.html)-prefixed symbol representing a rule as parameter and returns a Storm topology object."

"For this discussion we will refer to the `timeline` rule described [here](cloudlog.html#joins)."
(clg/defrule timeline [user tweet]
     [:test/follows user author] (clg/by-anyone)
     [:test/tweeted author tweet] (clg/by-anyone))

"This rule has two *links*, one responding to `:test/follows` facts and the other -- to `:test/tweeted` facts."

"The corresponding topology will have [two spouts and three bolts](http://storm.apache.org/releases/1.1.0/Concepts.html).
The two spouts correspond to the two fact-streams (`:test/follows` and `:test/tweeted` respectively),
two bolts correspond to the two links that process these facts, and a third link outputs `timeline` entries."
(fact
 (topology 'perm.ABCD1234/timeline) => ..topology..
 (provided
  ;; We extract the actual function based on the symbol
  (perm/eval-symbol 'perm.ABCD1234/timeline) => timeline
  ;; Then we define the two spouts based on the fact streams
  (fact-spout "test/follows") => ..spout0..
  (s/spout-spec ..spout0..) => ..spoutspec0..
  (fact-spout "test/tweeted") => ..spout1..
  (s/spout-spec ..spout1..) => ..spoutspec1..
  ;; An initial link bolt based on the initial fact
  (initial-link-bolt 'perm.ABCD1234/timeline) => ..bolt0..
  (s/bolt-spec {"f0" ["key"]} ..bolt0..) => ..boltspec0..
  ;; and a regular link based on both the second fact and the initial link.
  (link-bolt 'perm.ABCD1234/timeline 1) => ..bolt1..
  (s/bolt-spec {"f1" ["key"]
                "l0" ["key"]} ..bolt1..) => ..boltspec1..
  ;; Finally, we add the output bolt
  (s/bolt-spec {"l1" :shuffle} output-bolt) => ..outbolt..
  ;; and create the complete topology
  (s/topology {"f0" ..spoutspec0..
               "f1" ..spoutspec1..}
              {"l0" ..boltspec0..
               "l1" ..boltspec1..
               "out" ..outbolt..}) => ..topology..))

