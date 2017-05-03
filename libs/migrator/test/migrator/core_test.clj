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
(clg/defrule timeline [user author tweet]
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
            :key 'perm.1234ABC/foo
            :data []}) => irrelevant
  (..pub.. {:name "axiom/rule"
            :key 'perm.1234ABC/bar
            :data []}) => irrelevant))

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
                :data []
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
                :data []
                :change 2} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/rule-exists"
            :key 'perm.1234ABC/bar
            :data []
            :change 1}) => irrelevant))

"This of-course only happens when the change is positive."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/baz
                :data []
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
            :data []
            :change -1}) => irrelevant))

[[:chapter {:title "rule-migrator"}]]
"When the [rule-tracker](#rule-tracker) finds out a rule has been introduced (for the first time), a migration process needs to take place
to process all the existing facts that interact with the new rule, to create all the derived facts and intermediate rule tuples that are the result of this interaction."

"`rule-migrator` is the service function responsible for this.
It depends on [zk-plan](zk-plan.html#module), which it uses to create the migration plan.
We will mock this module with functions that record their own operation, so that we will later be able to view the plan that was created."
(fact
 (def calls (transient []))
 (def last-task (atom 0))
 (def mock-zk-plan
   {:create-plan (fn [parent]
                   (conj! calls [:create-plan parent])
                   :plan-node)
    :add-task (fn [plan func args]
                (conj! calls [:add-task plan func args])
                (swap! last-task inc)
                @last-task)
    :mark-as-ready (fn [node]
                     (conj! calls [:mark-as-ready node]))}))

"With this mock, `calls` will contain a list of the calls that were made.
`create-plan` always returns `:plan-node`, and `add-task` returns an ordinal number."

"Since `rule-migrator` is a service, we mock `declare-service` and `assign-service` to get the actual function.
We also provides it a `migration-config` resource containing the `:number-of-shards` parameter, determining how much parallelism we wish to have."
(fact
 (let [calls-chan (async/chan 10)
       $ (di/injector {:declare-service (fn [key reg] (async/>!! calls-chan [:declare-service key reg]))
                       :assign-service (fn [key func] (async/>!! calls-chan [:assign-service key func]))
                       :zk-plan mock-zk-plan
                       :migration-config {:number-of-shards 3
                                          :plan-prefix "/my-plans"}})]
   (module $)
   (async/alts!! [calls-chan
                  (async/timeout 1000)]) => [[:declare-service "migrator.core/rule-migrator" {:kind :fact
                                                                                              :name "axiom/rule-exists"}] calls-chan]
   (let [[call ch] (async/alts!! [calls-chan
                                  (async/timeout 1000)])]
     ch => calls-chan
     (take 2 call) => [:assign-service "migrator.core/rule-migrator"]
     (def migrate-rule (call 2)))))

"Now, if we call the migration function `migrate-rule` on a rule, it will create a plan for migrating it.
The plan will include singleton [fact-declarer](#fact-declarer) tasks that will start collecting events to be processed by the given rule
once the migration is complete; a first phase of [initial-migrators](#initial-migrator) to process link 0 of the rule
and any number of [link-migrator](#link-migrator) phases to process the rest of the links."
(fact
 (migrate-rule {:kind :fact
                :name "axiom/rule-exists"
                :key 'perm.1234ABC/timeline
                :date []
                :writers #{:some-writers}
                :change 1}) => nil
 (provided
  (perm/eval-symbol 'perm.1234ABC/timeline) => timeline)
 (persistent! calls)
 => [[:create-plan "/my-plans"]
     [:add-task :plan-node `(fact-declarer perm.1234ABC/timeline 0) []] ;; => 1
     [:add-task :plan-node `(initial-migrator perm.1234ABC/timeline #{:some-writers} 0 3) [1]] ;; => 2
     [:add-task :plan-node `(initial-migrator perm.1234ABC/timeline #{:some-writers} 1 3) [1]] ;; => 3
     [:add-task :plan-node `(initial-migrator perm.1234ABC/timeline #{:some-writers} 2 3) [1]] ;; => 4
     [:add-task :plan-node `(fact-declarer perm.1234ABC/timeline 1) [2 3 4]] ;; => 5
     [:add-task :plan-node `(link-migrator perm.1234ABC/timeline 1 #{:some-writers} 0 3) [5]] ;; => 6
     [:add-task :plan-node `(link-migrator perm.1234ABC/timeline 1 #{:some-writers} 1 3) [5]] ;; => 7
     [:add-task :plan-node `(link-migrator perm.1234ABC/timeline 1 #{:some-writers} 2 3) [5]] ;; => 8
     [:add-task :plan-node `(migration-end-notifier perm.1234ABC/timeline #{:some-writers}) [6 7 8]] ;; => 9
     [:mark-as-ready :plan-node]])

[[:chapter {:title "Usage Example"}]]
"In this example we will migrate the rules provided in the associated example"

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

"`fact-declarer` is a generator function that takes the name of the rule and the link number (as a unique identifier)"
(fact
 (def decl-my-fact (fact-declarer 'perm.ABC123/my-rule 2)))

"The returned fact declarer is intended to be used in a [zk-plan](zk-plan.html), as a [task](zk-plan.html#add-task) function.
As such, it needs to accept one or more arguments.
It ignores all but the first one, which is an injector (`$`) passed to it directly by `zk-plan`."

"The injector must be able to resolve the resource `declare-service` (e.g., [the one implemented for RabbitMQ](rabbit-microservices.html#declare-service)).
We will mock it here."
(fact
 (let [calls (async/chan 10)
       $ (di/injector {:declare-service (fn [key reg] (async/>!! calls [:declare-service key reg]))})]
   (decl-my-fact $ :some :args :that :are :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC123/my-rule) => timeline)
   ;; Assert the calls
   (async/alts!! [calls
                  (async/timeout 1000)]) => [[:declare-service "fact-for-rule/perm.ABC123/my-rule!2" {:kind :fact
                                                                                          :name "test/follows"}] calls]))

[[:section {:title "initial-migrator"}]]
"`initial-migrator` creates a migration function for link 0 of a rule.
Link 0 is special in that it only depends on a fact, creates rule tuples based on fact tuples.
Other links also depend on previous rule tuples."

"`initial-migrator` takes the following arguments:
1. `rule`: The name of a rule (as a symbol).
2. `writers`: The rule's writer-set
3. `shard`: The shard number
4. `shards`: The total number of shards being used.
It returns a closure (function) that operates from within a [zk-plan](zk-plan.html)."
(def my-migrator (initial-migrator 'perm.ABC/my-rule #{:some-writer} 2 6))

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

[[:section {:title "link-migrator"}]]
"For links other than 0, migration requires applying a [matcher](cloudlog-events.html#matcher).
A matcher takes one event (a fact event in our case), and matches it against all matching events
(rule events in our case).
We need to provide a `database-chan` to allow the matcher to cross reference facts and rules."

"The `link-migrator` function takes the following arguments:
1. `rule`: The rule to be migrated.
2. `link`: The link number within the rule (> 0).
3. `writers`: The rule's writer set.
4. `shard`: The shard number to be processed by this node.
5. `shards`: The overall number of shards.
It returns a closure to be processed as a `zk-plan` task."
(def my-link-migrator (link-migrator 'perm.ABC/my-rule 1 #{:my-writers} 3 17))

"For the migration process we will need a `database-scanner` that will provide fact events.
We will mock one to produce `:test/tweeted` facts, stating that Bob, Charlie and Dave all tweeted 'hello'."
(defn mock-scanner [name shard shards chan]
  (when-not (= [name shard shards] ["test/tweeted" 3 17])
    (throw (Exception. (str "Unexpected args in mock-scanner: " [name shard shards]))))
  (doseq [user ["bob" "charlie" "dave"]]
    (async/>!! chan {:kind :fact
                     :name name
                     :key user
                     :data ["hello"]
                     :change 1}))
  (async/close! chan))

"We also need to provide a `database-chan` (e.g., [this](dynamo.html#database-chan)), which in our case,
will answer that regardless of who was making the tweet, both Alice and Eve are followers.
These events are similar to the ones we got from the [initial-migrator](#initial-migrator)."
(fact
 (def mock-db-chan (async/chan 10))
 (async/go
   (loop []
     (let [[query out-chan] (async/<! mock-db-chan)]
       (when-not (nil? query)
         ;; The query is for rule tuples of link 0
         (when-not (= (:name query) "migrator.core-test/timeline!0")
           (throw (Exception. (str "Wrong fact in query: " (:name query)))))
         (async/>! out-chan {:change 1
                             :data ["alice" (:key query)]
                             :key (:key query)
                             :kind :rule
                             :name "migrator.core-test/timeline!0"
                             :readers nil
                             :writers #{:some-writer}})
         (async/>! out-chan {:change 1
                             :data ["eve" (:key query)]
                             :key (:key query)
                             :kind :rule
                             :name "migrator.core-test/timeline!0"
                             :readers nil
                             :writers #{:some-writer}})
         (async/close! out-chan)
         (recur))))))

"Once more, we will need a `database-event-storage-chan` (e.g., [this](dynamo.html#database-event-storage-chan))
to store the events we get."
(def mock-store-chan (async/chan 10))

"The closure we got from `link-migrator` (`my-link-migrator` in our case) takes an injector as a first argument, and ignores all others.
It calls [permacode.core/eval-symbol](permacode.html#eval-symbol) to get the rule and then creates a [matcher](cloudlog-events.html#matcher)
based on it."
(fact
 (let [$ (di/injector {:database-scanner mock-scanner
                       :database-chan mock-db-chan
                       :database-event-storage-chan mock-store-chan})]
   (my-link-migrator $ :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC/my-rule) => timeline)))

"The migration function reads all fact events from the scanner, 
for each event it consults the `database-chan` for matching rule tuples, and the resulting events (timeline facts in our case)
are pushed to the `database-event-storage-chan`."
(fact
 ;; For Bob
 (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "alice"
                                            :data ["bob" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan]
  (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "eve"
                                            :data ["bob" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan]
   (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "alice"
                                            :data ["charlie" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan]
  (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "eve"
                                            :data ["charlie" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan]
   (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "alice"
                                            :data ["dave" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan]
  (async/alts!! [mock-store-chan
                (async/timeout 1000)]) => [{:kind :fact
                                            :name "migrator.core-test/timeline"
                                            :key "eve"
                                            :data ["dave" "hello"]
                                            :change 1
                                            :readers nil
                                            :writers #{:some-writer}} mock-store-chan])

"For good citizenship, let's close the mock `database-chan` and allow the service we started shut down."
(fact
 (async/close! mock-db-chan))

[[:section {:title "migration-end-notifier"}]]
"[rule-migrator](#rule-migrator) finishes when a plan is created and is ready to be executed.
However, the actual migration operation only starts at that point.
We therefore need a way to tell other parts of *axiom* that the rule has been migrated,
once migration is complete.
For this purpose, the plan will include a final piece: `migration-end-notifier`, which
will [publish](rabbit-microservices.html#publish) a `axiom/rule-ready` event holding the
rule identifier as its `:key`."
(fact
 (def my-end-notifier (migration-end-notifier 'perm.ABC123/my-rule #{:some-writer})))

"The returned closure (`my-end-notifier`) takes an injector and any number of other parameters.
From the injector it takes the `publish` resource to publish the desired event."
(fact
 (def events (transient []))
 (let [$ (di/injector {:publish (fn [ev] (conj! events ev))})]
   (my-end-notifier $ :some :other :params)))

"Once called it should `publish` an `axiom/rule-ready` event."
(fact
 (persistent! events) => [{:kind :fact
                           :name "axiom/rule-ready"
                           :key 'perm.ABC123/my-rule
                           :data []
                           :change 1
                           :writers #{:some-writer}}])
