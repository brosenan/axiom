(ns dynamo.core-test
  (:require [midje.sweet :refer :all]
            [dynamo.core :refer :all]
            [clojure.core.async :as async]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [di.core :as di]))


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
   (let [chan-req (di/wait-for $ database-chan)
         chan-res (async/chan 10)]
     (async/>!! chan-req [{:some :request} chan-res])
     (async/<!! chan-res) => {:event 1}
     (async/<!! chan-res) => {:event 2}
     (async/<!! chan-res) => nil)))

[[:chapter {:title "database-retriever: A Function for Retrieving Events from DynamoDB" :tag "database-retriever"}]]
"`database-retriever` is a function that retrieves data from DynamoDB.  
It is a DI-resource that depends on `dynamodb-config` -- a map containing credentials and other details for connecting to DynamoDB 
(see [faraday's documentation](https://github.com/ptaoussanis/faraday#connecting) for more details)."
(fact
 (let [$ (di/injector {:dynamodb-config :some-config
                       :dynamodb-get-tables (fn [conf] [])})]
   (module $)
   (def database-retriever (di/wait-for $ database-retriever))))

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

[[:chapter {:title "store-fact"}]]
"`store-fact` is a microservice function that subscribes to all fact events.
It depends on the following resources:
1. `serve`: a function that registers it to certain events,
2. `dynamodb-config`: DynamoDB credentials etc.
3. `dynamodb-default-throughput`: a map containing default throughput parameters for new tables
4. `database-tables`: an atom containing a set of the currently-known tables."

"Since the function itself is not provided as a resource by itself, we can grab it
by mocking `serve` to store the event handler function aside."
(do
  (def my-store-fact (atom nil))
  (def partial-event (async/chan))
  (def table-set (atom #{:foo :bar}))
  (let [$ (di/injector {:dynamodb-config :config
                        :serve (fn [func reg]
                                 (reset! my-store-fact func)
                                 (async/>!! partial-event reg))
                        :database-tables table-set
                        :dynamodb-default-throughput {:read 1 :write 1}})]
    (module $)))

(fact
 (async/<!! partial-event) => {:kind :fact})

"`store-fact` responds to events by storing them in DynamoDB."
(fact
 (def my-event {:kind :fact
                :name "foo"
                :key 1234
                :data [1 2 3 4]
                :ts 1000
                :change 1
                :writers #{}
                :readers #{}})
 (@my-store-fact my-event) => nil
 (provided
  (nippy/freeze {:data [1 2 3 4]
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (far/put-item :config :foo {:key "1234"
                              :ts 1000
                              :event ..bin..}) => irrelevant))

"If the table does not exist, it is created."
(fact
 (def my-event2 (assoc my-event :name "baz"))
 (@my-store-fact my-event2) => nil
 (provided
  (nippy/freeze {:data [1 2 3 4]
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (far/ensure-table :config :baz [:key :s] ; :key is the partition key
                    {:range-keydef [:ts :n] ; and :ts is the range key
                     :throughput {:read 1 :write 1}
                     :block true}) => irrelevant
  (far/put-item :config :baz {:key "1234"
                              :ts 1000
                              :event ..bin..}) => irrelevant))

"The new table is then added to the set."
(fact
 @table-set => #{:foo :bar :baz})

"Events with `:name` values that end with \"?\" or \"!\" should not be persisted, and are therefore ignored."
(fact
 (@my-store-fact (assoc my-event :name "foo?")) => nil
 (provided
                                        ; No side effect
  )
 (@my-store-fact (assoc my-event :name "bar!")) => nil
 (provided
                                        ; No side effect
  ))


[[:chapter {:title "database-tables"}]]
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
   (let [tables (di/wait-for $ database-tables)]
     @tables => #{:foo :bar :config})))

[[:chapter {:title "database-scanner: Scan a Shard of a Table" :tag "database-scanner"}]]
"For the purpose of data migration we need to process all events in a table.
To do this efficiently, we shard the table."

"`database-scanner` depends on `dynamodb-config`."
(let [$ (di/injector {:dynamodb-config :some-config
                      :dynamodb-get-tables (fn [conf] [])})]
  (module $)
  (def database-scanner (di/wait-for $ database-scanner)))

"`database-scanner` is given a name of a table, a shard number, a total number of shards and an output channel.
It then produces all events from that shard into the channel and closes the channel.
It is blocking, and is therefore assumed to be working from within its own thread."
(fact
 (database-scanner ..table.. ..shard.. ..shards.. ..chan..) => nil
 (provided
  (table-kw ..table..) => ..kw..
  (far/scan :some-config ..kw.. {:segment ..shard..
                               :total-segments ..shards..}) => [{:key "1" :ts 1000 :event ..bin1..}
                                                                {:key "2" :ts 2000 :event ..bin2..}]
  (nippy/thaw ..bin1..) => {:data [1 2 3]}
  (async/>!! ..chan.. {:kind :fact
                       :name ..table..
                       :key 1
                       :ts 1000
                       :data [1 2 3]}) => irrelevant
  (nippy/thaw ..bin2..) => {:data [2 3 4]}
  (async/>!! ..chan.. {:kind :fact
                       :name ..table..
                       :key 2
                       :ts 2000
                       :data [2 3 4]}) => irrelevant))

[[:chapter {:title "Usage Example"}]]
"To see that this library can actually connect to DynamoDB we will use `store-fact` to create a few events in different
tables, and then use `database-chan` to retrieve some of them."

"We will use a [local DynamoDB](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) exposed on the local host on port 8006.
Our configuration is as follows (we follow [these](https://github.com/ptaoussanis/faraday#connecting) instructions:"
(fact
 :integ ; Does not run on usual CI testing
 (def client-opts
   {:access-key "FOO"
    :secret-key "BAR"
    :endpoint "http://localhost:8006"}))

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

"Let's inject some dependencies to get `store-fact` and `database-chan`:"
(fact
 :integ ; Does not run on usual CI testing
 (def store-fact (atom nil))
 (let [chan (async/chan)
       $ (di/injector {:dynamodb-config client-opts
                       :num-database-retriever-threads 1
                       :dynamodb-default-throughput {:read 1 :write 1}
                       :serve (fn [func reg]
                                (reset! store-fact func)
                                (async/close! chan))})]
   (module $)
   (def req-chan (di/wait-for $ database-chan))
   (async/<!! chan)))

"Now we use `store-fact` to store all these events."
(fact
 :integ ; Does not run on usual CI testing
 (doseq [ev events]
   (@store-fact ev)))

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

"We shut down the service by closing the request channel, and then waiting for the
retriever thread to complete by reading from it."
(fact
 :integ ; Does not run on usual CI testing
 (async/close! req-chan))

[[:chapter {:title "Under the Hood"}]]
[[:chapter {:title "table-kw"}]]
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


