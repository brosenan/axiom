(ns rabbit-microservices.core-test
  (:require [midje.sweet :refer :all]
            [rabbit-microservices.core :refer :all]
            [taoensso.nippy :as nippy])
  (:require [langohr.core      :as rmq]
            [langohr.channel   :as lch]
            [langohr.queue     :as lq]
            [langohr.consumers :as lc]
            [langohr.basic     :as lb]
            [langohr.exchange  :as le]
            [di.core :as di]
            [clojure.core.async :as async]))

[[:chapter {:title "Introduction"}]]
"`rabbit-microservices` is a library that supports the definition of microservices.
It [provides](di.html#provide) the `serve` function as a [resource](di.html).
In turn, the `serve` function registers given functions to a subset of the events published through AMQP.
It is provided a partial event, and calls the given function on any event that matches the given partial event."

"The event structure we refer to here is the one discussed in [cloudlog-events](cloudlog-events.html)."

[[:chapter {:title "serve: Register an Event Handler" :tag "serve"}]]
"`serve` is a [dependency-injection resource](di.html) which depends on two functions:
1. [declare-service](#declare-service), and
2. [assign-service](#assign-service)"

"We will consider the following service function for the examples below:"
(defn my-service [ev])

"It takes a service function and a partial event, and does the following:
1. Based on the name of the function, it creates a queue name and calls `declare-service` to declare it.
2. It then assigns the function to the service by calling `assign-service`."
(fact
 (let [calls (transient [])
       $ (di/injector {:declare-service (fn [key reg] (conj! calls [:declare-service key reg]))
                       :assign-service (fn [key func] (conj! calls [:assign-service key func]))})]
   (module $)
   (let [serve (di/wait-for $ serve)]
     (serve my-service {:partial :event}) => nil
     (persistent! calls) => [[:declare-service "rabbit-microservices.core-test/my-service" {:partial :event}]
                             [:assign-service "rabbit-microservices.core-test/my-service" my-service]])))


[[:chapter {:title "declare-service: Declare an AMQP Queue" :tag "declare-service"}]]
"`declare-service` depends on an `amqp-service` resource (which itself depends on `amqp-config`)."
(fact
 (let [$ (di/injector {:amqp-service {:chan :some-chan}})]
   (module $)
   (def declare-service (di/wait-for $ declare-service))))

"`declare-service` declares a queue and binds it to the `facts` exchange to receive events matching the given partial event."
(fact
 (declare-service "foobar" {:kind :fact
                            :name "foo/bar"}) => nil
 (provided
  (lq/declare :some-chan "foobar") => irrelevant
  (lq/bind :some-chan "foobar"
           facts-exch {:routing-key "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.#"}) => irrelevant))

[[:chapter {:title "assign-service: Register an Event Handler" :tag "assign-service"}]]
"`assign-service` depends on an `amqp-service`."
(fact
 (let [inj (di/injector {:amqp-service {:chan :some-chan}})]
   (module inj)
   (def assign-service (di/wait-for inj assign-service))))


"`assign-service` takes a key that was previously [declared](#declare-service) and a service function,
and subscribes to this queue using a function that wraps the given function."
(fact
 (assign-service "foobar" my-service) => nil
 (provided
  (lc/subscribe :some-chan "foobar" irrelevant {:auto-ack true}) => irrelevant))

"Auto acknowledgement is disabled when the provided function has three parameters.
The third of which is expected to be bound to an explicit `ack` function."
(defn my-other-service [event publish ack]) 
(fact
 (assign-service "foobar" my-other-service) => nil
 (provided
  (lc/subscribe :some-chan "foobar" irrelevant {:auto-ack false}) => irrelevant))

[[:chapter {:title "publish: Publish an Event from the Outside" :tag "publish"}]]
"`publish` is a service that allows its users to publish events from outside the context of a service.
It depends on `amqp-service`."
(fact
 (let [inj (di/injector {:amqp-service {:chan :some-chan}})]
   (module inj)
   (def publish (di/wait-for inj publish))))

(fact
 (publish ..ev..) => nil
 (provided
  (nippy/freeze ..ev..) => ..bin..
  (event-routing-key ..ev..) => ..routing-key..
  (lb/publish :some-chan facts-exch ..routing-key.. ..bin.. {}) => irrelevant))

[[:chapter {:title "Usage Example"}]]
"To demonstrate how the above functions work together we build a small usage example."

"In our example we have the following service functions:
1. Function `sum-foo` that sums the values in the data of events of name \"foo\" and emits events named \"foo-sum\".
2. Function `count-ev` that counts the events of the different names and emits events of type \"count\" (it does not count \"count\" events).
3. Function `foo-from-count` that listens on \"count\" events and emits corresponding \"foo\" events with the same value."

(fact
 :integ ; Integration test.  Does not run during continouts integration
 (def foo-sum (atom 0))
 (def ev-count (atom {}))
 (def inj (di/injector))
 (module inj)
 (async/go
   (di/with-dependencies inj [serve]
     (println "Registering services")
     (serve (fn ;; sum-foo
              [ev pub]
              (swap! foo-sum (partial + (:data ev)))
              (pub {:kind :fact
                    :name "foo-sum"
                    :key 0
                    :data @foo-sum}))
            {:kind :fact
             :name "foo"})

     (serve (fn  ;; count-ev
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
            {:kind :fact})

     (serve (fn ;; foo-from-count
              [ev pub]
              (pub {:kind :fact
                    :name "foo"
                    :key (:key ev)
                    :data (:data ev)}))
            {:kind :fact
             :name "count"}))))

"For the `serve` resource to initialize, we need to provide an `amqp-config` resource with values needed for the underlying `langohr` library
to find the AMQP broker.
We will use the default values."
(fact
 :integ
 (di/provide amqp-config inj
             rmq/*default-config*))

"Now our service is ready to roll.  
All we need is to get the fire started is a little match -- a single event."
(fact :integ ; Integration test.  Does not run during continouts integration
      (async/go
        (di/with-dependencies inj [publish]
          (println "Kicking the first event")
          (publish {:kind :fact
                    :name "foo"
                    :key 0
                    :data 1})))
      (println "waiting for the sum to reach ten-milion")
      (while (< @foo-sum 10000000)
        (Thread/sleep 100))
      (println "done!"))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "create-service: Initialization" :tag "create-service"}]]
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
 (handle-event my-func alive :the-channel {:delivery-tag 777} my-event-bin) => nil
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
 (handle-event my-func alive :the-channel :meta-attrs my-event-bin) => nil
 (provided
  (nippy/freeze event-that-was-sent) => ..bin..
  (lb/publish :the-channel facts-exch (event-routing-key event2) ..bin.. :meta-attrs) => irrelevant))

"When the `alive` atom evaluates to `false`, the publish function does nothing."
(fact
 (reset! alive false)
 (handle-event my-func alive :the-channel :meta-attrs my-event-bin) => nil
 ; Nothing is published
 )
"If the wrapped function accepts three parameters, the third parameter is taken to be an `ack` function, that explicitly acknowledges the received event."
(fact
 (defn my-func
   {:reg {:kind :fact
          :name "foo/bar"}}
   [ev publish ack]
   (ack))
 (handle-event my-func alive :the-channel {:delivery-tag "foo"} my-event-bin) => nil
 (provided
  (lb/ack :the-channel "foo") => irrelevant))

