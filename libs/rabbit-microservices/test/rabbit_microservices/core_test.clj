(ns rabbit-microservices.core-test
  (:require [midje.sweet :refer :all]
            [rabbit-microservices.core :refer :all]
            [taoensso.nippy :as nippy])
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
"`create-service` initializes a connection and a channel to RabbitMQ."
(fact
 (create-service) => {:conn ..conn..
                      :chan ..chan..}
 (provided
  (rmq/connect) => ..conn..
  (lch/open ..conn..) => ..chan..))

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
"`register-func` takes a service and a variable that refers to a function, and serves the function as part of the service."

"The function must have a meta parameter `:reg` consisting of a partial event map."
(defn  my-service
  {:reg {:kind :fact
         :name "foo/bar"}}
  [event]
  ; Do something...
  )
(fact
 (-> #'my-service meta :reg :name) => "foo/bar")

"When given such a function, `register-func` performs the following:
1. Declares a queue, dedicated to this function.
2. Binds this queue to the `facts` exchange, based on the routing key pattern provided by the `:reg` map.
3. Subscribes to this queue using a function that wraps the given function."
(fact
 (register-func {:chan ..chan..} #'my-service) => nil
 (provided
  (lq/declare-server-named ..chan..) => ..q..
  (lq/bind ..chan.. ..q.. facts-exch {:routing-key "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.#"}) => irrelevant
  (lc/subscribe ..chan.. ..q.. irrelevant {:auto-ack true}) => irrelevant))

"Auto acknowledgement is disabled when the provided function has three parameters.
The third of which is expected to be bound to an explicit `ack` function."
(fact
 (defn my-service-1
   {:reg {:kind :fact
          :name "foo/bar"}}
   [event publish ack])
 (register-func {:chan ..chan..} #'my-service-1) => nil
 (provided
  (lq/declare-server-named ..chan..) => ..q..
  (lq/bind ..chan.. ..q.. facts-exch {:routing-key "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.#"}) => irrelevant
  (lc/subscribe ..chan.. ..q.. irrelevant {:auto-ack false}) => irrelevant))

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
                     :readers #{}}) => "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.7110eda4d09e062aa5e4a390b0a572ac0d2c0220")

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


[[:section {:title "handle-event"}]]
"`handle-event` takes a function to be called when an event arrives along with arguments provided by
a subscription to a queue, so that the function `(partial handle-event some-func)` can be used with `lc/subscribe`."

"The wrapped function can take one, two, or three parameters.
If it takes one parameter, we pass this parameter a de-serialization of the event."
(fact
 (def received (atom '()))
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev]
   (swap! received #(conj % ev)))
 (def my-event {:kind :fact
                :name "foo/bar"
                :key 1234
                :ts 1000
                :data ["foo" :bar]
                :change 1
                :writers #{}
                :readers #{}})
 (def my-event-bin (nippy/freeze my-event))
 ; By running handle-event...
 (handle-event #'my-func :the-channel {:delivery-tag 777} my-event-bin) => nil
 ; we execute my-func, which adds the event to received
 @received => [my-event])

"If the wrapped function accepts two parameters, the second parameter is taken to be a `publish` function, which publishes events on the facts exchange."
(fact
 (def event2 {:kind :fact
              :name "foo/baz"
              :key 5555
              :data [1 2 3 4]})
 (def event2-bin (nippy/freeze event2))
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev publish]
   (publish event2))
 (handle-event #'my-func :the-channel :meta-attrs my-event-bin) => nil
 (provided
  (lb/publish :the-channel facts-exch (event-routing-key event2) irrelevant :meta-attrs) => irrelevant))

"If the wrapped function accepts three parameters, the third parameter is taken to be an `ack` function, that explicitly acknowledges the received event."
(fact
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev publish ack]
   (ack))
 (handle-event #'my-func :the-channel {:delivery-tag "foo"} my-event-bin) => nil
 (provided
  (lb/ack :the-channel "foo") => irrelevant))
