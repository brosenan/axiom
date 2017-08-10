(ns cloudlog-events.testing-test
  (:use [midje.sweet]
        [cloudlog-events.testing])
  (:require [cloudlog.core :as clg]
            [clojure.set :as set]))

[[:chapter {:title "Introduction"}]]
"Unit testing is an important aspect of any software, and good tools for testing are important for creating good tests.
This library is intended to provide tools for testing [cloudlog](cloudlog.html) code."

"Cloudlog is a purely declarative DSL.
[rules](cloudlog.html#defrule) and [clauses](cloudlog.html#defclause) defined in cloudlog are declarative, meaning they do not have state.
This makes testing easier.
The output of a rule or a clause is a (pure) function of its input."

"But how can we treat a rule, a clause or a combination of rules and clauses as functions?
We can treat them as function that transform *sets of tuples*.
Each fact, query or query result can be seen as a tuple.
A rule transforms a set of (raw) facts into a set of derived facts.
A clause transforms a single query and a set of facts into a set of query results."

"The functions in the library implement these functions, allowing the rules and clauses available to the current namespace code to be treated as functions
from fact tuples to result tuples."

"The examples on this page will refer to the following application definition:"
(fact
 (def msec-in-day (* 1000 60 60 24))

 ;; Rules should reside in their own source file
 (clg/defrule followee-tweets [[user day] author tweet ts]
   [:tweetlog/follows user author] (clg/by user)
   [:tweetlog/tweeted author tweet ts] (clg/by author)
   (let [day (quot ts msec-in-day)]))

 (clg/defclause tl-1
   [:tweetlog/timeline user from-day to-day -> author tweet ts]
   (let [day-range (range from-day to-day)])
   (when-not (> (count day-range) 20))
   (for [day day-range])
   [followee-tweets [user day] author tweet ts])

 (clg/defrule follower [user f]
   [:tweetlog/follows f user] (clg/by f))
 
 (clg/defclause f1
   [:tweetlog/follower user -> f]
   [follower user f]))

[[:chapter {:title "apply-rules"}]]
"The `apply-rules` function takes a collection of facts [represented as vectors](#to-event) and a vector containing a rule name and a key
and returns a set of `:data` tuples that together with the rule name and the key form facts derived from given collection of raw facts using all available rules."
(fact
 (let [facts [[:tweetlog/follows "charlie" "alice" #{"charlie"}]
              [:tweetlog/follows "eve" "bob" #{"eve"}]
              [:tweetlog/tweeted "alice" "hello, world" 100 #{"alice"}]
              [:tweetlog/tweeted "bob" "hola, mundo" 200 #{"bob"}]]]
   (apply-rules facts [:cloudlog-events.testing-test/followee-tweets ["charlie" 0]]) => #{["alice" "hello, world" 100]}))

"If no result is available, `apply-rules` returns a map with the following fields to help debugging the problem:
- `:keys`: A set of all the keys that exist in the index, and
- `:rules`: A set of all the names of the rules that were applied."

"For example, if in the above example we misspell the name of the rule and write, e.g., `followees-tweets` instead of `followee-tweets`,
we get a map telling us what names are valid so we can adjust the test (in this case) or the rule if the mistake was done there."
(fact
 (let [facts [[:tweetlog/follows "charlie" "alice" #{"charlie"}]
              [:tweetlog/follows "eve" "bob" #{"eve"}]
              [:tweetlog/tweeted "alice" "hello, world" 100 #{"alice"}]
              [:tweetlog/tweeted "bob" "hola, mundo" 200 #{"bob"}]]]
   (let [result (apply-rules facts [:cloudlog-events.testing-test/followees-tweets ["charlie" 0]])]
     (:keys result) => #{[:tweetlog/follows "charlie"]
                         [:tweetlog/follows "eve"]
                         [:tweetlog/tweeted "alice"]
                         [:tweetlog/tweeted "bob"]
                         [:cloudlog-events.testing-test/followee-tweets ["charlie" 0]]
                         [:cloudlog-events.testing-test/followee-tweets ["eve" 0]]
                         [:cloudlog-events.testing-test/follower "alice"]
                         [:cloudlog-events.testing-test/follower "bob"]}
     (set/subset? #{:cloudlog-events.testing-test/followee-tweets :cloudlog-events.testing-test/follower} (:rules result))
     => true)))

[[:chapter {:title "query"}]]
"`query` uses [apply-rules](#apply-rules) to perform a query.
Queries exercise [clauses](cloudlog.html#defclause) by providing them an input tuple, expecting output tuples in return."

"The `query` function takes a collection of facts and a query vector that consists of the name of the predicate (the keyword mentioned in the clause)
and all its input arguments, and returns a set of the results as tuples corresponding to the output arguments."
(fact
 (let [facts [[:tweetlog/follows "charlie" "alice" #{"charlie"}]
              [:tweetlog/follows "eve" "bob" #{"eve"}]
              [:tweetlog/tweeted "alice" "hello, world" 100 #{"alice"}]
              [:tweetlog/tweeted "bob" "hola, mundo" 200 #{"bob"}]]]
   (query facts [:tweetlog/timeline "charlie" 0 3]) => #{["alice" "hello, world" 100]}))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "all-rules"}]]
"`all-rules` returns a sequence of all the rule and clause functions available to the current namespace, sorted topologically by their dependencies.
It calls `all-ns` to get all visible namespaces, and then calls `ns-publics` on each returned namespace to collect definitions.
Out of these, it returns all the rule and clause functions."
(fact
 (all-rules) => ..sorted..
 (provided
  (all-ns) => [..ns1.. ..ns2.. ..ns3..]
  (ns-publics ..ns1..) => {'tl-1 #'tl-1
                           'f1 #'f1
                           '+ #'+ '* #'*}
  (ns-publics ..ns2..) => {'followee-tweets #'followee-tweets
                           '/ #'/ '- #'-}
  (ns-publics ..ns3..) => {'bit-and #'bit-and
                           'follower #'follower
                           'bit-or #'bit-or}
  (clg/sort-rules #{followee-tweets tl-1 follower f1}) => ..sorted..))

[[:section {:title "to-event"}]]
"This testing library uses [cloudlog-events.core](cloudlog-events.html)'s event processors for processing rules and clauses.
As they work on events, we need to be able to convert fact-like tuples into events.
`to-event` converts a vector representing a fact to an event, in the following way:
- The first element, assumed to be a keyword, is converted to a string in the `:name` field.
- The second element is taken to be the `:key`.
- The *last* element is taken to be the `:writers`.
- All elements after the second and before the last are taken to be the `:data` tuple.
The rest of the fields are field with defaults."
(fact
 (to-event [:foo/bar "key" 1 2 3 #{"bob"}])
 => {:kind :fact
     :name "foo/bar"
     :key "key"
     :data [1 2 3]
     :ts 1
     :change 1
     :writers #{"bob"}
     :readers #{}})

[[:section {:title "index-events"}]]
"To efficiently fecth events that match a certain `:name` and `:key` values we create an index using `index-events`.
`index-events` takes a collection of events and creates an event index for them."

"For an empty collection, an empty map is returned."
(fact
 (index-events []) => {})

"An index is a map where the keys are `[:name :key]` pairs (the `:name` is provided as a keyword), and the values are sets of matching events."
(fact
 (index-events (->> [[:foo/bar 123 1 2 3 #{"bob"}]]
                    (map to-event)))
 => {[:foo/bar 123] #{(to-event [:foo/bar 123 1 2 3 #{"bob"}])}})

"`index-events` reduces the given events using [merge-indexes](#merge-indexes) as the reducer function to create an index."
(fact
 (index-events (->> [[:foo/bar 123 1 2 3 #{"bob"}]
                     [:foo/bar 124 2 3 4 #{"bob"}]
                     [:foo/bar 123 2 3 4 #{"bob"}]]
                    (map to-event)))
 => {[:foo/bar 123] #{(to-event [:foo/bar 123 1 2 3 #{"bob"}])
                      (to-event [:foo/bar 123 2 3 4 #{"bob"}])}
     [:foo/bar 124] #{(to-event [:foo/bar 124 2 3 4 #{"bob"}])}})

[[:section {:title "merge-indexes"}]]
"`merge-indexes` merges event indexes.
Given zero arguments if returns an empty map."
(fact
 (merge-indexes) => {})

"Given two event indexes as input, it merges them."
(fact
 (merge-indexes {[:foo/bar 1] #{(to-event [:foo/bar 1 2 3 #{}])}}
               {[:foo/bar 2] #{(to-event [:foo/bar 2 3 4 #{}])}})
 => {[:foo/bar 1] #{(to-event [:foo/bar 1 2 3 #{}])}
     [:foo/bar 2] #{(to-event [:foo/bar 2 3 4 #{}])}})

"In case the two indexes have a key in common, the sets are merged."
(fact
 (merge-indexes {[:foo/bar 1] #{(to-event [:foo/bar 1 2 3 #{}])}}
               {[:foo/bar 1] #{(to-event [:foo/bar 1 3 4 #{}])}})
 => {[:foo/bar 1] #{(to-event [:foo/bar 1 2 3 #{}])
                    (to-event [:foo/bar 1 3 4 #{}])}})

[[:section {:title "process-initial-link"}]]
"`process-initial-link` processes link-0 of a rule function.
It receives a rule function and an event index as paramters, and returns a set of resulting events."
(fact
 (let [index (->> [[:tweetlog/follows "charlie" "alice" #{"charlie"}]
                   [:tweetlog/follows "eve" "bob" #{"eve"}]]
                  (map to-event)
                  index-events)]
   (process-initial-link followee-tweets index)
   => #{(-> [:cloudlog-events.testing-test/followee-tweets!0 "alice" "charlie" "alice" #{"cloudlog-events.testing-test"}]
            to-event (assoc :kind :rule))
        (-> [:cloudlog-events.testing-test/followee-tweets!0 "bob" "eve" "bob" #{"cloudlog-events.testing-test"}]
            to-event (assoc :kind :rule))}))

[[:section {:title "process-link"}]]
"`process-link` takes as paramters a link function (a `:continuation` of a rule function), a set of rule events and an event index with facts, 
and returns a set of events resulting from applying every relevant `:fact` event from the index to every rule event in the given set."
(fact
 (let [index (->> [[:tweetlog/tweeted "alice" "hello, world" 100 #{"alice"}]
                   [:tweetlog/tweeted "bob" "hola, mundo" 200 #{"bob"}]]
                  (map to-event)
                  index-events)
       rules #{(to-event [:cloudlog-events.testing-test/followee-tweets!0 "alice" "charlie" "alice" #{"cloudlog-events.testing-test"}])
               (to-event [:cloudlog-events.testing-test/followee-tweets!0 "bob" "eve" "bob" #{"cloudlog-events.testing-test"}])}]
   (process-link (-> followee-tweets meta :continuation) index rules)
   => #{(-> [:cloudlog-events.testing-test/followee-tweets ["charlie" 0] "alice" "hello, world" 100 #{"cloudlog-events.testing-test"}]
            to-event)
        (-> [:cloudlog-events.testing-test/followee-tweets ["eve" 0] "bob" "hola, mundo" 200 #{"cloudlog-events.testing-test"}]
            to-event)}))

[[:section {:title "process-rule"}]]
"`process-rule` takes a rule function and an event index as paramters, and returns an event index updated with the derived facts generated from the index by this rule."
(fact
 (let [index (->> [[:tweetlog/follows "charlie" "alice" #{"charlie"}]
                   [:tweetlog/follows "eve" "bob" #{"eve"}]
                   [:tweetlog/tweeted "alice" "hello, world" 100 #{"alice"}]
                   [:tweetlog/tweeted "bob" "hola, mundo" 200 #{"bob"}]]
                  (map to-event)
                  index-events)]
   (process-rule followee-tweets index)
   => (->> [[:cloudlog-events.testing-test/followee-tweets ["charlie" 0] "alice" "hello, world" 100 #{"cloudlog-events.testing-test"}]
               [:cloudlog-events.testing-test/followee-tweets ["eve" 0] "bob" "hola, mundo" 200 #{"cloudlog-events.testing-test"}]]
           (map to-event)
           index-events
           (merge-indexes index))))
