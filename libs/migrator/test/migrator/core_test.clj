(ns migrator.core-test
  (:require [midje.sweet :refer :all]
            [migrator.core :refer :all]
            [permacode.core :as perm]
            [permacode.publish :as permpub]
            [permacode.validate :as permval]
            [di.core :as di]
            [clojure.core.async :as async]
            [zk-plan.core :as zkp]
            [zookeeper :as zk]
            [cloudlog.core :as clg]
            [rabbit-microservices.core :as rms]
            [zk-plan.core :as zkp]
            [dynamo.core :as dyn]
            [zookeeper :as zk]
            [clojure.java.io :as io]
            [s3.core :as s3]))

[[:chapter {:title "Introduction"}]]
"`migrator` is a microservice that listens to events describing new rules being published,
and initiates data migration using [zk-plan](zk-plan.html)."

"Consider for example the following two clause originally introduced in our [cloudlog documentation](cloudlog.html)."
(clg/defrule timeline [user author tweet]
  [:test/follows user author] (clg/by-anyone)
  [:test/tweeted author tweet] (clg/by-anyone))

(clg/defrule trending [tweet]
  [:test/influencer influencer] (clg/by-anyone)
  [timeline influencer tweet] (clg/by-anyone))

"If we introduce these rules when `:test/follows`, `:test/tweeted` and `:test/influencer` facts already exist, a migration process is necessary to do the following:
1. Create timeline entries for all the tweets that already have followers.
2. Create trending entries based on newly-calculated timeline entries for all existing influencers.
3. Create intermediate data so that new followers will receive existing tweets in their timelines.
4. Create intermediate data so that new tweets will be added to the timelines of existing followers.
5. Create intermediate data so that new timeline entries of existing influencers will be taken into account for trending."

"To allow this to happen, we need to go through all the `:test/follows` facts first and create intermediate rule tuples based on them.
Then we need to go through all the `:test/tweeted` facts and match them against the tuples we generated in the previous step to know
to which timelines each tweet needs to go.
Then, after all timeline entries are calculated we can go through all influencers and create trending data."

