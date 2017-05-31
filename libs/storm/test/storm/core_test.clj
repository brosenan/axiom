(ns storm.core-test
  (:require [midje.sweet :refer :all]
            [storm.core :refer :all]
            [cloudlog.core :as clg]
            [permacode.core :as perm]
            [org.apache.storm
             [clojure :as s]
             [config :as scfg]
             [testing :as st]]
            [di.core :as di]
            [clojure.core.async :as async]))


[[:chapter {:title "Introduction"}]]
"[Apache Storm](http://storm.apache.org) is an open-source distributed realtime computation system.
With Storm, a computation is expressed in terms of a *topology*, 
which consists of *spouts* -- data sources, and *bolts* -- data handlers.
Spouts create *tuples* with data, and bolts process this data and provide output.
Many Storm topologies can run on a single Storm cluster."

"This module provides a service that subcribes to `:axiom/rule-ready` events and deploys topologies based on these rules
to a Storm cluster.
Each such topology is a chain of bolts representing the different links in the rule, fed by spouts which emit facts read from a queue.
A last bolt writes the derived facts to a queue."

[[:chapter {:title "topology"}]]
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

[[:chapter {:title "initial-link-bolt"}]]
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
                              {"l0" (s/bolt-spec {"f0" :shuffle}
                                                 (initial-link-bolt 'storm.core-test/timeline
                                                                    config))})
         result (st/complete-topology cluster topology
                                      :mock-sources
                                      {"f0" [[:fact "test/follows" "alice" ["bob"]
                                              1000 1 #{} #{}]]})]
     (set (st/read-tuples result "l0"))
     => #{[:rule "storm.core-test/timeline!0" "bob" ["alice" "bob"]
           1000 1 #{"storm.core-test"} #{}]})))

[[:chapter {:title "link-bolt"}]]
"The `link-bolt` implements a single (non-initial) link in a rule.
It can receive both `:fact` and `:rule` events, coming from fact spouts and previous link bolts, respectively."

"When an event comes, `link-bolt` feeds it to a [matcher](cloudlog-events.html#matcher) which looks up any 
matching rules or facts in the database.
The matcher then applies the rule to the fact (regardless of which one of those came from the input and which one came from the database),
and emits the results of this application."

"To demonstrate this, we will mock the database using a function that when asked for a rule it returns tuples stating that
both `charlie` and `dave` follow the user in question (regardless of the user),
and when asked for a fact it provides two tweets made by the requested author
We provide this mock in a module function we will provide in the bolt's config map.
The interface we provide is the one required by the [matcher](cloudlog-events.html#matcher), 
and is similar to the one provided for [DynamoDB](dynamo.html#database-chan)."
(fact
 (defn mock-db-module [$]
   (di/provide $ database-chan []
               (let [database-chan (async/chan)]
                 (async/go
                   (loop []
                     (let [[part out-chan] (async/<! database-chan)]
                       (when (= (:kind part) :rule)
                         (async/>! out-chan (merge part {:data ["charlie" (:key part)]
                                                         :ts 1001
                                                         :change 1
                                                         :writers #{"storm.core-test"}
                                                         :readers #{}}))
                         (async/>! out-chan (merge part {:data ["dave" (:key part)]
                                                         :ts 1002
                                                         :change 1
                                                         :writers #{}
                                                         :readers #{}})))
                       (when (= (:kind part) :fact)
                         (async/>! out-chan (merge part {:data [(str (:key part) "'s first tweet")]
                                                         :ts 1003
                                                         :change 1
                                                         :writers #{"storm.core-test"}
                                                         :readers #{}}))
                         (async/>! out-chan (merge part {:data [(str (:key part) "'s second tweet")]
                                                         :ts 1004
                                                         :change 1
                                                         :writers #{}
                                                         :readers #{}})))
                       (async/close! out-chan))
                     (recur)))
                 database-chan))))

"The following topology mocks two spouts that feed a single `l1` bolt.
The `l0` spout provides rule tuples simulating followers (typically provided by [initial-link-bolt](#initial-link-bolt)),
and the `f1` spout providing tweets."
(fact
   (st/with-local-cluster [cluster]
     (let [config {:link-bolt {:include []}
                   :modules ['storm.core-test/mock-db-module]}
           topology (s/topology {"l0" (s/spout-spec (fact-spout "mocked..." config))
                                 "f1" (s/spout-spec (fact-spout "mocked..." config))}
                                {"l1" (s/bolt-spec {"l0" ["key"]
                                                    "f1" ["key"]} (link-bolt 'storm.core-test/timeline 1 config))})
           result (st/complete-topology cluster topology
                                        :mock-sources
                                        {"l0" [[:rule "storm.core-test/timeline!0" "bob" ["alice" "bob"]
                                                1000 1 #{"storm.core-test"} #{}]]
                                         "f1" [[:fact "test/tweeted" "foo" ["hello, world"]
                                                1001 1 #{} #{}]]})]
       (->> (st/read-tuples result "l1")
            (map (fn [[kind name user [tweet] ts change writers readers]]
                   [user tweet ts]))
            set) => #{["alice" "bob's first tweet" 1003]
                      ["alice" "bob's second tweet" 1004]
                      ["charlie" "hello, world" 1001]
                      ["dave" "hello, world" 1001]})))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "injector"}]]
"We use our [dependency inejection mechanism](di.html) to inject external dependencies to topologies.
Since each bolt and spout has its own lifecycle, it makes sense to give each of them its own injector.
The `injector` function takes the `config` parameter each bolt and spout receives and a [keyword representing the bolt or spout](#task-config), 
and returns an initialized injector."

"One challenge to address here is the desire to keep the bolt and spout code independent of the implementations of the different resources.
Generally, this is exactly what DI is supposed to provide.
In the typical case, all libraries are independent of one another, but the *main program* knows about everything, 
and it initializes the injector that spreads all the resources around by calling the `module` functions of the different libraries.
[The migrator integration test](migrator.html#usage-example) is a good example for this pattern."

"Unfortunately, this does not work as well here.
Since each bolt or spout has its own injector, each of them needs to initialize it."

"To address this, the configuration includes a `:modules` entry, consisting of a list of symbols representing different `module`
functions from around the code base.
`injector` evaluates these symbols and calls these functions on the injector it created."

"To demonstrate this we will create our own module function here.
This function defines resource `:bar` that depends on `:foo`"
(fact
 (defn my-module [$]
   (di/provide $ bar [foo]
               (inc foo))))

"Now when we call `injector` with a config for which the `:modules` entry contains our function
and the config for the specific spout we initialize contains `:foo`, we will get `:bar` initialized."
(fact
 (let [config {:spout-x {:include [:foo]}
               :foo 1
               :modules ['storm.core-test/my-module]}
       $ (injector config :spout-x)]
   (di/do-with! $ [bar]
                bar => 2)))

"It only initializes resources based on what is included for *that particular* spout or bolt.
If `:foo` is not included, `:bar` will not be computed."
(fact
 (let [config {:spout-x {:include []}
               :foo 1
               :modules ['storm.core-test/my-module]}
       $ (injector config :spout-x)]
   (di/do-with! $ [bar]) => (throws)))

[[:section {:title "task-config"}]]
"The config map given to [topology](#topology) typically contains all the configuration needed to create all possible resources in Axiom.
While this is fine for injectors that are initalized at startup and shut-down at system shutdown,
this is less than ideal for bolts and spouts, since we wish their startup and shutdown to be as fast as possible."

"`task-config` helps acheive that by creating a config map that is dedicated to a specific bolt or spout.
It takes the original config and a keyword representing the bolt or spout, and returns the targetted config."

"An entry with the name of the bolt or spout is expected to exist in the config map.
It is expected to have an `:include` entry, containing a sequence of keys.
The output map will include these keys (only) with their values in the original config map."
(fact
 (let [config {:foo 1
               :bar 2
               :baz 3
               :bolt-x {:include [:foo :baz]}}]
   (task-config config :bolt-x) => {:foo 1
                                    :baz 3}))

"An optional `:overrides` map will be used to override any values in the config, as well as add new ones."
(fact
 (let [config {:foo 1
               :bar 2
               :baz 3
               :bolt-x {:include [:foo :baz]
                        :overrides {:bar "2"
                                    :baz "3"}}}]
   (task-config config :bolt-x) => {:foo 1
                                    :bar "2"
                                    :baz "3"}))
