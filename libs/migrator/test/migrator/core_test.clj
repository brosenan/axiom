(ns migrator.core-test
  (:require [midje.sweet :refer :all]
            [migrator.core :refer :all]
            [permacode.core :as perm]
            [di.core :as di]
            [clojure.core.async :as async]
            [zk-plan.core :as zkp]
            [zookeeper :as zk]
            [cloudlog.core :as clg]))

[[:chapter {:title "Introduction"}]]
"`migrator` is a microservice that listens to events describing new rules being published,
and initiates data migration using [zk-plan](zk-plan.html)."

"Consider for example the `timeline` clause originally introduced in our [cloudlog documentation](cloudlog.html#joins)."
(clg/defrule timeline [user tweet]
  [:test/follows user author] (clg/by-anyone)
  [:test/tweeted author tweet] (clg/by-anyone))

"If we introduce this rule when `:test/follows` and `:test/tweeted` facts already exist, a migration process is necessary to do the following:
1. Create timeline entries for all the tweets that already have followers.
2. Create intermediate data so that new followers will receive existing tweets in their timelines.
3. Create intermediate data so that new tweets will be added to the timelines of existing followers."

"To allow this to happen, we need to go through all the `:test/follows` facts first and create intermediate rule tuples based on them.
Then we need to go through all the `:test/tweeted` facts and match them against the tuples we generated in the previous step to know
to which timelines each tweet needs to go."

"Going through all the existing facts with a certain name can be a lengthy process.
To speed things up we do the following:
- We use [zk-plan](zk-plan.html) to distribute this work across multiple workers.
- We [scan the facts](dynamo.html#database-scanner) in the database in shards, to allow different computer nodes to process different pieces of data in parallel."

[[:chapter {:title "extract-version-rules"}]]
"`extract-version-rules` is a service function which registers to `:axiom/version` events.
As such it depends on the `serve` function we will mock in order to get hold of the function itself and the registration it is making."
(fact
 (let [reg (async/chan)
       $ (di/injector {:serve (fn [f r]
                                (async/>!! reg [f r]))})]
   (module $)
   (let [[f r] (async/<!! reg)]
     r => {:kind :fact
           :name "axiom/version"}
     (def extract-version-rules f))))

"`:axiom/version` events report on addition or removal of `permacode` module versions.
`extract-version-rules` extracts all the rule functions from such a version and publishes
corresponding `:axiom/rule` events."

"It publishes only rule functions, identified by having a `:source-fact` meta field."
(fact
 (extract-version-rules {:key "perm.1234ABC"} ..pub..) => nil
 (provided
  (perm/module-publics 'perm.1234ABC) => {'foo (with-meta (fn []) {:source-fact ["foo" 1]})
                                          'bar (with-meta (fn []) {:source-fact ["bar" 1]})
                                          'baz (fn [])} ; baz will not be published
  (..pub.. {:name "axiom/rule"
            :key 'perm.1234ABC/foo}) => irrelevant
  (..pub.. {:name "axiom/rule"
            :key 'perm.1234ABC/bar}) => irrelevant))

[[:chapter {:title "rule-tracker"}]]
"`rule-tracker` registers to `:axiom/rule` and tracks the quantity of each rule by summing the `:change` [field of the event](cloudlog-events.html#introduction)."

"It depends on the resources [zookeeper-counter-add](#zookeeper-counter-add) `declare-service` and `assign-service`, which we will mock."
(fact
 (def mock-counters (transient {"/rules/perm.1234ABC.foo" 2}))
 (def calls (async/chan))
 (let [$ (di/injector {:zookeeper-counter-add (fn [path change]
                                                (let [old (mock-counters path 0)
                                                      new (+ old change)]
                                                  (assoc! mock-counters path new)
                                                  new))
                       :declare-service (fn [key reg] (async/>!! calls [:declare-service key reg]))
                       :assign-service (fn [key func] (async/>!! calls [:assign-service key func]))})]
   (module $)
   (let [[call chan] (async/alts!! [calls
                                    (async/timeout 1000)])]
     chan => calls
     call => [:declare-service "migrator.core/rule-tracker" {:kind :fact
                                                             :name "axiom/rule"}])))

"The function `rule-tracker` is the second argument given to `assign-service`."
(fact
 (let [[call chan] (async/alts!! [calls
                                  (async/timeout 1000)])]
   chan => calls
   (take 2 call) => [:assign-service "migrator.core/rule-tracker"]
   (def rule-tracker (call 2))))

"The `rule-tracker` service function is given an `:axiom/rule` event and a `publish` function."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/foo
                :change 3} (fn publish [ev]
                             (throw (Exception. "This should not be called")))) => nil)

"It calls `zookeeper-counter-add` to increment the counter corresponding to the rule."
(fact
 (mock-counters "/rules/perm.1234ABC.foo") => 5)

"If the rule goes from 0 to a positive count, an `:axiom/rule-exists` event with `:change = 1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/bar
                :change 2} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/rule-exists"
            :key 'perm.1234ABC/bar
            :change 1}) => irrelevant))

