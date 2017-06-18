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

"The corresponding topology will have [two spouts and four bolts](http://storm.apache.org/releases/1.1.0/Concepts.html).
The two [fact-spout](#fact-spout)s introduce new facts to this rule,
two bolts -- an [initial-link-bolt](#initial-link-bolt) and a [link-bolt](#link-bolt) 
correspond to the two links that process these facts,
and two output bolts -- a [store-bolt](#store-bolt) stores intermediate tuples 
and an [output-bolt](#output-bolt) outputs `timeline` entries.
All bolts and spouts take the `config` parameter as their last parameter."
(fact
 (topology 'perm.ABCD1234/timeline ..config..) => ..topology..
 (provided
  ;; We extract the actual function based on the symbol
  (perm/eval-symbol 'perm.ABCD1234/timeline) => timeline
  ;; Then we define the two spouts based on the fact streams
  (fact-spout "fact-for-rule/perm.ABCD1234/timeline!0" ..config..) => ..spout0..
  (s/spout-spec ..spout0..) => ..spoutspec0..
  (fact-spout "fact-for-rule/perm.ABCD1234/timeline!1" ..config..) => ..spout1..
  (s/spout-spec ..spout1..) => ..spoutspec1..
  ;; An initial link bolt based on the initial fact
  (initial-link-bolt 'perm.ABCD1234/timeline ..config..) => ..bolt0..
  (s/bolt-spec {"f0" ["key"]} ..bolt0..) => ..boltspec0..
  ;; A store-bolt that stores the tuples coming from the initial link
  (store-bolt ..config..) => ..outbolt0..
  (s/bolt-spec {"l0" :shuffle} ..outbolt0..) => ..outboltspec0..
  ;; and a regular link based on both the second fact and the initial link.
  (link-bolt 'perm.ABCD1234/timeline 1 ..config..) => ..bolt1..
  (s/bolt-spec {"f1" ["key"]
                "l0" ["key"]} ..bolt1..) => ..boltspec1..
  ;; Finally, we add the output bolt
  (output-bolt ..config..) => ..outbolt1..
  (s/bolt-spec {"l1" :shuffle} ..outbolt1..) => ..outboltspec1..
  ;; and create the complete topology
  (s/topology {"f0" ..spoutspec0..
               "f1" ..spoutspec1..}
              {"l0" ..boltspec0..
               "o0" ..outboltspec0..
               "l1" ..boltspec1..
               "o1" ..outboltspec1..}) => ..topology..))

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
 :integ
 (st/with-local-cluster [cluster]
   (let [config {:initlal-link-bolt {:include [:hasher]}
                 :hasher [nil nil]}
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
 :integ
 (defn mock-db-module [$]
   (di/provide $ database-chan [foo]
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
 :integ
 (st/with-local-cluster [cluster]
   (let [config {:link-bolt {:include [:foo :hasher]}
                 :modules ['storm.core-test/mock-db-module]
                 :foo 1
                 :hasher [nil nil]}
         topology (s/topology
                   {"l0" (s/spout-spec (fact-spout "mocked..." config))
                    "f1" (s/spout-spec (fact-spout "mocked..." config))}
                   {"l1" (s/bolt-spec {"l0" ["key"]
                                       "f1" ["key"]} (link-bolt 'storm.core-test/timeline 1 config))})
         result (st/complete-topology
                 cluster topology
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

[[:chapter {:title "store-bolt"}]]
"The `store-bolt` uses a `database-event-storage-chan` (e.g., [the one for DynamoDB](dynamo.html#database-event-storage-chan))
to store each event it receives to a database."

"To demonstrate how it works we will mock a database that stores all the received events into into an `atom`."
(fact
 :integ
 (def stored-events (atom [])))

"We mock a module function to provide our mock `database-event-storage-chan`.
It takes tuples `[event ack]`, where `event` is an event (map) to be stored 
and `ack` is a channel to be closed once the event is stored."
(fact
 :integ
 (defn mock-db-storage-module [$]
   (di/provide $ database-event-storage-chan [foo]
               (let [database-event-storage-chan (async/chan)]
                 (async/go
                   (loop []
                     (let [[event ack] (async/<! database-event-storage-chan)]
                       (swap! stored-events #(conj % event))
                       (async/close! ack))
                     (recur)))
                 database-event-storage-chan))))

"We now build a topology consisting of a spout that emits events and this bolt, and expect that all events that were emitted
be stored in the sequence mocking the database once the topology completes."
(fact
 :integ
 (st/with-local-cluster [cluster]
   (let [config {:store-bolt {:include [:foo]}
                 :modules ['storm.core-test/mock-db-storage-module]
                 :foo 1}
         topology (s/topology
                   {"src" (s/spout-spec (fact-spout "mocked..." config))}
                   {"out" (s/bolt-spec {"src" :shuffle} (store-bolt config))})
         result (st/complete-topology
                 cluster topology
                 :mock-sources
                 {"src" [[:fact "test/tweeted" "foo" ["hello, world"]
                          1001 1 #{} #{}]
                         [:rule "storm.core-test/timeline!0" "bob" ["alice" "bob"]
                          1000 1 #{"storm.core-test"} #{}]]})]))
 (set @stored-events) => #{{:kind :fact :name "test/tweeted"
                            :key "foo" :data ["hello, world"]
                            :ts 1001 :change 1
                            :writers #{} :readers #{}}
                           {:kind :rule :name "storm.core-test/timeline!0"
                            :key "bob" :data ["alice" "bob"]
                            :ts 1000 :change 1
                            :writers #{"storm.core-test"} :readers #{}}})

[[:chapter {:title "output-bolt"}]]
"The `output-bolt` `publish`es (e.g., [through AMQP](rabbit-microservices.html#publish)) each event it receives."

"To demonstrate how it works we will mock the `publish` function.
Our mock will add each published event to the `published-events` atom."
(fact
 :integ
 (def published-events (atom [])))

"The following mock module provides our mock `publish`."
(fact
 :integ
 (defn mock-publish-module [$]
   (di/provide $ publish [foo]
               (fn [ev]
                 (swap! published-events #(conj % ev))))))

"We now build a topology consisting of a spout that emits events and this bolt, and expect that all events that were emitted
be stored in the sequence mocking the database once the topology completes."
(fact
 :integ
 (st/with-local-cluster [cluster]
   (let [config {:output-bolt {:include [:foo]}
                 :modules ['storm.core-test/mock-publish-module]
                 :foo 1}
         topology (s/topology
                   {"src" (s/spout-spec (fact-spout "mocked..." config))}
                   {"out" (s/bolt-spec {"src" :shuffle} (output-bolt config))})
         result (st/complete-topology
                 cluster topology
                 :mock-sources
                 {"src" [[:fact "test/tweeted" "foo" ["hello, world"]
                          1001 1 #{} #{}]
                         [:rule "storm.core-test/timeline!0" "bob" ["alice" "bob"]
                          1000 1 #{"storm.core-test"} #{}]]})]))
 (set @published-events) => #{{:kind :fact :name "test/tweeted"
                               :key "foo" :data ["hello, world"]
                               :ts 1001 :change 1
                               :writers #{} :readers #{}}
                              {:kind :rule :name "storm.core-test/timeline!0"
                               :key "bob" :data ["alice" "bob"]
                               :ts 1000 :change 1
                               :writers #{"storm.core-test"} :readers #{}}})

[[:chapter {:title "fact-spout"}]]
"The `fact-spout` registers to a certain fact feed, and emits all the events it receives from there.
It acknowledges received facts once their processing is complete."

"We will demonstrate how it operates by creating a topology consising of one `fact-spout` and one [output-bolt](#output-bolt).
We will mock the `assign-service` method used by the `fact-spout` to read events off an `async/chan`,
and will mock the `output-bolt`'s `publish` method to write to another `async/chan`. 
Then we will write events one by one to the first channel, and see them coming on the other end.
We will provide the `fact-spout` an `ack` method to acknowledge incoming events, which counts the times it is being called in an atom."
(fact
 :integ
 (def ack-counter (atom 0))
 (def to-chan (async/chan))
 (def from-chan (atom (async/chan)))
 (defn fact-spout-mock-module [$]
   (di/provide $ publish []
               (fn [ev]
                 (async/>!! to-chan ev)))
   (di/provide $ assign-service [foo]
               (let [threads (atom #{})]
                 {:resource (fn [q func]
                              (when-not (= q "the-queue-for-this-spout")
                                (throw (Exception. (str "Wrong queue: " q))))
                              (let [thread
                                    (async/thread
                                      (loop []
                                        (let [ack (fn [] (swap! ack-counter inc))
                                              event (async/<!! @from-chan)]
                                          (when event
                                            (func event nil ack)
                                            (recur)))))]
                                (swap! threads conj thread)))
                  :shutdown (fn []
                              (async/close! @from-chan)
                              (doseq [thread @threads]
                                (async/<!! thread)))}))))

"We wish to see that after we post and consume all events (which need to be the same events), all events are acknowledged."
(fact
 :integ
 (let [event (fn [u1 u2] {:kind :fact
                          :name "test/follows"
                          :key u1
                          :data [u2]
                          :ts 1000
                          :change 1
                          :writers #{u1}
                          :readers #{}})
       events [(event "alice" "bob")
               (event "alice" "charline")
               (event "bob" "dave")
               (event "charline" "dave")]
       config {:modules ['storm.core-test/fact-spout-mock-module]
               :fact-spout {:include [:foo]}
               :output-bolt{:include []}
               :foo 1}
       topology (s/topology {"src" (s/spout-spec (fact-spout "the-queue-for-this-spout" config))}
                            {"out" (s/bolt-spec {"src" :shuffle}
                                                (output-bolt config))})]
   (def fact-spout-topology topology)
   (def fact-spout-config config)
   (st/with-local-cluster [cluster]
     (st/submit-local-topology (:nimbus cluster)
                               "test-topology"
                               {}
                               topology)
     (doseq [event events]
       (async/>!! @from-chan event))
     (let [result (transient [])]
       (doseq [_ (range (count events))]
         (conj! result (async/<!! to-chan)))
       (persistent! result) => events))
   @ack-counter => (count events)))

[[:chapter {:title "rule-topology"}]]
"`rule-topology` is a [microservice](rabbit-microservices.html#serve) that subscribes to `axiom/rule-ready` events, and brings topologies up and down accordingly."

"Unlike most `module` functions, the `storm.core/module` function takes an extra `config` parameter, intended to be the original configuration
that was used to create original injector.
It defines `rule-topology` based on these resources:
- `declare-service` and `assign-service`, to register itself to `axiom/rule-ready` events,
- `storm-cluster`, a storm cluster to load the topology on,
- `hasher`, to resolve [permacode](permacode.html) symbols (the rule name is one such symbol)."
(fact
 (def running-topologies (atom {}))
 (let [decl (transient {})
       assign (transient {})
       config {:declare-service (fn [name partial]
                                  (assoc! decl name partial))
               :assign-service (fn [name func]
                                 (assoc! assign name func))
               :storm-cluster {:run (fn [name top]
                                      (swap! running-topologies assoc name top))
                               :kill (fn [name]
                                       (swap! running-topologies dissoc name))}
               :hasher [:hash :unhash]}
       $ (di/injector config)]
   (def config config)
   (module $ config)
   (di/startup $)
   ((persistent! decl) "storm.core/rule-topology") => {:kind :fact
                                                       :name "axiom/rule-ready"}
   (let [assign (persistent! assign)]
     (assign "storm.core/rule-topology") => fn?
     (def rule-topology (assign "storm.core/rule-topology")))))

"When an `axiom/rule-ready` event with a positive `:change` (introduction of a rule) arrives, 
`rule-topology` calls [topology](#topology) to create a topology for the rule, 
and then assigns it to the `storm-cluster`.
The topology name is converted to avoid names not allowed by Storm."
(fact
 (rule-topology {:kind :fact
                 :name "axiom/rule-ready"
                 :key 'perm.ABCD1234/timeline
                 :data []
                 :ts 1000
                 :change 1
                 :writers #{}
                 :readers #{}}) => nil
 (provided
  (topology 'perm.ABCD1234/timeline config) => ..topology..
  (convert-topology-name "perm.ABCD1234/timeline") => "some-name")
 (@running-topologies "some-name") => ..topology..)

"When an `axiom/rule-ready` event with a *negative* `:change` arrives,
we kill the associated topology."
(fact
 (rule-topology {:kind :fact
                 :name "axiom/rule-ready"
                 :key 'perm.ABCD1234/timeline
                 :data []
                 :ts 1000
                 :change -1
                 :writers #{}
                 :readers #{}}) => nil
 (provided
  (convert-topology-name "perm.ABCD1234/timeline") => "some-name")
 (@running-topologies "some-name") => nil)

[[:chapter {:title "storm-cluster"}]]
"A `storm-cluster` resource represents an [Apache Storm](http://storm.apache.org) cluster.
It is a map with two fields:
- `:run` -- a function that takes a name and a topology, and deploys the topology on the cluster with the given name, and
- `:kill` -- a function that stops a topology and removes it from the cluster."

[[:section {:title "Local Cluster"}]]
"A local cluster is created if a resource named `local-storm-cluster` exists.
The value of `local-storm-cluster` does not matter because a local cluster has no configuration."

"To demonstrate our local cluster we will create one, and then deploy a simple topology to it, 
the same one we used for [fact-spou](t#fact-spout)."
(fact
 :integ
 (reset! from-chan (async/chan))
 (let [$ (di/injector {:local-storm-cluster true
                       :modules ['storm.core-test/fact-spout-mock-module]})]
   (module $ fact-spout-config)
   (di/startup $)
   (di/do-with! $ [storm-cluster]
                (let [{:keys [run kill]} storm-cluster
                      event {:kind :fact
                              :name "test/follows"
                              :key "alice"
                              :data ["bob"]
                              :ts 1000
                              :change 1
                              :writers #{"alice"}
                              :readers #{}}]
                  (run "my-topology" fact-spout-topology)
                  (async/>!! @from-chan event)
                  (async/alts!! [to-chan
                                 (async/timeout 4000)]) => [event to-chan])
                (di/shutdown $))))

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

[[:section {:title "convert-topology-name"}]]
"Storm restricts topology names from containing '.', '/', '\\' or ':'.
`convert-topology-name` replaces these characters with legal ones (namely, '-' and '_')."

"Strings that do not contain these characters are returned unchanged."
(fact
 (convert-topology-name "this name is legal") => "this name is legal")

"'.' is replaced with '-'.
The other illegal characters are replaced with '_'."
(fact
 (convert-topology-name "./\\:") => "-___")
