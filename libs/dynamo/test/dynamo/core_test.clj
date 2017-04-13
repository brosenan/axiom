(ns dynamo.core-test
  (:require [midje.sweet :refer :all]
            [dynamo.core :refer :all]
            [clojure.core.async :as async]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]
            [dynamo.srv :as srv]))


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


[[:chapter {:title "retriever: An Async Retrieval Function" :tag "retriever"}]]
"The in [cloudlog-events](https://brosenan.github.io/axiom/cloudlog-events.html), the [matcher](https://brosenan.github.io/axiom/cloudlog-events.html#matcher)
requires some external entity to accept requests in the form of partial events on one channel, 
and respond by providing stored events matching the partial ones on another channel."

"`retriever` is a function, intended to be used from inside a `async/thread` block, and listens to requests coming on a given channel.
For each such request it performs a query according to the parameters provided in the partial event.
Then it translates the results to events and pushes them to an output channel provided with the request."

"To demonstrate this, let's first create two channels: one for the request and one for the response."
(fact
 (def chan-req (async/chan 1000))
 (def chan-res (async/chan 1000)))

"Now let's put a request in the request channel.  The request consists of a partial event and the response channel."
(fact
 (async/>!! chan-req [{:kind :fact
                       :name "foo/bar"
                       :key 123}
                      chan-res]))

"`retriever` is supposed to already run in a `thread` and wait for this request.
In this test we simulate this by calling it after the request is already in the queue."

"We call `retriever` with a client options map (see [faraday's documentation](https://github.com/ptaoussanis/faraday)),
and the request channel.  Once called, it will perform the following:
1. Call `faraday`'s `query` method, to get a collection of items (maps).
2. Call `nippy/thaw` to deserialize the body of each event."
(fact
 (retriever ..config.. chan-req) => true
 (provided
  (far/query ..config.. :foo.bar {:key [:eq "123"]})
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

"Eventually `retriever` closes the response channel."
(fact
  (async/alts!! [chan-res
                (async/timeout 100)]) => [nil chan-res])

"If the request channel is closed, `retriever` exits, returning `false`."
(fact
 (async/close! chan-req)
 (retriever ..config.. chan-req) => false)

[[:chapter "Integration Testing / Usage Example"]]
"To see that this library can actually connect to DynamoDB we will use `dynamo.srv` to create a few events in different
tables, and then use `retriever` to retrieve some of them."

"We will use a [local DynamoDB](http://docs.aws.amazon.com/amazondynamodb/latest/developerguide/DynamoDBLocal.html) exposed on the local host on port 8006.
Our configuration is as follows (we follow [these](https://github.com/ptaoussanis/faraday#connecting) instructions:"
(fact (def client-opts
        {:access-key "FOO"
         :secret-key "BAR"
         :endpoint "http://localhost:8006"})

      (srv/init client-opts))

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

"Now we use `srv/store-fact` to store all these events."
(fact
 :integ ; Does not run on usual CI testing
 (doseq [ev events]
   (srv/store-fact ev)))

"Let's fire up a thread to handle retrieval of events."
(fact
 :integ ; Does not run on usual CI testing
 (def req-chan (async/chan 1000))
 (def worker-thread
   (async/thread
     (while (retriever client-opts req-chan)))))

"Out of all the events we created, we are interested in the ones with `:name = foo/foo` and `:key = [\"z\"]"
(fact
 :integ ; Does not run on usual CI testing
 (let [res-chan (async/chan 1000)]
   (async/>!! req-chan [{:kind :fact
                         :name "foo/foo"
                         :key ["z"]}
                        res-chan])
   (async/alts!! [res-chan
                  (async/timeout 100)]) => [{:kind :fact
                                             :name "foo/foo"
                                             :key ["z"]
                                             :ts 1000
                                             :data ["foo/foo" ["z"] 1000]
                                             :change 1}
                                            res-chan]
   (async/alts!! [res-chan
                  (async/timeout 100)]) => [{:kind :fact
                                             :name "foo/foo"
                                             :key ["z"]
                                             :ts 2000
                                             :data ["foo/foo" ["z"] 2000]
                                             :change 1}
                                            res-chan]
   (async/alts!! [res-chan
                  (async/timeout 100)]) => [{:kind :fact
                                             :name "foo/foo"
                                             :key ["z"]
                                             :ts 3000
                                             :data ["foo/foo" ["z"] 3000]
                                             :change 1}
                                            res-chan]
   (async/alts!! [res-chan
                  (async/timeout 100)]) => [nil
                                            res-chan]))

"We shut down the service by closing the request channel, and then waiting for the
retriever thread to complete by reading from it."
(fact
  :integ ; Does not run on usual CI testing
  (async/close! req-chan)
  (async/<!! worker-thread))