"This of-course only happens when the change is positive."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/baz
                :change -2} (fn publish [ev]
                             (throw (Exception. "This should not be called")))) => nil)

"If the aggregated value of the rule goes down to 0, an `:axiom/rule-exists` event with `:change = -1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/foo
                :change -5} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/rule-exists"
            :key 'perm.1234ABC/foo
            :change -1}) => irrelevant))

[[:chapter {:title "rule-migrator"}]]
"When the [rule-tracker](#rule-tracker) finds out a rule has been introduced (for the first time), a migration process needs to take place
to process all the existing facts that interact with the new rule, to create all the derived facts and intermediate rule tuples that are the result of this interaction."

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "zookeeper-counter-add"}]]
"`zookeeper-counter-add` depends on the `zookeeper` resource as dependency, and uses it to implement a global atomic counter."
(let [$ (di/injector {:zookeeper :zk})]
  (module $)
  (def zookeeper-counter-add (di/wait-for $ zookeeper-counter-add)))

"`zookeeper-counter-add` takes a path to a counter and a number to be added.
If a node corresponding to the given path does not exist, it is assumed to be equal 0, and is therefore created using the given number.
The new value, which is the given number, is returned."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 3
 (provided
  (zk/exists :zk "/rules/foobar") => nil
  (zk/create :zk "/rules/foobar" :persistent? true) => "/rules/foobar"
  (zkp/set-initial-clj-data :zk "/rules/foobar" 3) => irrelevant))

"If a node exists, its value is updated to add the given number."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 5
 (provided
  (zk/exists :zk "/rules/foobar") => {:some :values
                                      :version 7}
  (zkp/get-clj-data :zk "/rules/foobar") => 2
  (zkp/to-bytes "5") => ..bin..
  (zk/set-data :zk "/rules/foobar" ..bin.. 7) => irrelevant))

"`zookeeper-counter-add` takes an extra `retries` parameter which defaults to 3.
If `zk/set-data` throws an exception for any reason, the update is retried.
This is to account for the possibility of concurrent update."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 6
 (provided
  (zk/exists :zk "/rules/foobar") =streams=> [{:some :values
                                               :version 7}
                                              {:some :values
                                               :version 8}]
  (zkp/get-clj-data :zk "/rules/foobar") =streams=> [2 3]
  (zkp/to-bytes "5") => ..bin1..
  (zk/set-data :zk "/rules/foobar" ..bin1.. 7) =throws=> (Exception. "boo")
  (zkp/to-bytes "6") => ..bin2..
  (zk/set-data :zk "/rules/foobar" ..bin2.. 8) => irrelevant))

"When the retries are exhasted, the function throws."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => (throws "boo")
 (provided
  (zk/exists :zk "/rules/foobar") =streams=> [{:some :values
                                               :version 7}
                                              {:some :values
                                               :version 8}
                                              {:some :values
                                               :version 9}]
  (zkp/get-clj-data :zk "/rules/foobar") =streams=> [2 3 4]
  (zkp/to-bytes irrelevant) => irrelevant
  (zk/set-data :zk "/rules/foobar" irrelevant irrelevant) =throws=> (Exception. "boo")))