"Going through all the existing facts with a certain name can be a lengthy process.
To speed things up we do the following:
- We use [zk-plan](zk-plan.html) to distribute this work across multiple workers.
- We [scan the facts](dynamo.html#database-scanner) in the database in shards, to allow different computer nodes to process different pieces of data in parallel."

[[:chapter {:title "push-handler"}]]
"When developers push a new version of their code, they publish an `:axiom/app-version` event stating the clone URL and git commit hash for that new version.
The origin of such operation could be a handler for a [GitHub webhook](#https://developer.github.com/webhooks/) or something similar."

"This service uses [git](https://git-scm.com) to clone the specified version into the local file system.
It relies on the following resources:
1. `sh`: A shell to run `git` commands in.
2. `migration-config`: where the `:clone-location` -- the directory where all clones are performed, is specified, and the `:clone-depth`.
3. `hasher`: to be used for storing the versions loaded from git.
3. `serve`: which registers this function with the `:axiom/app-version` event."
(fact
 (def cmds (transient []))
 (let [reg (transient {})
       $ (di/injector {:sh (fn [& args]
                             (conj! cmds args)
                             {:exit 0
                              :out "some output"
                              :err ""})
                       :migration-config {:clone-location "/my/clone/location"
                                          :clone-depth 12}
                       :hasher :my-hasher
                       :serve (fn [f r]
                                (assoc! reg r f))})]
   (module $)
   (di/startup $)
   (def push-handler ((persistent! reg) {:kind :fact
                                         :name "axiom/app-version"})))
 push-handler => fn?)

"The `push-handler` function responds to such events by calling `git clone` and `git checkout` 
to get the specified version of the specified repo inside the given directory.
Then `permacode.publish/hash-all` is called on the local repo, and then the directory is removed."
(fact
 (def published (transient []))
 (push-handler {:kind :fact
                :name "axiom/app-version"
                :key "https://example.com/some/repo"
                :data ["ABCD1234"]}
               (fn [ev]
                 (conj! published ev))) => nil
 (provided
  (rand-int 1000000000) => 12345
  (io/file "/my/clone/location/repo12345/src") => ..dir..
  (permpub/hash-all :my-hasher ..dir..) => {'foo 'perm.ABCD123
                                            'bar 'perm.EFGH456})
 (persistent! cmds) => [["git" "clone" "--depth" "12" "https://example.com/some/repo" "/my/clone/location/repo12345"]
                        ["git" "checkout" "ABCD1234" :dir "/my/clone/location/repo12345"]
                        ["rm" "-rf" "/my/clone/location/repo12345"]])

"The handler publishes an `:axiom/perm-versions` event."
(fact
 (persistent! published) => [{:kind :fact
                              :name "axiom/perm-versions"
                              :key "https://example.com/some/repo"
                              :data ["ABCD1234" #{'perm.ABCD123 'perm.EFGH456}]}])

[[:chapter {:title "perm-tracker"}]]
"`perm-tracker` registers to `:axiom/perm-versions` and tracks the quantity of each [permacode module](permacode.html) by summing the `:change`
[field of the event](cloudlog-events.html#introduction)."

"It depends on the resources [zookeeper-counter-add](#zookeeper-counter-add) `declare-service` and `assign-service`, which we will mock."
(fact
 (def mock-counters (transient {"/perms/perm.ABCD123" 2}))
 (def calls (transient []))
 (let [$ (di/injector {:zookeeper-counter-add (fn [path change]
                                                (let [old (mock-counters path 0)
                                                      new (+ old change)]
                                                  (assoc! mock-counters path new)
                                                  new))
                       :declare-service (fn [key reg] (conj! calls [:declare-service key reg]))
                       :assign-service (fn [key func] (conj! calls [:assign-service key func]))})]
   (module $)
   (di/startup $)
   (def calls (persistent! calls))
   (first calls) => [:declare-service "migrator.core/perm-tracker" {:kind :fact
                                                                    :name "axiom/perm-versions"}]))

"The function `rule-tracker` is the second argument given to `assign-service`."
(fact
 (let [call (second calls)]
   (take 2 call) => [:assign-service "migrator.core/perm-tracker"]
   (def rule-tracker (call 2))))

"The `perm-tracker` service function is given an `:axiom/perm-versions` event and a `publish` function."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule-versions"
                :key "https://example.com/some/repo"
                :data ["ABCD1234" #{'perm.ABCD123}]
                :change 3}
               (fn publish [ev]
                 (throw (Exception. "This should not be called")))) => nil)

"It calls `zookeeper-counter-add` to increment the counter corresponding to the rule."
(fact
 (mock-counters "/perms/perm.ABCD123") => 5)

"If one or more perms go from 0 to a positive count, an `:axiom/perms-exist` event with `:change = 1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule-versions"
                :key "https://example.com/some/repo"
                :data ["ABCD1234" #{'perm.ABCD123
                                    'perm.EFGH456}]
                :change 3} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/perms-exist"
            :key "https://example.com/some/repo"
            :data ["ABCD1234" #{'perm.EFGH456}]
            :change 1}) => irrelevant))

"This of-course only happens when the change is positive."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule-versions"
                :key "https://example.com/some/repo"
                :data ["ABCD1234" #{'perm.FOOBAR}]
                :change -3} (fn publish [ev]
                              (throw (Exception. "This should not be called")))) => nil)

"If the aggregated value of the rule goes down to 0, an `:axiom/perms-exist` event with `:change = -1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule-versions"
                :key "https://example.com/some/repo"
                :data ["ABCD1234" #{'perm.ABCD123
                                    'perm.EFGH456}]
                :change -3} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/perms-exist"
            :key "https://example.com/some/repo"
            :data ["ABCD1234" #{'perm.EFGH456}]
            :change -1}) => irrelevant))

[[:chapter {:title "rule-migrator"}]]
"When the [perm-tracker](#perm-tracker) finds out new permacode modules have been introduced (for the first time), a migration process needs to take place
to process all the existing facts that interact with any new rules defined in these modules,
to create all the derived facts and intermediate rule tuples that are the result of this interaction."

