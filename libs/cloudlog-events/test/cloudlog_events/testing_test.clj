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
The output of a rule or a clause is a (pure) function of its input.
They can therefore be tested by creating *scenarios*, which are sets of facts, and then evaluating *queries*, which result in a set of result tuples."

"This library defines a DSL for creating scenarios and evaluating queries.
The [scenario](#scenario) macro defines the scope of a single scenario.
[Queries](#query) made inside a scenario will only consider facts [emitted](#emit) inside the same scenario."

"Access control is a big part of Cloudlog, and should be a part of any testing framework for Cloudlog.
Any [emition](#emit) or [query](#query) needs to be performed on behalf of a user.
The [as](#as) assigns a user to all underlying operations."

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

[[:chapter {:title "scenario"}]]
"The `scenario` macro executes the underlying code, similar to `do`."
(fact
 (let [x (atom 0)]
   (scenario
    (swap! x inc)
    (swap! x inc)
    @x) => 2))

"It binds a new atom to the `*scenario*` dynamic variable, containing an empty vector."
(fact
 (scenario
  @*scenario* => []))

[[:chapter {:title "as"}]]
"The `as` macro also evaluates to the underlying code, similar to `do`."
(fact
 (let [x (atom 0)]
   (as "alice"
       (swap! x inc)
       (swap! x inc)
       @x) => 2))

"It binds the dynamic variable `*user*` to the given user value."
(fact
 (as "alice"
     *user* => "alice"))

[[:chapter {:title "emit"}]]
"The `emit` function can only be called from within a [scenario](#scenario), inside an [as](#as) block."
(fact
 (as "alice"
     (emit [:foo/bar 1 2 3])) => (throws "emit can only be called from within a scenario")
 (scenario
  (emit [:foo/bar 1 2 3])) => (throws "emit can only be called from within an `as` block"))

"It adds a single fact to the `*scenario*`."
(fact
 (scenario
  (as "alice"
      (emit [:tweetlog/follows "alice" "bob"]))
  @*scenario* => [["tweetlog/follows" "alice" ["bob"] #{"alice"} #{}]]))

"The fact added to `*scenario*` is a 5-tuple with the following [event fields](cloudlog-events.html#introduction):
1. `:name`
2. `:key`
3. `:data`
4. `:writers`
5. `:readers`"

"The last two elements in a fact vector stored in the `*scenario*` are the `:writers` and `:readers` sets associated with the fact.
In the previous example, the added fact had `#{\"alice\"}` as its `:writers` set, and `#{}` as its `:readers` set.
This means that the fact is attributed to `alice`, and everybody is allowed to read it.
These are the defaults, but they can be changed.
If a second arguent is given, it is taken as the `:writers` set."
(fact
 (scenario
  (as "alice"
      (emit [:tweetlog/follows "alice" "bob"] #{}))
  @*scenario* => [["tweetlog/follows" "alice" ["bob"] #{} #{}]]))

"This means that the fact is attributed to no one -- anyone could have emitted it."

"An optional third argument provides the `:readers` set."
(fact
 (scenario
  (as "alice"
      (emit [:tweetlog/follows "alice" "bob"] #{"alice"} #{"alice"}))
  @*scenario* => [["tweetlog/follows" "alice" ["bob"] #{"alice"} #{"alice"}]]))

"This means only `alice` can read this fact, or anything derived from it."

"Axiom's [gateway tier](gateway.html) defines rules regarding who can emit which fact.
Concepually, the user needs to be a member of the fact's `:writers` set, according to its definition as an [interset](cloudlog.interset.html).
In short, an interset consists of a union (`[]`) of intersections (`#{}`) of named groups, which can take two forms:
1. a string, representing a single user (a group that consists of one user who's ID is the content of the string), and
2. a vector representing a group whos members are determined by facts and rules.
In the case of a vector, a vector of the form `[:some/name arg1 arg2 arg3...]` represents the group of every user `u` for which a fact `[:some/name u arg1 arg2 arg3...]`
exists in Axiom.
Technically, this could either be a raw fact or a derived fact. 
However true access control will only be acheived by using derived facts for this purpose, since any user can emit any fact in Axiom."

"The `emit` function checks, based on the scenario before its invocation, that the user on behalf of which the fact is emitted is indeed allowed to emit this fact.
For example, imagine our application allows users to send private messages only to their followers.
To send a message to someone we therefore need to identify as someone they follow.
Now imagine `bob` and `malory` trying to send a message to `alice`, who follows `bob` but not `malory`.
`emit` should succeed for `bob`, but throw an exception for `malory`."
(fact
 (scenario
  (as "alice"
      (emit [:tweetlog/follows "alice" "bob"]))
  (as "bob"
      (emit [:tweetlog/message "alice" "malory" "Hi friend!"] #{[:cloudlog-events.testing-test/follower "alice"]}))
  (as "malory"
      (emit [:tweetlog/message "alice" "malory" "Hi 'friend'!"] #{[:cloudlog-events.testing-test/follower "alice"]})
      => (throws "Cannot emit fact. malory is not a member of #{[:cloudlog-events.testing-test/follower \"alice\"]}."))))


[[:chapter {:title "query"}]]
"`query` performs a query based on the current [scenario](#scenario), on behalf of the [current user](#as).
Queries exercise [clauses](cloudlog.html#defclause) by providing them an input tuple, expecting output tuples in return."

"`query` needs to be called from within a `scenario` and an an `as` block."
(fact
 (as "alice"
     (query [:foo/bar 1 2 3])) => (throws "query can only be called from within a scenario")
 (scenario
  (query [:foo/bar 1 2 3])) => (throws "query can only be called from within an `as` block"))

"The `query` function takes a query vector that consists of the name of the predicate (the keyword mentioned in the clause)
and all its input arguments, and returns a set of the results as tuples corresponding to the output arguments."
(fact
 (scenario
  (as "eve"
      (emit [:tweetlog/follows "eve" "bob"]))
  (as "alice"
      (emit [:tweetlog/tweeted "alice" "hello, world" 100]))
  (as "bob"
      (emit [:tweetlog/tweeted "bob" "hola, mundo" 200]))
  (as "charlie"
      (emit [:tweetlog/follows "charlie" "alice"])
      (query [:tweetlog/timeline "charlie" 0 3]) => #{["alice" "hello, world" 100]})))

"A query only returns results visible to the current user."
(fact
 (scenario
  (as "alice"
      ;; The following tweet is intended only for bob
      (emit [:tweetlog/tweeted "alice" "hello, world" 100] #{"alice"} #{"bob"}))
  (as "charlie"
      (emit [:tweetlog/follows "charlie" "alice"])
      ;; So although charlie follows alice, he doesn't get it.
      (query [:tweetlog/timeline "charlie" 0 3]) => #{})))

"If no results are found, a map containing the keys and rules is returned (same as in [apply-rules](#apply-rules))."
(fact
 (scenario
  (as "charlie"
      (emit [:tweetlog/follows "charlie" "alice"])
      (query [:tweetlog/timeline "charlie" 0 3]) => map?)))

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
`to-event` converts a 5-tuple produced by [emit](#emit) to an event as follows:
- `:kind` -- always `:fact`
- `:name`, `:key`, `:data`, `:writers` and `:readers` are taken from the tuple.
- `:ts` -- always 1
- `:change` -- always 1"
(fact
 (to-event ["foo/bar" "key" [1 2 3] #{"bob"} #{"alice"}])
 => {:kind :fact
     :name "foo/bar"
     :key "key"
     :data [1 2 3]
     :ts 1
     :change 1
     :writers #{"bob"}
     :readers #{"alice"}})

[[:section {:title "index-events"}]]
"To efficiently fecth events that match a certain `:name` and `:key` values we create an index using `index-events`.
`index-events` takes a collection of events and creates an event index for them."

"For an empty collection, an empty map is returned."
(fact
 (index-events []) => {})

"An index is a map where the keys are `[:name :key]` pairs (the `:name` is provided as a keyword), and the values are sets of matching events."
(fact
 (index-events (->> [["foo/bar" 123 [1 2 3] #{"bob"} #{}]]
                    (map to-event)))
 => {[:foo/bar 123] #{(to-event ["foo/bar" 123 [1 2 3] #{"bob"} #{}])}})

"`index-events` reduces the given events using [merge-indexes](#merge-indexes) as the reducer function to create an index."
(fact
 (index-events (->> [["foo/bar" 123 [1 2 3] #{"bob"} #{}]
                     ["foo/bar" 124 [2 3 4] #{"bob"} #{}]
                     ["foo/bar" 123 [2 3 4] #{"bob"} #{}]]
                    (map to-event)))
 => {[:foo/bar 123] #{(to-event ["foo/bar" 123 [1 2 3] #{"bob"} #{}])
                      (to-event ["foo/bar" 123 [2 3 4] #{"bob"} #{}])}
     [:foo/bar 124] #{(to-event ["foo/bar" 124 [2 3 4] #{"bob"} #{}])}})

[[:section {:title "merge-indexes"}]]
"`merge-indexes` merges event indexes.
Given zero arguments if returns an empty map."
(fact
 (merge-indexes) => {})

"Given two event indexes as input, it merges them."
(fact
 (merge-indexes {[:foo/bar 1] #{(to-event ["foo/bar" 1 [2 3] #{} #{}])}}
                {[:foo/bar 2] #{(to-event ["foo/bar" 2 [3 4] #{} #{}])}})
 => {[:foo/bar 1] #{(to-event ["foo/bar" 1 [2 3] #{} #{}])}
     [:foo/bar 2] #{(to-event ["foo/bar" 2 [3 4] #{} #{}])}})

"In case the two indexes have a key in common, the sets are merged."
(fact
 (merge-indexes {[:foo/bar 1] #{(to-event ["foo/bar" 1 [2 3] #{} #{}])}}
                {[:foo/bar 1] #{(to-event ["foo/bar" 1 [3 4] #{} #{}])}})
 => {[:foo/bar 1] #{(to-event ["foo/bar" 1 [2 3] #{} #{}])
                    (to-event ["foo/bar" 1 [3 4] #{} #{}])}})

[[:section {:title "process-initial-link"}]]
"`process-initial-link` processes link-0 of a rule function.
It receives a rule function and an event index as paramters, and returns a set of resulting events."
(fact
 (let [index (->> [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{}]
                   ["tweetlog/follows" "eve" ["bob"] #{"eve"} #{}]]
                  (map to-event)
                  index-events)]
   (process-initial-link followee-tweets index)
   => #{(-> ["cloudlog-events.testing-test/followee-tweets!0" "alice" ["charlie" "alice"] #{"cloudlog-events.testing-test"} #{}]
            to-event (assoc :kind :rule))
        (-> ["cloudlog-events.testing-test/followee-tweets!0" "bob" ["eve" "bob"] #{"cloudlog-events.testing-test"} #{}]
            to-event (assoc :kind :rule))}))

[[:section {:title "process-link"}]]
"`process-link` takes as paramters a link function (a `:continuation` of a rule function), a set of rule events and an event index with facts, 
and returns a set of events resulting from applying every relevant `:fact` event from the index to every rule event in the given set."
(fact
 (let [index (->> [["tweetlog/tweeted" "alice" ["hello, world" 100] #{"alice"} #{}]
                   ["tweetlog/tweeted" "bob" ["hola, mundo" 200] #{"bob"} #{}]]
                  (map to-event)
                  index-events)
       rules #{(to-event ["cloudlog-events.testing-test/followee-tweets!0" "alice" ["charlie" "alice"] #{"cloudlog-events.testing-test"} #{}])
               (to-event ["cloudlog-events.testing-test/followee-tweets!0" "bob" ["eve" "bob"] #{"cloudlog-events.testing-test"} #{}])}]
   (process-link (-> followee-tweets meta :continuation) index rules)
   => #{(-> ["cloudlog-events.testing-test/followee-tweets" ["charlie" 0] ["alice" "hello, world" 100] #{"cloudlog-events.testing-test"} #{}]
            to-event)
        (-> ["cloudlog-events.testing-test/followee-tweets" ["eve" 0] ["bob" "hola, mundo" 200] #{"cloudlog-events.testing-test"} #{}]
            to-event)}))

[[:section {:title "process-rule"}]]
"`process-rule` takes a rule function and an event index as paramters, and returns an event index updated with the derived facts generated from the index by this rule."
(fact
 (let [index (->> [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{}]
                   ["tweetlog/follows" "eve" ["bob"] #{"eve"} #{}]
                   ["tweetlog/tweeted" "alice" ["hello, world" 100] #{"alice"} #{}]
                   ["tweetlog/tweeted" "bob" ["hola, mundo" 200] #{"bob"} #{}]]
                  (map to-event)
                  index-events)]
   (process-rule followee-tweets index)
   => (->> [["cloudlog-events.testing-test/followee-tweets" ["charlie" 0] ["alice" "hello, world" 100] #{"cloudlog-events.testing-test"} #{}]
            ["cloudlog-events.testing-test/followee-tweets" ["eve" 0] ["bob" "hola, mundo" 200] #{"cloudlog-events.testing-test"} #{}]]
           (map to-event)
           index-events
           (merge-indexes index))))

[[:section {:title "apply-rules"}]]
"The `apply-rules` function takes a collection of facts [represented as vectors](#to-event) and a vector containing a rule name and a key
and returns a set of `:data` tuples that together with the rule name and the key form facts derived from given collection of raw facts using all available rules."
(fact
 (let [facts [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{}]
              ["tweetlog/follows" "eve" ["bob"] #{"eve"} #{}]
              ["tweetlog/tweeted" "alice" ["hello, world" 100] #{"alice"} #{}]
              ["tweetlog/tweeted" "bob" ["hola, mundo" 200] #{"bob"} #{}]]]
   (apply-rules facts [:cloudlog-events.testing-test/followee-tweets ["charlie" 0]]) => #{["alice" "hello, world" 100]}))

"The tuples returned by `apply-rules` are annotated with a `:readers` meta-attribute, representing their `:readers` set.
This is useful in order to later filter out tuples a certain user cannot know about."
(fact
 (let [facts [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{[:foo/bar 1]}]
              ["tweetlog/tweeted" "alice" ["hello, world" 100] #{"alice"} #{[:foo/bar 2]}]]]
   (->>
    (apply-rules facts [:cloudlog-events.testing-test/followee-tweets ["charlie" 0]])
    (map #(-> % meta :readers))
    set) => #{#{[:foo/bar 1] [:foo/bar 2]}}))

"If no result is available, `apply-rules` returns a map with the following fields to help debugging the problem:
- `:keys`: A set of all the keys that exist in the index, and
- `:rules`: A set of all the names of the rules that were applied."

"For example, if in the above example we misspell the name of the rule and write, e.g., `followees-tweets` instead of `followee-tweets`,
we get a map telling us what names are valid so we can adjust the test (in this case) or the rule if the mistake was done there."
(fact
 (let [facts [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{}]
              ["tweetlog/follows" "eve" ["bob"] #{"eve"} #{}]
              ["tweetlog/tweeted" "alice" ["hello, world" 100] #{"alice"} #{}]
              ["tweetlog/tweeted" "bob" ["hola, mundo" 200] #{"bob"} #{}]]]
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

[[:section {:title "identity-set"}]]
"A user's *identity set* is a union of all the groups a user is a member of.
A user is a member of his or her singleton group (a group named after that user) and zero or more rule-based groups.
The `identity-set` function takes a user ID, a scenario (collection of fact tuples) and an interset, 
and returns some superset of the user's identity set (as an interset).
This superset is tight enough around the given interset to allow comparison against it to check if the user is or is not a member of that interset."

"For an interset that does not mention any rule-based groups, the returned interset is the user's singleton set."
(fact
 (identity-set "alice" [] #{}) => #{"alice"})

"If the given interset mentiones rule-based groups, the returned set is an intersection of all the groups *of the same predicate* for which the user is a member of.
For example, if the given interset mentions `[:cloudlog-events.testing-test/follower \"bob\"]` (a follower of `alice`),
the returned interset will be an intersection of all the groups based on who follows the user, regardless of whether or not `bob` follows her."
(fact
 (let [scenario [["tweetlog/follows" "charlie" ["alice"] #{"charlie"} #{}]
                 ["tweetlog/follows" "dave" ["alice"] #{"dave"} #{}]]]
   (identity-set "alice" scenario #{[:cloudlog-events.testing-test/follower "bob"]})
   => #{"alice"
        [:cloudlog-events.testing-test/follower "charlie"]
        [:cloudlog-events.testing-test/follower "dave"]}))

"If no results are found for a rule-based group, no results are returned."
(fact
 (let [scenario []]
   (identity-set "alice" scenario #{[:cloudlog-events.testing-test/follower "bob"]})
   => #{"alice"}))

"`identity-set` ignores groups that are not rule-based (groups that are not represented as vectors)."
(fact
 (identity-set "alice" [] #{:foo :bar "baz"}) => #{"alice"})