[[:section {:title "fact-declarer"}]]
"`fact-declarer` is used to declare the service that will accept events related to a certain fact.
We call it before we start migration based on that fact so that new events related to this fact that come
during the migration process are accumulated in the queue.
Once the migration is done, [functions assigned to it](rabbit-microservices.html#assign-service) will receive
these events as well as new ones, so there will not be any data loss."

"`fact-declarer` is a generator function that takes the name of the rule and the link number (as a unique identifier)
and the name of the fact to be declared."
(fact
 (def decl-my-fact (fact-declarer "my-rule" 2 "my-fact")))

"The returned fact declarer is intended to be used in a [zk-plan](zk-plan.html), as a [task](zk-plan.html#add-task) function.
As such, it needs to accept one or more arguments.
It ignores all but the first one, which is an injector (`$`) passed to it directly by `zk-plan`."

"The injector must be able to resolve the resource `declare-service` (e.g., [the one implemented for RabbitMQ](rabbit-microservices.html#declare-service)).
We will mock it here."
(fact
 (let [calls (async/chan 10)
       $ (di/injector {:declare-service (fn [key reg] (async/>!! calls [:declare-service key reg]))})]
   (decl-my-fact $ :some :args :that :are :ignored)
   ;; Assert the calls
   (async/alts!! [calls
                  (async/timeout 1000)]) => [[:declare-service "fact-for-rule/my-rule!2" {:kind :fact
                                                                                          :name "my-fact"}] calls]))

[[:section {:title "initial-migrator"}]]
"`initial-migrator` creates a migration function for link 0 of a rule.
Link 0 is special in that it only depends on a fact, creates rule tuples based on fact tuples.
Other links also depend on previous rule tuples."

"`initial-migrator` takes the name of a rule, the name of its source fact and the rule's writer-set
as well as a shard number and the total number of shards being used.
It returns a closure (function) that operates from within a [zk-plan](zk-plan.html)."
(def my-migrator (initial-migrator "perm.ABC/my-rule" "test/follows" #{:some-writer} 2 6))

"The migration process requires a `database-scanner` (e.g., [this](dynamo.html#database-scanner)) to scan given shard of the given table (fact).
We mock this function by providing `:test/follows` facts for Alice, who follows Bob, Charlie and Dave. "
(defn mock-scanner [name shard shards chan]
  (when-not (= [name shard shards] ["test/follows" 2 6])
    (throw (Exception. (str "Unexpected args in mock-scanner: " [name shard shards]))))
  (doseq [followee ["bob" "charlie" "dave"]]
    (async/>!! chan {:kind :fact
                     :name name
                     :key "alice"
                     :data [followee]
                     :change 1}))
  (async/close! chan))

"To store the resulting tuples it depends on a `database-event-storage-chan` (e.g., [this](dynamo.html#database-event-storage-chan)),
a channel to which all generated events need to be sent."
(def rule-tuple-chan (async/chan 10))

"As a `zk-plan` task function, the first argument of the returned closure (`my-migrator` in our case), is expected to be an injector (`$`),
and all other arguments should be ignored.
Once called, it will use [permacode.core/eval-symbol](permacode.html#eval-symbol) to get the rule function.
It will evaluate this function on each result comming from the `database-scanner`."
(fact
 (let [$ (di/injector {:database-scanner mock-scanner
                       :database-event-storage-chan rule-tuple-chan})]
   (my-migrator $ :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC/my-rule) => timeline)))

"The migrator function should push to `database-event-storage-chan` rule events.
In our case, these should be one per each fact."
(fact
 (async/alts!! [rule-tuple-chan
                (async/timeout 1000)]) => [{:change 1
                                            :data ["alice" "bob"]
                                            :key "bob"
                                            :kind :rule
                                            :name "migrator.core-test/timeline!0"
                                            :readers nil
                                            :writers #{:some-writer}} rule-tuple-chan]
  (async/alts!! [rule-tuple-chan
                (async/timeout 1000)]) => [{:change 1
                                            :data ["alice" "charlie"]
                                            :key "charlie"
                                            :kind :rule
                                            :name "migrator.core-test/timeline!0"
                                            :readers nil
                                            :writers #{:some-writer}} rule-tuple-chan]
    (async/alts!! [rule-tuple-chan
                (async/timeout 1000)]) => [{:change 1
                                            :data ["alice" "dave"]
                                            :key "dave"
                                            :kind :rule
                                            :name "migrator.core-test/timeline!0"
                                            :readers nil
                                            :writers #{:some-writer}} rule-tuple-chan])