"`rule-migrator` is the service function responsible for this.
It depends on [zk-plan](zk-plan.html#module), which it uses to create the migration plan.
We will mock this module with functions that record their own operation, so that we will later be able to view the plan that was created."
(fact
 (def calls (transient []))
 (def last-task (atom 0))
 (def mock-zk-plan
   {:create-plan (fn [parent]
                   (when-not (= permval/*hasher* :my-hasher)
                     (throw (Exception. "A custom hasher needs to be bound")))
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
We also provides it a `migration-config` resource containing the `:number-of-shards` parameter, determining how much parallelism we wish to have.
The `hasher` resource is used to retrieve the content of the permacode modules."
(fact
 (let [calls-chan (async/chan 10)
       $ (di/injector {:declare-service (fn [key reg] (async/>!! calls-chan [:declare-service key reg]))
                       :assign-service (fn [key func] (async/>!! calls-chan [:assign-service key func]))
                       :zk-plan mock-zk-plan
                       :migration-config {:number-of-shards 3
                                          :plan-prefix "/my-plans"}
                       :hasher :my-hasher})]
   (module $)
   (di/startup $)
   (async/alts!! [calls-chan
                  (async/timeout 1000)]) => [[:declare-service "migrator.core/rule-migrator" {:kind :fact
                                                                                              :name "axiom/perms-exist"}] calls-chan]
   (let [[call ch] (async/alts!! [calls-chan
                                  (async/timeout 1000)])]
     ch => calls-chan
     (take 2 call) => [:assign-service "migrator.core/rule-migrator"]
     (def migrate-rules (call 2)))))

"Now, if we call the migration function `migrate-rules` on a rule, it will create a migration plan.
First, it will extract the rules out of the given permacode modules.
Then it will sort them according to their dependencies.
Finally, it will create a migration plan that will cover all rule functions in these modules, to be migrated one by one, in topological order."

"The plan will include, for each rule, a singleton [fact-declarer](#fact-declarer) tasks that will start collecting events to be processed by the given rule
once the migration is complete; a first phase of [initial-migrators](#initial-migrator) to process link 0 of the rule
and any number of [link-migrator](#link-migrator) phases to process the rest of the links."
(fact
 (migrate-rules {:kind :fact
                 :name "axiom/perms-exist"
                 :key "https://example.com/some/repo"
                 :data ["ABCD1234" #{'perm.ABC1234 'perm.DEF5678}]
                 :writers #{:some-writers}
                 :change 1}) => nil
 (provided
  (extract-version-rules 'perm.ABC1234) => [timeline]
  (extract-version-rules 'perm.DEF5678) => [trending]
  (clg/sort-rules [trending timeline]) => [timeline trending])
 (persistent! calls)
 => [[:create-plan "/my-plans"]
     [:add-task :plan-node `(fact-declarer 'migrator.core-test/timeline 0) []] ;; => 1
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/timeline 0 3) [1]] ;; => 2
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/timeline 1 3) [1]] ;; => 3
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/timeline 2 3) [1]] ;; => 4
     [:add-task :plan-node `(fact-declarer 'migrator.core-test/timeline 1) [2 3 4]] ;; => 5
     [:add-task :plan-node `(link-migrator 'migrator.core-test/timeline 1 0 3) [5]] ;; => 6
     [:add-task :plan-node `(link-migrator 'migrator.core-test/timeline 1 1 3) [5]] ;; => 7
     [:add-task :plan-node `(link-migrator 'migrator.core-test/timeline 1 2 3) [5]] ;; => 8
     [:add-task :plan-node `(migration-end-notifier 'migrator.core-test/timeline #{:some-writers}) [6 7 8]] ;; => 9
     [:add-task :plan-node `(fact-declarer 'migrator.core-test/trending 0) [9]] ;; => 10
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/trending 0 3) [10]] ;; => 11
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/trending 1 3) [10]] ;; => 12
     [:add-task :plan-node `(initial-migrator 'migrator.core-test/trending 2 3) [10]] ;; => 13
     [:add-task :plan-node `(fact-declarer 'migrator.core-test/trending 1) [11 12 13]] ;; => 14
     [:add-task :plan-node `(link-migrator 'migrator.core-test/trending 1 0 3) [14]] ;; => 15
     [:add-task :plan-node `(link-migrator 'migrator.core-test/trending 1 1 3) [14]] ;; => 16
     [:add-task :plan-node `(link-migrator 'migrator.core-test/trending 1 2 3) [14]] ;; => 17
     [:add-task :plan-node `(migration-end-notifier 'migrator.core-test/trending #{:some-writers}) [15 16 17]] ;; => 18
     [:mark-as-ready :plan-node]])

[[:chapter {:title "Usage Example"}]]
"In this example we will migrate the rules provided in our [tweetlog example](https://github.com/brosenan/tweetlog-clj)."

"First, we need to provide configuration to connect to Zookeeper, RabbitMQ, DynamoDB (local) and S3 
(where we provide coordinates using environment variables)."
(def config
  {:zookeeper-config {:url "127.0.0.1:2181"}
   :zk-plan-config {:num-threads 5
                    :parent "/my-plans"}
   :dynamodb-config {:access-key "FOO"
                     :secret-key "BAR"
                     :endpoint "http://localhost:8006"}
   :num-database-retriever-threads 1
   :dynamodb-default-throughput {:read 1 :write 1}
   :dynamodb-event-storage-num-threads 3
   :rabbitmq-config {:username "guest"
                 :password "guest"
                 :vhost "/"
                 :host "localhost"
                 :port 5672}
   :migration-config {:number-of-shards 3
                      :plan-prefix "/my-plans"
                      :clone-location "/tmp"
                      :clone-depth 10}
   :s3-config {:bucket-name (System/getenv "PERMACODE_S3_BUCKET")
               :access-key (System/getenv "AWS_ACCESS_KEY")
               :secret-key (System/getenv "AWS_SECRET_KEY")}})

"We now create an injector based on the config, and inject dependencies to the migrator and its dependencies."
(fact
 :integ
 (def $ (di/injector config))
 (module $)
 (rms/module $)
 (zkp/module $)
 (dyn/module $)
 (s3/module $)
 (di/startup $))

"Let's create the root elements we need in Zookeeper"
(fact
 :integ
 (di/do-with! $ [zookeeper]
   (when (zk/exists zookeeper "/perms")
     (zk/delete-all zookeeper "/perms"))
   (zk/create zookeeper "/perms" :persistent? true)
   (when (zk/exists zookeeper "/my-plans")
     (zk/delete-all zookeeper "/my-plans"))
   (zk/create zookeeper "/my-plans" :persistent? true)))

"The next step would be to generate test data.
We will start with tweets:"
(fact
 :integ
 (def users ["alice" "bob" "charlie"])
 (def time (atom 1000))
 (di/do-with! $ [publish]
   (doseq [greeting ["hello" "hi" "howdy"]
           greeted ["world" "clojure" "axiom"]
           user users]
     (publish {:kind :fact
               :name "tweetlog/tweeted"
               :key user
               :data [(str greeting " " greeted " from " user)]
               :ts @time
               :change 1
               :writers #{user}
               :readers #{}})
     (swap! time inc))))

"Now let's create a full-factorial following matrix (everyone follows everyone else)."
(fact
 :integ
 (di/do-with! $ [publish]
   (doseq [u1 users
           u2 users]
     (when-not (= u1 u2)
       (publish {:kind :fact
                 :name "tweetlog/follows"
                 :key u1
                 :data [u2]
                 :ts @time
                 :change 1
                 :writers #{u1}
                 :readers #{}}))
     (swap! time inc))
   :not-nil))

"Let's give the microservices a few seconds to store all the facts."
(fact
 :integ
 (Thread/sleep 5000))

"To know when our migration is complete we need to listen to `axiom/rule-ready` events.
The following service function listens to such events and for the rule `followee-tweets` it closes a channel to indicate it is done."
(fact
 :integ
 (def done (async/chan))
 (di/do-with! $ [serve]
   (serve (fn [ev]
            (when (= (name (:key ev)) "followee-tweets")
              (async/close! done)))
          {:kind :fact
           :name "axiom/rule-ready"})))

"To kick the migration, we need to publish an `axiom/app-version` event with a version of the example application."
(fact
 :integ
 (di/do-with! $ [publish]
   (publish {:kind :fact
             :name "axiom/app-version"
             :key "https://github.com/brosenan/tweetlog-clj.git"
             :data ["d3a8c6c5b946279186f857381e751801a657f70c"]
             :ts 1000
             :change 1
             :writers #{}
             :readers #{}})))

"So now we wait for the migration to complete."
(fact
 :integ
 (async/<!! done))

"After the migration, all facts have been processed by the rules.
This means that Alice's timeline should contain 18 tweets."
(fact
 :integ
 (di/do-with! $ [database-chan]
   (let [chan-out (async/chan 30)]
     (async/>!! database-chan [{:kind :fact
                                :name "perm.QmdLhmeiaJTMPdv7oT7mUsztdtjHq7f16Dw6nkR6JhxswP/followee-tweets"
                                :key "alice"} chan-out])
     (loop [res 0]
       (let [ev (async/<!! chan-out)]
         (cond (nil? ev)
               res
               :else
               (recur (inc res))))))) => 18)

"Finally, we shut down the injector to stop the workers."
(fact
 :integ
 (di/shutdown $))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "zookeeper-counter-add"}]]
"`zookeeper-counter-add` depends on the `zookeeper` resource as dependency, and uses it to implement a global atomic counter."
(let [$ (di/injector {:zookeeper :zk})]
  (module $)
  (di/startup $)
  (def zookeeper-counter-add (di/do-with! $ [zookeeper-counter-add] zookeeper-counter-add)))

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
 (def decl-my-fact (fact-declarer 'perm.ABC123/my-rule 1)))

"The returned fact declarer is intended to be used in a [zk-plan](zk-plan.html), as a [task](zk-plan.html#add-task) function.
As such, it needs to accept one or more arguments.
It ignores all but the first one, which is an injector (`$`) passed to it directly by `zk-plan`."

"The injector must be able to resolve the resources `declare-service` (e.g., [the one implemented for RabbitMQ](rabbit-microservices.html#declare-service)),
and `hasher` (e.g., [the one implemented for S3](s3.html#hasher)).
We will mock them here."
(fact
 (let [calls (async/chan 10)
       $ (di/injector {:hasher :my-hasher
                       :declare-service (fn [key reg]
                                          (when-not (= permval/*hasher* :my-hasher)
                                            (throw (Exception.
                                                    "A custom hasher needs to be bound")))
                                          (async/>!! calls [:declare-service key reg]))})]
   (decl-my-fact $ :some :args :that :are :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC123/my-rule) => timeline)
   ;; Assert the calls
   (async/alts!! [calls
                  (async/timeout 1000)])
   => [[:declare-service "fact-for-rule/perm.ABC123/my-rule!1" {:kind :fact
                                                                :name "test/tweeted"}] calls]))

[[:section {:title "initial-migrator"}]]
"`initial-migrator` creates a migration function for link 0 of a rule.
Link 0 is special in that it only depends on a fact, creates rule tuples based on fact tuples.
Other links also depend on previous rule tuples."

"`initial-migrator` takes the following arguments:
1. `rule`: The name of a rule (as a symbol).
2. `shard`: The shard number
3. `shards`: The total number of shards being used.
It returns a closure (function) that operates from within a [zk-plan](zk-plan.html)."
(def my-migrator (initial-migrator 'perm.ABC/my-rule 2 6))

"The migration process requires a `database-scanner` (e.g., [this](dynamo.html#database-scanner)) to scan given shard of the given table (fact).
We mock this function by providing `:test/follows` facts for Alice, who follows Bob, Charlie and Dave. "
(defn mock-scanner [name shard shards chan]
  (when-not (= [name shard shards] ["test/follows" 2 6])
    (throw (Exception. (str "Unexpected args in mock-scanner: " [name shard shards]))))
  (when-not (= permval/*hasher* :my-hasher)
    (throw (Exception. "A custom hasher needs to be bound")))
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
Evaluation leverages the `hasher` resource.
It will evaluate this function on each result comming from the `database-scanner`."
(fact
 (let [$ (di/injector {:hasher :my-hasher
                       :database-scanner mock-scanner
                       :database-event-storage-chan rule-tuple-chan})]
   (my-migrator $ :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC/my-rule) => timeline)))

"The migrator function should push to `database-event-storage-chan` rule events.
In our case, these should be one per each fact."
(fact
 (defn read-event []
   (let [resp (async/alts!! [rule-tuple-chan
                             (async/timeout 1000)])]
     (when-not (= (second resp) rule-tuple-chan)
       (throw (Exception. "Opration timed out")))
     (async/close! (second (first resp))) ;; ack
     (first (first resp))))
 
 (read-event) => {:change 1
                  :data ["alice" "bob"]
                  :key "bob"
                  :kind :rule
                  :name "migrator.core-test/timeline!0"
                  :readers nil
                  :writers #{"migrator.core-test"}}
 (read-event) => {:change 1
                  :data ["alice" "charlie"]
                  :key "charlie"
                  :kind :rule
                  :name "migrator.core-test/timeline!0"
                  :readers nil
                  :writers #{"migrator.core-test"}}
 (read-event) => {:change 1
                  :data ["alice" "dave"]
                  :key "dave"
                  :kind :rule
                  :name "migrator.core-test/timeline!0"
                  :readers nil
                  :writers #{"migrator.core-test"}})

[[:section {:title "link-migrator"}]]
"For links other than 0, migration requires applying a [matcher](cloudlog-events.html#matcher).
A matcher takes one event (a fact event in our case), and matches it against all matching events
(rule events in our case).
We need to provide a `database-chan` to allow the matcher to cross reference facts and rules."

"The `link-migrator` function takes the following arguments:
1. `rule`: The rule to be migrated.
2. `link`: The link number within the rule (> 0).
3. `shard`: The shard number to be processed by this node.
4. `shards`: The overall number of shards.
It returns a closure to be processed as a `zk-plan` task."
(def my-link-migrator (link-migrator 'perm.ABC/my-rule 1 3 17))

"For the migration process we will need a `database-scanner` that will provide fact events.
We will mock one to produce `:test/tweeted` facts, stating that Bob, Charlie and Dave all tweeted 'hello'."
(defn mock-scanner [name shard shards chan]
  (when-not (= [name shard shards] ["test/tweeted" 3 17])
    (throw (Exception. (str "Unexpected args in mock-scanner: " [name shard shards]))))
  (when-not (= permval/*hasher* :my-hasher)
    (throw (Exception. "A custom hasher needs to be bound")))
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
         (async/>!! out-chan {:change 1
                             :data ["alice" (:key query)]
                             :key (:key query)
                             :kind :rule
                             :name "migrator.core-test/timeline!0"
                             :readers nil
                             :writers #{:some-writer}})
         (async/>!! out-chan {:change 1
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
                       :database-event-storage-chan mock-store-chan
                       :hasher :my-hasher})]
   (my-link-migrator $ :ignored) => nil
   (provided
    (perm/eval-symbol 'perm.ABC/my-rule) => timeline)))

"The migration function reads all fact events from the scanner, 
for each event it consults the `database-chan` for matching rule tuples, and the resulting events (timeline facts in our case)
are pushed to the `database-event-storage-chan`."
(fact
 (defn read-event []
   (let [resp (async/alts!! [mock-store-chan
                             (async/timeout 1000)])]
     (when-not (= (second resp) mock-store-chan)
       (throw (Exception. "Operation timed out")))
     (async/close! (second (first resp))) ;; ack
     (first (first resp))))
 ;; For Bob
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "alice"
                  :data ["bob" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}}
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "eve"
                  :data ["bob" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}}
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "alice"
                  :data ["charlie" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}}
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "eve"
                  :data ["charlie" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}}
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "alice"
                  :data ["dave" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}}
 (read-event) => {:kind :fact
                  :name "migrator.core-test/timeline"
                  :key "eve"
                  :data ["dave" "hello"]
                  :change 1
                  :readers nil
                  :writers #{:some-writer}})

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

"Once called it should `publish` an `axiom/rule-ready` event (we ignore the `:ts` field which changes with time)."
(fact
 (-> events
     persistent!
     first
     (dissoc :ts)) => {:kind :fact
                       :name "axiom/rule-ready"
                       :key 'perm.ABC123/my-rule
                       :data []
                       :change 1
                       :writers #{:some-writer}})

[[:section {:title "extract-version-rules"}]]
"`extract-version-rules` extracts all the rule functions from such a version and publishes
corresponding `:axiom/rule` events."

"It returns only rule functions, identified by having a `:source-fact` meta field."
(fact
 (let [foo (with-meta (fn []) {:source-fact ["foo" 1]})
       bar (with-meta (fn []) {:source-fact ["bar" 1]})
       baz (fn [])]
   (extract-version-rules 'perm.1234ABC) => [foo
                                             bar]
   (provided
    (perm/module-publics 'perm.1234ABC) => {'foo foo
                                            'bar bar
                                            'baz baz} ; baz will not be published
    )))

"It excludes clauses, for which the `:source-fact` ends with a question mark (`?`)."
(fact
 (let [foo (with-meta (fn []) {:source-fact ["foo" 1]})
       bar (with-meta (fn []) {:source-fact ["bar?" 1]})]
   (extract-version-rules 'perm.1234ABC) => [foo]
   (provided
    (perm/module-publics 'perm.1234ABC) => {'foo foo
                                            'bar bar} ; bar will not be published
    )))
