(ns dynamo.core-test
  (:require [midje.sweet :refer :all]
            [dynamo.core :refer :all]
            [clojure.core.async :as async]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [di.core :as di]
            [langohr.core]
            [rabbit-microservices.core]))


[[:chapter {:title "database-chan: A Channel for Database Queries" :tag "database-chan"}]]
"The in [cloudlog-events](https://brosenan.github.io/axiom/cloudlog-events.html), the [matcher](https://brosenan.github.io/axiom/cloudlog-events.html#matcher)
requires some external entity to accept requests in the form of partial events on one channel, 
and respond by providing stored events matching the partial ones on another channel."

"`database-chan` is a [dependency-injection resource](di.html) which is a `core.async` channel on which requests can be posted."

"`database-chan` depends on the following resources:
1. `database-retriever`: a function that retrieves data from the database.
2. `num-database-retriever-threads`: a number determining the number of threads to be spawned."

"In the example below we inject a mock retriever function.
It will get a response channel from the request channel, and then output two events to it, and close it."
(fact
 (let [$ (di/injector {:database-retriever (fn [chan]
                                             (let [[_ resp-chan] (async/<!! chan)]
                                               (async/>!! resp-chan {:event 1})
                                               (async/>!! resp-chan {:event 2})
                                               (async/close! resp-chan)))
                       :num-database-retriever-threads 1})]
   (module $)
   (di/startup $)
   (let [chan-req (di/do-with! $ [database-chan] database-chan)
         chan-res (async/chan 10)]
     (async/>!! chan-req [{:some :request} chan-res])
     (async/<!! chan-res) => {:event 1}
     (async/<!! chan-res) => {:event 2}
     (async/<!! chan-res) => nil)))

