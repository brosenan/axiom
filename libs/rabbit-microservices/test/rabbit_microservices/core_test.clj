(ns rabbit-microservices.core-test
  (:require [midje.sweet :refer :all]
            [rabbit-microservices.core :refer :all])
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]))

[[:chapter {:title "Introduction"}]]
"`rabbit-microservices` is a library that allows microservices to be defined as Clojure modules.
A microservice is attached to a queue, and consists of functions that are applied in response to events on the queue.
Functions are associated with AMQP topics according to meta fields provided for them.
Each such function is given two arguments when invoked: The event on which it was invoked, and a `publish` function
allowing it to publish events in response."

"The event structure we refer to here is the one discussed in [cloudlog-events](cloudlog-events.html)."

[[:chapter {:title "create-service: Initialization" :tag "create-service"}]]
"`create-service` initializes a connection and a channel to RabbitMQ.  
It also declares a server-named queue to be associated with the service."
(fact
 (create-service) => {:conn ..conn..
                      :chan ..chan..
                      :q ..qname..}
 (provided
  (rmq/connect) => ..conn..
  (lch/open ..conn..) => ..chan..
  (lq/declare-server-named ..chan..) => ..qname..))

"The dynamic variable `langohr.core/*default-config*` controls the configuration.  See the [Langohr documentation](http://clojurerabbitmq.info) for more information."

[[:chapter {:title "shut-down-service: Cleanup" :tag "shut-down-service"}]]
"`shut-down-service` closes the channel and the connection to RabbitMQ."
(fact
 (shut-down-service {:conn ..conn..
                     :chan ..chan..}) => nil
 (provided
  (rmq/close ..chan..) => irrelevant
  (rmq/close ..conn..) => irrelevant))

[[:chapter {:title "register-func: Register an Event Handler" :tag "register-func"}]]
"`register-func` "

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "event-routing-key"}]]
"The `event-routing-key` function returns a AMQP-conforming routing key for a given event map."
(fact
 (event-routing-key {:kind :fact
                     :name "foo/bar"
                     :key 1234
                     :ts 1000
                     :data ["foo" :bar]
                     :change 1
                     :writers #{}
                     :readers #{}}) => #"f\.[a-z0-9]+\.[a-z0-9]+")

"The initial `f` is derived from `:kind :fact`.  For `:kind :rule` an exception is thrown."
(fact
 (event-routing-key {:kind :rule
                     :name "foo/bar"
                     :key 1234
                     :ts 1000
                     :data ["foo" :bar]
                     :change 1
                     :writers #{}
                     :readers #{}}) => (throws "Only fact events are supported"))

"The ony fields that effect the routing key are `kind`, `name` and `key`."
(fact (let [base  (event-routing-key {:kind :fact
                                      :name "foo/bar"
                                      :key 1234
                                      :ts 1000
                                      :data ["foo" :bar]
                                      :change 1
                                      :writers #{}
                                      :readers #{}})]
        (= (event-routing-key {:kind :fact
                               :name "foo/bar"
                               :key 1234
                               :ts 1001
                               :data ["fool" :baz]
                               :change -1
                               :writers #{:me}
                               :readers #{:you}}) base) => true
        (= (event-routing-key {:kind :fact
                               :name "foo/baz" ; Different name
                               :key 1234
                               :ts 1001
                               :data ["fool" :baz]
                               :change -1
                               :writers #{:me}
                               :readers #{:you}}) base) => false
        (= (event-routing-key {:kind :fact
                               :name "foo/bar"
                               :key [:foo :bar] ; Different key
                               :ts 1001
                               :data ["fool" :baz]
                               :change -1
                               :writers #{:me}
                               :readers #{:you}}) base) => false))

"`event-routing-key` can handle partial events.
If `:name` is omitted, a pattern that matches all facts is returned."
(fact
 (event-routing-key {:kind :fact}) => "f.#")

"If `:name` is provided, but `:key` is omitted, a pattern matching all keys with that name is returned."
(fact
 (event-routing-key {:kind :fact
                     :name "foo/bar"}) => #"f\.[0-9a-f]+\.#")
