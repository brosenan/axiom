(ns cloudlog.core_test_facttable
  (:use midje.sweet)
  (:use [cloudlog.core])
  (:use [cloudlog.core_test]))

[[:chapter {:title "fact-table: Get a Fully-Qualified Name for a Fact"}]]
"In an implementation of a Cloudlog engine it is necessary, given a `:source-fact` meta of a rule, to know
which real-world resources (tables, queues etc) are associated with this fact.  Doing so
consistently is important to get different references to the same fact to work against the same resources.

The function `fact-table` takes a `[name arity]` pair that is given as the `:source-fact` of a rule (or a continuation)
and returns a string representing this fact.

For raw facts (represented by Clojure keywords, e.g., `:test/follows`), the returned string is simply the 
fully qualified name of the keyword:"
(fact
 (-> timeline meta :source-fact fact-table) => "test/follows")

"For a derived fact, represented as a name of a rule, the returned string is the 
fully qualified name of the rule."
(fact
 (-> trending meta :continuation
     meta :source-fact fact-table) => "cloudlog.core_test/timeline")
