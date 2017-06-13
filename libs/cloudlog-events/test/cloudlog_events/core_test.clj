(ns cloudlog-events.core_test
  (:use [midje.sweet]
        [cloudlog-events.core])
  (:require [cloudlog.core :as cloudlog]
            [clojure.core.async :as async]))

[[:chapter {:title "Introduction"}]]
"`defrule` and `defclause` allow users to define logic that defines applications.
These macros actually define *rule functions*, which are Clojure functions with metadata,
intended to process *facts* and create new facts based on them.
See [core](cloudlog.html) for more details."

"The `cloudlog.events` package provides functions for applying rule functions on events.
An *event* is a Clojure map, containing a single change in the state of the application.
This change can be an addition of a fact, a removal of a fact, or the effect of such event
on the internal state of a rule. Each event contains the following fields:
- `:kind`: Either `:fact`, if this event represent a change to a fact, or `:rule` if it represents a change to a rule.
- `:name`: A string representing the name of the stream this event belongs to.  See [fact-table](cloudlog.html#fact-table-get-a-fully-qualified-name-for-a-fact) for details.
- `:key`: The key of the fact or the rule.  See [here](cloudlog.html#joins) for more details.
- `:ts`: The time (in milliseconds since EPOCH) in which this event was created (for facts.  See below for rules).
- `:data`: The data tuple representing a fact, or the state of a rule, excluding the key.
- `:change`: A number representing the change.  Typically, `1` represents addition, and `-1` represents removal.
- `:writers`: The event's *writer-set*, represented as an [interset](cloudlog.interset.html).
- `:readers`: The event's *reader-set*, represented as an [interset](cloudlog.interset.html).
- `:removed` (optional): If exists, account for an additional event that is identical to the original one, but with `:data` = `:removed`, and `:change` = -`:change`."

"For the examples of this package we will use the following convenience function:"

(defn event [kind name key data & {:as ev}]
  (merge {:kind kind
          :name name
          :key key
          :data data
          :ts 1000
          :change 1
          :writers #{}
          :readers #{}} ev))

(fact
 (event :fact "foo" 13 ["a" "b"] :ts 2000) => {:kind :fact
                                               :name "foo"
                                               :key 13
                                               :data ["a" "b"]
                                               :ts 2000
                                               :change 1
                                               :writers #{}
                                               :readers #{}}
 (event :fact "foo" 13 ["a" "b"] :ts 2000
        :removed ["b" "a"]) => {:kind :fact
                                :name "foo"
                                :key 13
                                :data ["a" "b"]
                                :ts 2000
                                :change 1
                                :writers #{}
                                :readers #{}
                                :removed ["b" "a"]})

[[:section {:title "Rules"}]]
"In this module we base our examples on the following rules defined [here](cloudlog.html):"
(cloudlog/defrule foo-yx [y x]
  [:test/foo x y] (cloudlog/by-anyone))

(cloudlog/defrule timeline [user tweet]
  [:test/follows user author] (cloudlog/by-anyone)
  [:test/tweeted author tweet] (cloudlog/by-anyone))

[[:chapter {:title "emitter: Create an Event-Emitting Function"}]]
"Rules start by matching a single fact.  An emitter function takes an event
representing such a fact and applies the rule function associated with the event."

"For a simple rule, the result is a sequence of derived fact."
(fact
 (let [em (emitter foo-yx)]
   (em (event :fact "test/foo" 2 [3]))
   => [(event :fact "cloudlog-events.core_test/foo-yx" 3 [2]
              ;; writers will be discussed later
              :writers #{"cloudlog-events.core_test"})]))

"For a join, the result is an event representing the *rule* produced from the fact.

Here some explanation is in order.  Our notion of facts and rules come from mathematical logic.
Facts are what mathematical logic calls *atoms* -- a combination of a name with some arguments,
and rules are logic formulas of the form `a->b`, where the `->` operator represents logical inference.
In the subset of mathematical logic we adopted, the left-hand-side of the `->` operator must be an atom
(a fact).  However, the right-hand side can be any kind of axiom -- fact or rule.  With this
we can create compound rules such as `a->b->c->d`, that should be read as `a->(b->(c->d))`.
This means that the fact `a` implies the rule `b->c->d`, which in turn means that
the fact `b` implies `c->d`, which in turn means that `c` implies `d`.
In cloudlog.clj, rule functions take whatever matches the left-hand side of the rule, and
emit whatever is on the right-hand side, be it a (derived) fact or a rule.
Our implementation does not emit the rule syntactically.  Instead it provides a tuple
that contains its underlying data.  But we still treat it as a rule."

(fact
 (let [em (emitter timeline)]
   (em (event :fact "test/follows" "alice" ["bob"]))
   => [(event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]
              :writers #{"cloudlog-events.core_test"})]))
"The `:name` component in the produced events is derived from the name of the rule, a 'bang' (`!`) and
the index of the link that emitted this event in the overall rule.  An emitter always represents
the first link in a rule, so this value is always 0."

[[:section {:title "Readers and Writers" :tag "writers"}]]
"The only parts of the event that are not easy to understand are the `:readers` and `:writers` keys.
The values associated with these keys are conceptually *sets of users*.
`:writers` represents the set of users who *may have created* the event.
Each user in this set has permission to create a similar event that, e.g., can cancel the effect of this one
(e.g., by creating an event with the same `:key`, `:data` `:readers` and `writers`
but with a complement `:change` value) and to replace the underlying datum with another one
(e.g., by creating an event with the same `:key`, `:readers` and `:writers` but different `:data`.
The `:writers` set is key to how Clojure applications manage *integrity*."

"When a rule is applied to a fact, the resulting event carries the `:writers` set associated
with the rule, which is its namespace, for reasons we discuss [in our discussion of integrity for derived facts](cloudlog.html#integrity)."
(fact
 (let [em (emitter foo-yx)]
   (em (event :fact "test/foo" 2 [3] :writers #{:foo :bar}))
   => [(event :fact "cloudlog-events.core_test/foo-yx" 3 [2] :writers #{"cloudlog-events.core_test"})]))

"`:readers` represents a set of users allowed to read an axiom. We will revisit `:readers` when discussing
[multiplier](#multiplier)."

"Rules may pose requirements for `:writers` and `:readers`.
To support this, we pass them as metadata on the data."
(fact
 (let [some-rule (fn [vec]
                   (when-not (= (meta vec) {:writers #{:w}
                                            :readers #{:r}})
                     (throw (Exception. "Did not get readers and writers as meta"))))
       em (emitter some-rule)]
   (em (event :fact "something" 1 [2 3] :writers #{:w} :readers #{:r}))
   => irrelevant))

[[:section {:title "Atomic Updates"}]]
"When an event contains a `:removed` key, it is considered an *atomic update*.
The reason is that this is a single event that flows through the system, 
and removes one piece of data and at the same time (`:ts`) also adds another piece or data."

"If the `:removed` field exists, two events will be emitted, one for `:data` and one for `:removed`."
(fact
 (let [em (emitter foo-yx)]
   (em (event :fact "test/foo" 2 [3]
              :removed [1]))
   => [(event :fact "cloudlog-events.core_test/foo-yx" 3 [2]
              :writers #{"cloudlog-events.core_test"})
       (event :fact "cloudlog-events.core_test/foo-yx" 1 [2]
              :change -1
              :writers #{"cloudlog-events.core_test"})]))

[[:chapter {:title "multiplier: Create a Function Applying Rules to Facts" :tag "multiplier"}]]
"The lowest level of event processing is taking two corresponding events 
(i.e., an event for a fact and a rule with the same key) and producing a collection of events
that are produced from this combination."

"A multiplier is constructed based on a rule function, and a number (>0) representing the link in the rule."
(def mult1 (multiplier timeline 1))

"The returned function takes two arguments: a *rule event* and a matching *fact event*.
It returns a sequence of events created by this combination."
(fact
 (mult1 (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]
               :writers #{"cloudlog-events.core_test"})
        (event :fact "test/tweeted" "bob" ["something"]))
 => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["something"]
            :writers #{"cloudlog-events.core_test"})])

"We call this unit a *multiplier*, because it multiplies the `:change` field of the rule and the fact event.
Imagine we have `n` facts and `m` rules with a certain key.  In order to have all possible derived events
we should invoke the multiplier function `n*m` times, once for each combination.
Now imagine these `n` facts are actually the same fact, just added `n` times, and the `m` rules are the same
rule added `m` times.  The state of the application can therefore be represented using two events,
one for the fact, with `:change` value of `n`, and one for the rule with `:change` value of `m`.
Now if we introduce these two events to the multiplier function, we would like to get the same result as before,
that is, applying the rule to the fact `n*m` times.  To achieve this, the multiplier function multiplies the
`:change` values, so that every event it returns has a `:change` value of `n*m`."
(fact
 (mult1 (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"] :change 2)
        (event :fact "test/tweeted" "bob" ["something"] :change 3))
 => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["something"] :change 6)])

"Multiplies can handle atomic updates given in the fact:"
(fact
 (mult1 (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"])
        (event :fact "test/tweeted" "bob" ["something else"]
               :removed ["something"]))
 => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["something else"]
            :removed ["something"])])

"... or in the rule."
(fact
 (mult1 (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["eve" "bob"]
               :removed ["alice" "bob"])
        (event :fact "test/tweeted" "bob" ["something"]))
 => [(event :fact "cloudlog-events.core_test/timeline" "eve" ["something"])
     (event :fact "cloudlog-events.core_test/timeline" "alice" ["something"]
            :change -1)])

[[:section {:title "Confidentiality"}]]
"A multiplier is a meeting place of facts with rules, which in turn are derived from other facts.
Such a meeting place is where the *confidentiality* of the system is put to the test."

"Consider a dating service, where users can open *tickets* with their personal details,
set up a *watch* for tickets that match certain criteria (for simplicity, let's say, gender, location, and age-range).
A rule for creating search results can look like this:"
(cloudlog/defrule ticket-by-gender-and-location [[gender loc] ticket-id age]
  [:test/ticket ticket-id gender age loc] (cloudlog/by-anyone))

(cloudlog/defrule dating-matches [watch-id ticket-id]
  [:test/watch watch-id gender loc min-age max-age] (cloudlog/by-anyone)
  [ticket-by-gender-and-location [gender loc] ticket-id age] (cloudlog/by-anyone)
  (when (and (<= age max-age)
             (>= age min-age))))

"`ticket-by-gender-and-location` is an indexing of raw `:test/ticket`, performed by the following rule:"

"People openning tickets on dating services often wish their tickets to be limited to a certain
set of users.  Similarly, people setting up a watch often wish to keep their preferences secret.
These preferences are recorded in the `:readers` set of each fact.
`:readers` is an [interset](cloudlog.interset.html) specifying who can read this fact.
Each element in `:readers` represents a *named set* of users, and the *readers set* for the event
is an intersection of all these named sets.

In Cloudlog, rules are not responsible for confidentiality; the Cloudlog implementation is.
Imagine a Alice openning a ticket in the dating service, willing to make it visible only to 
`:male` `:long-time-users` (i.e., to members of the intersection of the `:male` and `:long-time-users`
named sets).  The event will look like this:"
(def alices-ticket-event
  (event :fact ":test/ticket" 1234 [:female 35 "NYC"]
         :readers #{:male :long-time-users}))

"The `emitter` applying the `ticket-by-gender-and-location` rule will keep the `:readers` set as-is:"
(def ticket-by-gender-and-location-event
  (let [em (emitter ticket-by-gender-and-location)]
    (first (em alices-ticket-event))))
(fact
 (:readers ticket-by-gender-and-location-event) => #{:male :long-time-users})

"When Bob looks for dates like Alice, he creates `:test/watch` with an event looking like this:"
(def bobs-watch-event
  (event :fact ":test/watch" 9876 [:female "NYC" 30 40]
         :readers #{:male [:user= "bob"]}))
"He places only himself in the `:readers` set, so no one else will know he's looking, or what he's looking for.
Bob can be seen as an intersection of all the named sets he's a member of.  One such set is `[:user= \"bob\"]`,
a partition set that contains only him, but Bob is also a member of the `:male` set.
Note that Bob is not a member of the `:long-time-users` set, since he's new to the service.

Here too, the `emitter` function does not change the `:readers` set."
(def dating-matches-event
  (let [em (emitter dating-matches)]
    (first (em bobs-watch-event))))
(fact
 (:readers dating-matches-event) => #{:male [:user= "bob"]})

"Now is where things get interesting.  When the second link of the `dating-matches` rule kicks in,
taking both `dating-matches-event` as the rule-event and `ticket-by-gender-and-location-event` as its fact event.
What shall the `:reader` set of the result be?  That of the rule, conveying Bob's wishes, or that of the fact,
conveying Alice's wishes?"

"The `:readers` set of the resulting events will be an *intersection* of both `:readers` sets, (which
is actually a [union of the Clojure sets](cloudlog.interset.html#intersection))."
(fact
 (let [mult (multiplier dating-matches 1)
       ev (first (mult dating-matches-event ticket-by-gender-and-location-event))]
   (:readers ev) => #{:male [:user= "bob"] :long-time-users}))
"The resulting `:readers` set is an intersection of `:male` and `[:user= \"bob\"]`, which Bob is a member of,
with `:long-time-users`, which Bob is not a member of.  This places Bob outside the intersection, and therefore
unable to see the resulting fact (which is what we expect)."

[[:section {:title "Integrity" :tag "integrity"}]]
"Since rules [take ownership over the resulting events](cloudlog.html#integrity), the `:writers` set of events coming out of a multiplier
must come from the rule event:"
(fact
 (let [mult (multiplier timeline 1)]
   (mult (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"] :writers #{"example.com"})
         (event :fact ":test/tweeted" "bob" ["hello"] :writers #{[:user= "bob"]}))
   => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["hello"] :writers #{"example.com"})]))

[[:section {:title "Timestamps"}]]
"Events are stored with a primary key that combines `:key` and `:ts`.  Out of these, `:key` is used for [sharding](https://en.wikipedia.org/wiki/Shard_%28database_architecture%29), and `:ts` is used for sorting.
Typically, we will want to query events that share `:name` and `:key` values together, so it makes sense to store all of them together.
The `:ts` provides an additional key, so we can refer to specific events that share `:name` and `:key`."

"Some event processing systems like [Apache Storm](http://storm.apache.org/) guarantee [at least once](http://storm.apache.org/releases/1.0.0/Guaranteeing-message-processing.html) semantics.
The advantage of this approach is that they guarantee that everything that needs to be processed gets processed, and do this at
relatively low cost.  The disadvantage is that if we use such a mechanism we need to be able to cope with processing being done more than once.
We cope with this in two ways:
1. All computation is declarative, meaning that if you call a rule function twice with the same input you are guaranteed to get the same output.
2. We use a consistent unique key for each result, so that if we get the same result a second time, the new result gets \"swallowed\" by the old result.

The unique ID we use is the timestamp -- `:ts`.  Timestamps are given to *fact events* when they are created.
Each timestamp in the system, whether it refers to a raw fact, derived fact or rule events, is always originally
a timestamp given to a raw fact when it was created.  This takes the computation time out of the equation for calculating
these timestamps."

"An `emitter` function simply moves the `:ts` attribute from its input event to its output."
(fact
 (let [em (emitter foo-yx)
       ev (first (em (event :fact ":test/foo" 1 [2] :ts 1234)))]
   (:ts ev) => 1234))

"For a `multiplier`, the question of which `:ts` value to produce is more complicated.
Each of the input events (the rule and the fact) has a timestamp, so which one should we use?
Because we want each timestamp to be a real timestamp (from a raw fact event), we need to take
exactly one of them.  We take the one from the *fact*."
(fact
 (let [mult (multiplier timeline 1)
       ev (first (mult (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"] :ts 2345)
                       (event :fact ":test/tweeted" "bob" ["hello"] :ts 3456)))]
   (:ts ev) => 3456))

"Why? because we need to keep the value unique per `:key`.
Consider the above example.  If we take the rule `:ts` value, the `:ts` for each entry in Alice's timeline
will be the `:ts` of the rule that was applied, which takes its `:ts` from the `:test/follows` fact.
This means that every tweet made by Bob will appear in Alice's timeline with the same `:ts` value,
and hence tweets will overrun one another.

But what guarantee do we have that if we take the fact's `:ts` we do not get into such a situation?
There is no hard guarantee for that.  Hoever, the whole point of using rules it to [denormalize](https://en.wikipedia.org/wiki/Denormalization) the data so that it is searchable with different keys.  It is pointless to have a rule that keeps the same `:key`.
Such rules can be easily converted to [clauses](cloudlog.html#defclause), which do not require redundant information to be stored."

[[:chapter {:title "matcher: Matches Rules and Facts" :tag "matcher"}]]
"[multipliers](#multiplier) apply rules to facts, and expect to be given both as parameters.  However, in most cases
we are only given one -- a rule or a fact, and we need to apply that rule or that fact to all matching rules and facts.
These matching rule and fact events are typically stored in some database.
The database can provide all events that match a combination of `:kind`, `:name` and `:key`."

"As we wish to decouple Cloudlog's implementation from any particular database, we use `core.async` channels to interact
with the database.  We provide the event to be matched on a channel, along with a channel for the output,
and in return *someone* provides us with the matching events on the channel we provided, and closes the channel.
On the other side of the channel there could be an interface to a cloud database, an in-memory cache or a combination
of the two.  We don't care as long as we get what we need from the other side of the channel."

"A matcher instance is constructed by the `matcher` function, which is given a rule function, a link number and a channel to the database."
(def db-chan (async/chan 1000))
(def timeline-matcher (matcher timeline 1 db-chan))


[[:section {:title "Matching Rules for Facts"}]]
"The returned matcher is a function that takes an event and an output channel for the resulting events."
(def res-chan (async/chan 1000))
(timeline-matcher (event :fact ":test/tweeted" "bob" ["hello"]) res-chan)

"The matcher will emit a query to the `db-chan`, specifying what it is looking for"
(def db-request
  (async/alts!! [db-chan
                 (async/timeout 1000)]))

(fact
 (-> db-request second) => db-chan)
"The request consists of a pair `[req chan]`, where `req` is a partial event that matches what we are looking for:"
(fact
 (-> db-request first first) => {:kind :rule
                                 :name "cloudlog-events.core_test/timeline!0"
                                 :key "bob"})

"And `chan` is the channel on which the database is expected to provide the reply"
(def reply-chan (-> db-request first second))

"Next, the database emits matching rules on the reply channel, and closes it."
(fact
 (async/>!! reply-chan (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]))
 (async/>!! reply-chan (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["eve" "bob"]))
 ;; The following two events should cancel one another
 (async/>!! reply-chan (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["fred" "bob"]))
 (async/>!! reply-chan (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["fred" "bob"] :change -1))
 (async/close! reply-chan))

"For each such event the matcher will emit the events obtained by multiplying the fact and the rules."
(fact
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["hello"]) res-chan]
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [(event :fact "cloudlog-events.core_test/timeline" "eve" ["hello"]) res-chan])

"Finally, the channel needs to be closed."
(fact
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [nil res-chan])

[[:section {:title "Matching Facts for Rules"}]]
"A matcher function can accept either a fact event or a rule event."
(def res-chan (async/chan 1000))
(timeline-matcher (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]) res-chan)

"For a rule, the matcher will query the database for matching facts."
(def db-request
  (async/alts!! [db-chan
                 (async/timeout 1000)]))
(fact
 (-> db-request second) => db-chan)
(fact
 (-> db-request first first) => {:kind :fact
                                 :name "test/tweeted"
                                 :key "bob"}
 (def reply-chan (-> db-request first second)))

"Now the database replies fact events."
(fact
 (async/>!! reply-chan (event :fact "test/tweeted" "bob" ["hello"]))
 (async/>!! reply-chan (event :fact "test/tweeted" "bob" ["world"]))
 (async/close! reply-chan))

"The results are emitted on the `res-chan`:"
(fact
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["hello"]) res-chan]
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [(event :fact "cloudlog-events.core_test/timeline" "alice" ["world"]) res-chan]
 (async/alts!! [res-chan
                (async/timeout 1000)]) => [nil res-chan])


[[:chapter {:title "Accumulating Events"}]]
"[emitters](#emitter) [multipliers](#multiplier) and [matchers](#matcher) treat each event individually.
This approach is consistent with the practice of [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html).
However, sometimes we wish to see the complete picture and accumulate events.
The following functions support event accumulation."

[[:section {:title "accumulate"}]]
"`accumulate` is a *reducing function* that accumulates events into a map for which the keys are the events themselves, excluding their `:change` fields,
and the values are the sums of the `:change` for one particular event."

"With arity 0, `accumulate` returns and empty map."
(fact
 (accumulate) => {})

"With arity 2, it will take a map and an event, and if the event is not already a key in the map, it will be added
so that the `:change` is the value"
(fact
 (accumulate {} (event :fact "foo/bar" "k1" [1 2]))
 => {(-> (event :fact "foo/bar" "k1" [1 2])
         (dissoc :change)) 1})

"If the event exists as a key in the map (possibly with a different `:change` value),
the entry is updated to include the sum of the existing value and the event's `:change`."
(fact
 (accumulate {(-> (event :fact "foo/bar" "k1" [1 2])
                  (dissoc :change)) 1}
             (event :fact "foo/bar" "k1" [1 2]))
 => {(-> (event :fact "foo/bar" "k1" [1 2])
         (dissoc :change)) 2})

"[Atomic update events](cloudlog-events.html#atomic-updates) are accumulated as two different events.
In the following example the first event sets the value of `k1` to `[1 2]`,
and the second event is an atomic update, updating its value to `[2 3]`.
The accumulation shows only a single event with the updated value.
(The old value is still present, but with value of 0)."
(fact
 (reduce accumulate {}
         [(event :fact "foo/bar" "k1" [1 2])
          (event :fact "foo/bar" "k1" [2 3]
                 :removed [1 2])])
 => {(-> (event :fact "foo/bar" "k1" [2 3])
         (dissoc :change)) 1
     (-> (event :fact "foo/bar" "k1" [1 2])
         (dissoc :change)) 0})

[[:section {:title "accumulated-events"}]]
"With an accumulated map we can get a sequence of the underlying accumulated events by calling `accumulated-events`."
(fact
 (set (accumulated-events {(-> (event :fact "foo/bar" "k1" [1 2])
                               (dissoc :change)) 1
                           (-> (event :fact "foo/bar" "k2" [3 4])
                               (dissoc :change)) 2}))
 => #{(event :fact "foo/bar" "k1" [1 2])
      (event :fact "foo/bar" "k2" [3 4] :change 2)})

"Events for which the accumulated `:change` value is zero or negative are ignored."
(fact
 (accumulated-events {(-> (event :fact "foo/bar" "k1" [1 2])
                          (dissoc :change)) 0
                      (-> (event :fact "foo/bar" "k2" [3 4])
                          (dissoc :change)) -1})
 => [])

[[:section {:title "accumulate-db-chan"}]]
"Given a `database-chan`, such as [the one provided for DynamoDB](dynamo.html#database-chan), and the one consumed by [matchers](#matcher),
`accumulate-db-chan` returns a new channel which implements the same protocol but accumulates the events coming from the given database interface."

"Consider for example the following mock database, which will answer each request with the same three events:"
(fact
 (def mock-db-chan (async/chan))
 (async/go
   (loop []
     (let [[req resp-chan] (async/<! mock-db-chan)]
       (async/>! resp-chan (event :fact "foo/bar" "k1" [1 2]))
       (async/>! resp-chan (event :fact "foo/bar" "k2" [3 4]))
       (async/>! resp-chan (event :fact "foo/bar" "k1" [1 2] :change -1))
       (async/close! resp-chan))
     (recur))))

"Please note that the first and third events cancel each other.
Now we use `accumulate-db-chan` to create a new channel."
(fact
 (def my-db-chan (accumulate-db-chan mock-db-chan)))

"When sending a request to `my-db-chan`, we should get the events provided on `mock-db-chan`, but accumulated."
(fact
 ;; Read everything from the response channel
 (defn slurp-response [resp-chan]
   (loop [res #{}]
     (let [ev (async/<!! resp-chan)]
       (cond ev
             (recur (conj res ev))
             :else
             res))))

 (let [resp-chan (async/chan)]
   (async/>!! my-db-chan [{:some :request} resp-chan])
   (slurp-response resp-chan) => #{(event :fact "foo/bar" "k2" [3 4])}))

"This should work multiple times..."
(fact
 (let [resp-chan (async/chan)]
   (async/>!! my-db-chan [{:some :request} resp-chan])
   (slurp-response resp-chan) => #{(event :fact "foo/bar" "k2" [3 4])}))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "split-atomic-update"}]]
"The function `split-atomic-update` takes an event that may or may not have a `:changed` field (an atomic update),
and returns a sequence of one or two events."

"For events without a `:changed` field, `split-atomic-update` returns the event as-is."
(fact
 (split-atomic-update (event :fact "test/tweeted" "bob" ["hello"]))
 => [(event :fact "test/tweeted" "bob" ["hello"])])

"For events that do have a `:changed` field, `split-atomic-update` returns two events,
one based on `:data` and the other based on `:removed`."
(fact
 (split-atomic-update (event :fact "test/tweeted" "bob" ["hello"]
                             :removed ["hola"]))
 => [(event :fact "test/tweeted" "bob" ["hello"])
     (event :fact "test/tweeted" "bob" ["hola"]
            :change -1)])

[[:section {:title "join-atomic-updates"}]]
"Given a collection of events, `join-atomic-updates` returns a possibly smaller collection of events
where all events that [form atomic updates](#matches) are unified into single events."

"If no events match, the function returns a collection with all original events"
(fact
 (join-atomic-updates [(event :fact "foo" "key1" [1 2 3])
                       (event :fact "foo" "key2" [2 3 4])
                       (event :fact "foo" "key3" [3 4 5])])
 => [(event :fact "foo" "key1" [1 2 3])
     (event :fact "foo" "key2" [2 3 4])
     (event :fact "foo" "key3" [3 4 5])])

"If some events match, `join-atomic-updates` joins them into a single event"
(fact
 (join-atomic-updates [(event :fact "foo" "key1" [2 3 4])
                       (event :fact "foo" "key2" [1 2 3])
                       (event :fact "foo" "key2" [2 3 4] :change -1)
                       (event :fact "foo" "key3" [3 4 5] :change -1)])
 => [(event :fact "foo" "key1" [2 3 4])
     (event :fact "foo" "key2" [1 2 3]
            :removed [2 3 4])
     (event :fact "foo" "key3" [3 4 5] :change -1)])

[[:section {:title "find-atomic-update"}]]
"The key to implementing `join-atomic-updates` is being able to find, for a given event, a matching event within a given collection.
`find-atomic-update` takes an event and a collection of other events, and returns a pair: `[match rest]`,
where `match` is an event or `nil`, and `rest` is a collection of events."

"If the collection of events does not contain a match for the given event,
`nil` and the original collection are returned."
(fact
 (find-atomic-update (event :fact "foo" "key1" [1 2 3])
                     [(event :fact "foo" "key1" [2 3 4])
                      (event :fact "foo" "key2" [3 4 5] :change -1)])
 => [nil [(event :fact "foo" "key1" [2 3 4])
          (event :fact "foo" "key2" [3 4 5] :change -1)]])

"If the collection of events contains a match, that match is returned, and the collection is returned with the match removed."
(fact
 (find-atomic-update (event :fact "foo" "key1" [1 2 3])
                     [(event :fact "foo" "key1" [2 3 4])
                      (event :fact "foo" "key1" [2 3 4] :change -1)
                      (event :fact "foo" "key2" [3 4 5] :change -1)])
 => [(event :fact "foo" "key1" [2 3 4] :change -1)
     [(event :fact "foo" "key1" [2 3 4])
      (event :fact "foo" "key2" [3 4 5] :change -1)]])

[[:section {:title "matching?"}]]
"`matching?` takes two events and returns whether they can be unified to a single atomic update.
Two events are considered to match if they share all fields except `:data` and `:change`, and the values of `:change` are
the negation of one another."
(fact
 (matching? (event :fact "foo" "key1" [1 2 3])
            (event :fact "foo" "key1" [2 3 4] :change -1)) => true
 (matching? (event :fact "foo" "key1" [1 2 3])
            (event :fact "foo" "key1" [2 3 4])) => false
 (matching? (event :fact "foo" "key1" [1 2 3])
            (event :fact "foo" "key2" [2 3 4] :change -1)) => false)
