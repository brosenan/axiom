(ns rabbit-microservices.core-test
  (:require [midje.sweet :refer :all]
            [rabbit-microservices.core :refer :all]
            [taoensso.nippy :as nippy])
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [langohr.exchange  :as le]))

[[:chapter {:title "Introduction"}]]
"`rabbit-microservices` is a library that supports the definition of microservices.
It [provides](di.html#provide) the `serve` function as a [resource](di.html).
In turn, the `serve` function registers given functions to a subset of the events published through AMQP.
It is provided a partial event, and calls the given function on any event that matches the given partial event."

"The event structure we refer to here is the one discussed in [cloudlog-events](cloudlog-events.html)."

[[:chapter {:title "create-service: Initialization" :tag "create-service"}]]
"`create-service` initializes a connection and a channel to RabbitMQ.
It also declares the `facts` exchange, which is a topic-based AMQP exchange.
The returned map contains the connection, the channel and an `:alive` atom, that defaults to `true`."
(fact
 (let [srv (create-service)]
   [(:conn srv) (:chan srv) @(:alive srv)]) => [..conn.. ..chan.. true]
 (provided
  (rmq/connect) => ..conn..
  (lch/open ..conn..) => ..chan..
  (le/declare ..chan.. facts-exch "topic") => irrelevant))

"The dynamic variable `langohr.core/*default-config*` controls the configuration.  See the [Langohr documentation](http://clojurerabbitmq.info) for more information."

[[:chapter {:title "shut-down-service: Cleanup" :tag "shut-down-service"}]]
"`shut-down-service` closes the channel and the connection to RabbitMQ.
To indicate to publishers that the channel is close it `reset!`s the `:alive` atom to `false`."
(fact
 (shut-down-service {:conn ..conn..
                     :chan ..chan..
                     :alive ..alive..}) => nil
 (provided
  (reset! ..alive.. false) => irrelevant
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

[[:chapter {:title "register-service: Register All Functions in a Module" :tag "register-service"}]]
"`register-service` takes a service and a namespace, and registers to that service all the public functions in the namespace
that have `:reg` meta field attached to them."
(fact
 (register-service ..srv.. ..ns..) => nil
 (provided
  (ns-publics ..ns..) => {'my-service #'my-service
                          'map #'map} ; A function that does not have :reg meta
  (register-func ..srv.. #'my-service) => nil))

[[:chapter {:title "publish: Publish an Event from the Outside"}]]
"The second parameter of a service function is bound to a `publish` function, allowing services to publish events.
However, we sometime need to publish events outside the context of a service (i.e., not in response to an incoming event).
The (global) `publish` function does that.
The only difference between the two functions is that the global `publish` receives the `service` as its first parameter."
(fact
 (publish {:chan ..chan..} ..ev..) => nil
 (provided
  (nippy/freeze ..ev..) => ..bin..
  (event-routing-key ..ev..) => ..routing-key..
  (lb/publish ..chan.. facts-exch ..routing-key.. ..bin.. {}) => irrelevant))

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
"`handle-event` takes a function to be called when an event arrives and the `:alive` atom for the service
 along with arguments provided by a subscription to a queue, 
so that the function `(partial handle-event some-func alive)` can be used with `lc/subscribe`."

"The wrapped function can take one, two, or three parameters.
If it takes one parameter, we pass this parameter a de-serialization of the event."
(fact
 (def alive (atom true))
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
 (handle-event #'my-func alive :the-channel {:delivery-tag 777} my-event-bin) => nil
 ; we execute my-func, which adds the event to received
 @received => [my-event])

"If the wrapped function accepts two parameters, the second parameter is taken to be a `publish` function, which publishes events on the facts exchange.
The event handler (`my-func` in the example below) does not have to specify all fields in the event it publishes (`event2` in the example).
All fields that are not specified in the event default to their values in the event that triggerred the function (`event-that-was-sent` in the example)."
(fact
 (def event2 {:name "foo/baz"
              :key 5555
              :data [1 2 3 4]})
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev publish]
   (publish event2))
 (def event-that-was-sent
   (merge my-event event2))
 (handle-event #'my-func alive :the-channel :meta-attrs my-event-bin) => nil
 (provided
  (nippy/freeze event-that-was-sent) => ..bin..
  (lb/publish :the-channel facts-exch (event-routing-key event2) ..bin.. :meta-attrs) => irrelevant))

"When the `alive` atom evaluates to `false`, the publish function does nothing."
(fact
 (reset! alive false)
 (handle-event #'my-func alive :the-channel :meta-attrs my-event-bin) => nil
 ; Nothing is published
 )
"If the wrapped function accepts three parameters, the third parameter is taken to be an `ack` function, that explicitly acknowledges the received event."
(fact
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev publish ack]
   (ack))
 (handle-event #'my-func alive :the-channel {:delivery-tag "foo"} my-event-bin) => nil
 (provided
  (lb/ack :the-channel "foo") => irrelevant))

[[:chapter {:title "Integration Testing"}]]
"To demonstrate how the above functions work together we build a small usage example.
In our example we have the following service functions:
1. Function `sum-foo` that sums the values in the data of events of name \"foo\" and emits events named \"foo-sum\".
2. Function `count-ev` that counts the events of the different names and emits events of type \"count\" (it does not count \"count\" events).
3. Function `foo-from-count` that listens on \"count\" events and emits corresponding \"foo\" events with the same value."

(def foo-sum (atom 0))
(defn sum-foo
  {:reg {:kind :fact
         :name "foo"}}
  [ev pub]
  (swap! foo-sum (partial + (:data ev)))
  (pub {:kind :fact
        :name "foo-sum"
        :key 0
        :data @foo-sum}))

(def ev-count (atom {}))
(defn count-ev
  {:reg {:kind :fact}}
  [ev pub]
  (let [key (:name ev)]
    (swap! ev-count (fn [m]
                      (let [old (or (m key) 0)
                            new (inc old)]
                        (assoc m key new))))
    (pub {:kind :fact
          :name "count"
          :key key
          :data (@ev-count key)})))


(defn foo-from-count
  {:reg {:kind :fact
         :name "count"}}
  [ev pub]
  (pub {:kind :fact
        :name "foo"
        :key (:key ev)
        :data (:data ev)}))

"We create a service based on this module."
(fact :integ ; Integration test.  Does not run during continouts integration
      (def service (create-service))
      (register-service service 'rabbit-microservices.core-test))

"Now our service is ready to roll.  
All we need is to get the fire started is a little match -- a single event."
(fact :integ ; Integration test.  Does not run during continouts integration
      (publish service {:kind :fact
                        :name "foo"
                        :key 0
                        :data 1})
      (println "waiting for the sum to reach ten-milion")
      (while (< @foo-sum 10000000)
        (Thread/sleep 100))
      (println "done!")
      (shut-down-service service))