[[:chapter {:title "database-retriever: A Function for Retrieving Events from DynamoDB" :tag "database-retriever"}]]
"`database-retriever` is a function that retrieves data from DynamoDB.  
It is a DI-resource that depends on `dynamodb-config` -- a map containing credentials and other details for connecting to DynamoDB 
(see [faraday's documentation](https://github.com/ptaoussanis/faraday#connecting) for more details),
and `database-ensure-table` to ensure the table exists before accessing it."
(fact
 (def table-ensured (atom nil))
 (let [$ (di/injector {:dynamodb-config :some-config
                       :dynamodb-get-tables (fn [conf] [])
                       :database-ensure-table (fn [table]
                                                (reset! table-ensured table))})]
   (module $)
   (di/startup $)
   (def database-retriever (di/do-with! $ [database-retriever] database-retriever))))

"To demonstrate how it works, let's create two channels: a request channel and a response channel."
(fact
 (def chan-req (async/chan 1000))
 (def chan-res (async/chan 1000)))

"Now let's put a request in the request channel.  The request consists of a partial event and the response channel."
(fact
 (async/>!! chan-req [{:kind :fact
                       :name "foo/bar"
                       :key 123}
                      chan-res]))

"We call `database-retriever` with a client options map (see [faraday's documentation](https://github.com/ptaoussanis/faraday)),
and the request channel.  Once called, it will perform the following:
1. Call `faraday`'s `query` method, to get a collection of items (maps).
2. Call `nippy/thaw` to deserialize the body of each event."
(fact
 (database-retriever chan-req) => true
 (provided
  (far/query :some-config :foo.bar {:key [:eq "123"]})
  => [{:key "123" :ts 1000 :event ..bin1..}
      {:key "123" :ts 1001 :event ..bin2..}]
  (nippy/thaw ..bin1..) => {:data [1 2 3]}
  (nippy/thaw ..bin2..) => {:data [2 3 4]}))

"It also ensured that the table exists by calling (our mock) `database-ensure-table`."
(fact
 @table-ensured => :foo.bar)

"The events are reconstructed from the items:
- The `:kind`, `:name` and `:key` fields are taken from the request.
- The `:ts` field is taken from the item, and
- The rest of the fields are taken from the de-serialized `:event` field in the item."
(fact
                                        ; First event
 (async/alts!! [chan-res
                (async/timeout 100)]) => [{:kind :fact
                                           :name "foo/bar"
                                           :key 123
                                           :ts 1000
                                           :data [1 2 3]}
                                          chan-res]
                                        ; Second event
 (async/alts!! [chan-res
                (async/timeout 100)]) => [{:kind :fact
                                           :name "foo/bar"
                                           :key 123
                                           :ts 1001
                                           :data [2 3 4]}
                                          chan-res])

"Eventually `database-retriever` closes the response channel."
(fact
 (async/alts!! [chan-res
                (async/timeout 100)]) => [nil chan-res])

"If the request channel is closed, `database-retriever` exits, returning `false`."
(fact
 (async/close! chan-req)
 (database-retriever chan-req) => false)

[[:chapter {:title "database-event-storage: A Function for Storing Events" :tag "database-event-storage"}]]
"`database-event-storage` is a function for storing events in the database."

"It is a DI resource that depends on the following resources:
1. `dynamodb-config`: The DynamoDB credentials and coordinates, and
2. [database-ensure-table](#database-ensure-table): a function that makes sure the given table exists."
(fact
 (def table-ensured (atom nil))
 (let [$ (di/injector {:dynamodb-config :config
                       :database-ensure-table (fn [table]
                                                (reset! table-ensured table))
                       :database-tables (atom #{})
                       :dynamodb-default-throughput 0})]
   (module $)
   (di/startup $)
   (def database-event-storage (di/do-with! $ [database-event-storage] database-event-storage))))

"`database-event-storage` takes an event and stores it in DynamoDB."
(fact
 (def my-event {:kind :fact
                :name "foo"
                :key 1234
                :data [1 2 3 4]
                :ts 1000
                :change 1
                :writers #{}
                :readers #{}})
 (database-event-storage my-event) => nil
 (provided
  (nippy/freeze {:data [1 2 3 4]
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (far/put-item :config :foo {:key "1234"
                              :ts 1000
                              :event ..bin..}) => irrelevant))

"It ensures the table exists by calling (our mock) `database-ensure-table`."
(fact
 @table-ensured => :foo)

"Events with `:name` values that end with \"?\" or \"!\" should not be persisted, and are therefore ignored."
(fact
 (database-event-storage (assoc my-event :name "foo?")) => nil
 (provided
                                        ; No side effect
  )
 (database-event-storage (assoc my-event :name "bar!")) => nil
 (provided
                                        ; No side effect
  ))

[[:chapter {:title "store-fact (service): Stores Fact Events" :tag "store-fact"}]]
"`store-fact` is a microservice function that subscribes to all fact events.
It depends on the following resources:
1. `declare-service`: a function that creates a queue and binds it to receive certain events
2. `assign-service`: a function that registers it to certain events,
3. [database-event-storage](#database-event-storage)."

"The service is merely a serving of `database-event-storage`, registered to all fact events.
We will demonstrate it by mocking the `serve` function to push its parameters to a channel."
(fact
 (let [calls (transient [])
       $ (di/injector {:declare-service (fn [key reg] (conj! calls [:declare-service key reg]))
                       :assign-service (fn [key func] (conj! calls [:assign-service key func]))
                       :database-event-storage :the-storage-func})]
   (module $)
   (di/startup $)
   (persistent! calls) => [[:declare-service "database-event-storage" {:kind :fact}]
                           [:assign-service "database-event-storage" :the-storage-func]]))

[[:chapter {:title "database-event-storage-chan: A Channel for Storing Events in the Database" :tag "database-event-storage-chan"}]]
"While [store-fact](#store-fact#) allows us to store system-wide fact events to the database,
we sometimes wish to store events events without publishing them system-wide.
`database-event-storage-chan` is a `core.async` *channel* that allows storing of event into the database without
publishing them system-wide."

"`database-event-storage-chan` depends on the following resources:
1. [database-event-storage](#database-event-storage)
2. `dynamodb-event-storage-num-threads`: The number of threads to be spawned for this task."

"In the following example we will demonstrate how it works by mocking `database-event-storage` to conj the given event
to a sequence stored in an atom."
(fact
 (def db (atom '()))
 (let [$ (di/injector {:database-event-storage (fn [ev]
                                                 (swap! db #(conj % ev)))
                       :dynamodb-event-storage-num-threads 2})]
   (module $)
   (di/startup $)
   (def database-event-storage-chan (di/do-with! $ [database-event-storage-chan] database-event-storage-chan))))

"We operate the channel by sending it pairs: `[ev ack]`, where `ev` is the event we wish to store,
and `ack` is a fresh channel we use to get acknowledgement through.
Once the event is safely stored, `ack` will be closed."

(fact
 (let [[ack1 ack2 ack3] (for [_ (range 3)] (async/chan))]
   (async/>!! database-event-storage-chan [:ev1 ack1])
   (async/>!! database-event-storage-chan [:ev2 ack2])
   (async/>!! database-event-storage-chan [:ev3 ack3])
   (async/alts!! [ack1 (async/timeout 1000)]) => [nil ack1]
   (async/alts!! [ack2 (async/timeout 1000)]) => [nil ack2]
   (async/alts!! [ack3 (async/timeout 1000)]) => [nil ack3])
 (set @db) => #{:ev1 :ev2 :ev3})



[[:chapter {:title "database-scanner: Scan a Shard of a Table" :tag "database-scanner"}]]
"For the purpose of data migration we need to process all events in a table.
To do this efficiently, we shard the table."

"`database-scanner` depends on `dynamodb-config` for credentials to DynamoDB, and on `database-ensure-table` to ensure the
table exists before issuing the actual scan request."
(fact
 (def table-ensured (atom nil))
 (let [$ (di/injector {:dynamodb-config :some-config
                       :database-ensure-table (fn [table]
                                                (reset! table-ensured table))
                       :dynamodb-get-tables (fn [conf] []) ;; Irrelevant to this test
                       })]
   (module $)
   (di/startup $)
   (def database-scanner (di/do-with! $ [database-scanner] database-scanner))))

"`database-scanner` is given a name of a table, a shard number, a total number of shards and an output channel.
It then produces all events from that shard into the channel and closes the channel.
It is blocking, and is therefore assumed to be working from within its own thread."
(fact
 (database-scanner "foo/bar" ..shard.. ..shards.. ..chan..) => nil
 (provided
  (far/scan :some-config :foo.bar {:segment ..shard..
                                   :total-segments ..shards..}) => [{:key "1" :ts 1000 :event ..bin1..}
                                                                    {:key "2" :ts 2000 :event ..bin2..}]
  (nippy/thaw ..bin1..) => {:data [1 2 3]}
  (async/>!! ..chan.. {:kind :fact
                       :name "foo/bar"
                       :key 1
                       :ts 1000
                       :data [1 2 3]}) => irrelevant
  (nippy/thaw ..bin2..) => {:data [2 3 4]}
  (async/>!! ..chan.. {:kind :fact
                       :name "foo/bar"
                       :key 2
                       :ts 2000
                       :data [2 3 4]}) => irrelevant
  (async/close! ..chan..) => irrelevant))

"Before issuing the scan, `database-scanner` calls `database-ensure-table` to make sure the table exists."
(fact
 @table-ensured => :foo.bar)

[[:chapter {:title "Usage Example"}]]
"To see that this library can actually connect to DynamoDB we will use the `store-fact` service to create a few events in different
tables, and then use `database-chan` to retrieve some of them."

"We will use a [local DynamoDB](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) exposed on the local host on port 8006.
Our configuration is as follows (we follow [these](https://github.com/ptaoussanis/faraday#connecting) instructions:"
(fact
 :integ ; Does not run on usual CI testing
 (def client-opts
   {:access-key "FOO"
    :secret-key "BAR"
    :endpoint "http://localhost:8006"}))

"We will use a real [rabbitmq microservice](rabbit-microservices.html) to store the facts in the database.
We will use [dependency injection](di.html) to initialize the needed services."
(fact
 :integ ; Does not run on usual CI testing
 (let [$ (di/injector {:dynamodb-config client-opts
                       :num-database-retriever-threads 1
                       :dynamodb-default-throughput {:read 1 :write 1}
                       :rabbitmq-config langohr.core/*default-config*})]
   (module $)
   (rabbit-microservices.core/module $)
   (di/startup $)
   (di/do-with! $ [database-chan publish]
                (def req-chan database-chan)
                (def publish publish))))

"Now let's create a bunch of events with different `:name`, `:key` and `:ts` values."
(def events
  (for [name ["foo/foo" "foo/bar" "bar/baz"]
        key ["x" :y ["z"]]
        ts [1000 2000 3000]]
    (let [data [name key ts]]
      {:kind :fact
       :name name
       :key key
       :ts ts
       :data data
       :change 1})))

"We will [publish](rabbit-microservices.html#publish) each event to have the [store-fact service](#store-fact) store them to DynamoDB."
(fact
 :integ ; Does not run on usual CI testing
 (doseq [ev events]
   (publish ev)))

"Let's wait to give all the events time to get stored."
(fact
 :integ ; Does not run on usual CI testing
 (Thread/sleep 5000))

"Out of all the events we created, we are interested in the ones with `:name = foo/foo` and `:key = [\"z\"]"
(fact
 :integ ; Does not run on usual CI testing
 (let [res-chan (async/chan 1000)]
   (async/>!! req-chan [{:kind :fact
                         :name "foo/foo"
                         :key ["z"]}
                        res-chan])
   (async/alts!! [res-chan
                  (async/timeout 1000)]) => [{:kind :fact
                                              :name "foo/foo"
                                              :key ["z"]
                                              :ts 1000
                                              :data ["foo/foo" ["z"] 1000]
                                              :change 1}
                                             res-chan]
   (async/alts!! [res-chan
                  (async/timeout 1000)]) => [{:kind :fact
                                              :name "foo/foo"
                                              :key ["z"]
                                              :ts 2000
                                              :data ["foo/foo" ["z"] 2000]
                                              :change 1}
                                             res-chan]
   (async/alts!! [res-chan
                  (async/timeout 1000)]) => [{:kind :fact
                                              :name "foo/foo"
                                              :key ["z"]
                                              :ts 3000
                                              :data ["foo/foo" ["z"] 3000]
                                              :change 1}
                                             res-chan]
   (async/alts!! [res-chan
                  (async/timeout 1000)]) => [nil
                                             res-chan]))

"We shut down the service by closing the request channel."
(fact
 :integ ; Does not run on usual CI testing
 (async/close! req-chan))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "table-kw"}]]
"The `taoensso.faraday` library we use uses keywords to convey names of tables and fields.
In axiom, table names (the `:name` field of an event) originate from keywords, but they may contain characters
that do not conform to [DynamoDB's naming policy](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/HowItWorks.NamingRulesDataTypes.html),
and in addition, the names used by axiom are fully qualified, and `faraday` only takes the `name`."

"To overcome this, the function `table-kw` converts the `:name` attribute of an event to a `faraday`-compatible keyword."

"By default, it simply converts a string to a keyword."
(fact
 (table-kw "foo") => :foo)

"If the name contains a '/' it is converted to a '.', so that applying `name` to it will retain the prefix."
(fact
 (name (table-kw "foo/bar")) => "foo.bar")

"Other illegal characters are converted to an underscore ('_')."
(fact
 (table-kw "?,:!@#$%^&*-()") => :___________-__)


[[:section {:title "database-tables"}]]
"`database-tables` is an atom containing a set of table keywords currently in the database.
It depends on:
1. `dynamodb-config`: the database credentials, and
2. `dynamodb-get-tables`: a function to get the list of tables.
The latter is intended for testing and is provided by this module to be `far/list-tables`."
(fact
 (let [$ (di/injector {:dynamodb-config :config
                       :dynamodb-get-tables (fn [config]
                                              [config :foo :bar])})]
   (module $)
   (di/startup $)
   (di/do-with! $ [database-tables]
                @database-tables) => #{:foo :bar :config}))

[[:section {:title "database-ensure-table"}]]
"Sometimes, before performing operations such as storing an event or starting a scan we want to ensure that the
table we have at hand actually exists.
If it doesn't, or we don't know it exists, we wish to create it.
We count on DynamoDB's ignoring re-creation of tables that already exist."

"`database-ensure-table` is based on the following:
1. [database-tables](#database-tables): the set of currently known tables,
2. `dynamodb-default-throughput`: a map containing the default configuration for a new table, and
3. `dynamodb-config`: credentials to talk to DynamoDB."
(fact
 (let [$ (di/injector {:database-tables (atom #{:foo :bar})
                       :dynamodb-default-throughput {:default :throughput}
                       :dynamodb-config :config})]
   (module $)
   (di/startup $)
   (def database-ensure-table (di/do-with! $ [database-ensure-table] database-ensure-table))
   (def database-tables (di/do-with! $ [database-tables] database-tables)))) ;; For inspecting it after calling database-ensure-table

"`database-ensure-table` is a function that takes a name of a table (as a keyword).
If the table exists in `database-tables`, it does nothing."
(fact
 (database-ensure-table :foo) => nil
 (provided
  ;; No calls
  ))

"However, if the table does not exist in `database-tables`, `database-ensure-table` creates a table in DynamoDB."
(fact
 (database-ensure-table :other-table) => nil
 (provided
  (far/ensure-table :config :other-table [:key :s] ; :key is the partition key
                    {:range-keydef [:ts :n] ; and :ts is the range key
                     :throughput {:default :throughput}
                     :block true}) => irrelevant))

"After ensuring a table exists, the table is added to `database-tables`."
(fact
 @database-tables => #{:foo :bar :other-table})


"In case of a failure due to a wrong state, the operation succeeds -- 
after all, the table couldn't have been in the wrong state if it hadn't existed..."
(fact
 (database-ensure-table :yet-another) => nil
 (provided
  (far/ensure-table :config :yet-another [:key :s] ; :key is the partition key
                    {:range-keydef [:ts :n] ; and :ts is the range key
                     :throughput {:default :throughput}
                     :block true})
  =throws=> (com.amazonaws.services.dynamodbv2.model.ResourceInUseException. "bad state...")))

"The `database-tables` set is still updated (we know the table exists)."
(fact
 @database-tables => #{:foo :bar :other-table :yet-another})
