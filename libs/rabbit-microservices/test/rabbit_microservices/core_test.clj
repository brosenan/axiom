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
   (di/startup $)
   (di/do-with! $ [serve]
     (serve my-service {:partial :event}) => nil
     (persistent! calls)
     => [[:declare-service "rabbit-microservices.core-test/my-service" {:partial :event}]
         [:assign-service "rabbit-microservices.core-test/my-service" my-service]])))


[[:chapter {:title "declare-service: Declare an AMQP Queue" :tag "declare-service"}]]
"`declare-service` depends on an `rabbitmq-service` resource (which itself depends on `rabbitmq-config`)."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (def declare-service (di/do-with! $ [declare-service] declare-service))))

"`declare-service` declares a queue and binds it to the `facts` exchange to receive events matching the given partial event."
(fact
 (declare-service "foobar" {:kind :fact
                            :name "foo/bar"}) => nil
 (provided
  (lq/declare :some-chan "foobar" {:durable true}) => irrelevant
  (lq/bind :some-chan "foobar"
           facts-exch {:routing-key "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.#"})
  => irrelevant))

[[:section {:title "declare-volatile-service"}]]
"Sometimes we wish services to exist only temporarily.
We set them up for a particular reason, and when that reason no longer exists we have no use for this service.
`declare-volatile-service` creates a non-durable AMQP queue, so it is removed after a broker restart."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (di/do-with! $ [declare-volatile-service]
                (declare-volatile-service "foobar" {:kind :fact
                                                    :name "foo/bar"}) => nil
                (provided
                 (lq/declare :some-chan "foobar" {:durable false}) => irrelevant
                 (lq/bind :some-chan "foobar"
                          facts-exch {:routing-key "f.17cdeaefa5cc6022481c824e15a47a7726f593dd.#"})
                 => irrelevant))))

[[:chapter {:title "assign-service: Register an Event Handler" :tag "assign-service"}]]
"`assign-service` depends on an `rabbitmq-service`."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (def assign-service (di/do-with! $ [assign-service] assign-service))))


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

"The `lc/subscribe` function is given a closure returned by [handle-event](#handle-event).
Please refer to it for more information."

[[:chapter {:title "publish: Publish an Event from the Outside" :tag "publish"}]]
"`publish` is a service that allows its users to publish events from outside the context of a service.
It depends on `rabbitmq-service` for publishing, and `time` for filling in the `:ts` field if one is not specified."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}
                       :time (constantly 12345)})]
   (module $)
   (di/startup $)
   (def publish (di/do-with! $ [publish] publish))))

(fact
 (publish {:some :event}) => nil
 (provided
  (nippy/freeze {:some :event
                 :ts 12345
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (event-routing-key {:some :event}) => ..routing-key..
  (lb/publish :some-chan facts-exch ..routing-key.. ..bin.. {}) => irrelevant))

[[:chapter {:title "poll-events: Pull Mode" :tag "poll-events"}]]
"The most efficient and conveinent way to receive messages from a queue is in *push mode*, where
the AMQP broker has control.
In this approach (as implemented by [assign-service](#assign-service)), we implement a function that is called whenever a new event arrives."

"However, sometimes we need to work the other way.
We need to have control, and consume messages off a queue when *we* decide to do so."

"An example for such a situation is where we respond to a user request.
In such a case, without the user request we have nothing to do with the data, 
so it does not make sense to have a service function handling all messages.
When a user request comes in, we wish to read all messages currently on the queue and return them to the user.
This is exactly what the `poll-events` function does."

"`poll-events` is a DI resource which is based on `rabbitmq-service`."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (di/do-with! $ [poll-events]
                (def poll-events poll-events))))

"When called, it calls [lb/get](http://reference.clojurerabbitmq.info/langohr.basic.html#var-get) to read events off the given queue.
It reads all events until the queue is empty.
Events are auto-acknowledged."
(fact
 (poll-events ..queue..) => [..ev1.. ..ev2..]
 (provided
  (lb/get :some-chan ..queue.. true) =streams=> [[..metadata.. ..payload1..]
                                                 [..metadata.. ..payload2..]
                                                 nil]
  (nippy/thaw ..payload1..) => ..ev1..
  (nippy/thaw ..payload2..) => ..ev2..))

[[:chapter {:title "event-bridge"}]]
"An `event-bridge` is a bidirectional bridge between events flowing globally through Axiom (as AMQP messages),
and local events flowing through `core.async` channels.
It is intended to connect Axiom (the server) to an external client."

"It takes a pair of channels named `c2s` (client to server) and `s2c` (server to client).
It does the following:
1. [publish](#publish) `:fact` events flowing on the `c2s` channel.
2. Handle `:reg` events by registering to `:fact` events they refer to, and
3. Push all registered events to the `s2c` channel."

"`event-bridge` is a DI resource with the following dependencies:
1. [publish](#publish), used to publish events coming on `c2s`.
2. [declare-private-queue](#declare-private-queue), used to create a private queue for this session.
3. [assign-service](#assign-service), used to assign a function that will push incoming events to the `s2c` channel.
4. `database-chan` (e.g., [this](dynamo.html#database-chan)), to retrieve any existing events, if so requested during registration.
5. [register-events-to-queue](#register-events-to-queue), used to direct events of interest to the session's queue, and
6. [delete-queue](#delete-queue), used to close the session queue once the `c2s` channel closes."
(fact
 (def calls (async/chan 10))
 (defn get-call []
   (let [[call chan] (async/alts!! [calls
                                    (async/timeout 1000)])]
     (when (not= chan calls)
       (throw (Exception. "Timed out waiting to read call")))
     call))
 (def database-chan (async/chan 10))
 
 (let [$ (di/injector {:publish
                       (fn [ev] (async/>!! calls [:publish ev]))
                       :declare-private-queue
                       (fn [] (async/>!! calls [:declare-private-queue])
                         "some-random-queue")
                       :assign-service
                       (fn [queue func] (async/>!! calls [:assign-service queue func]))
                       :database-chan
                       database-chan
                       :register-events-to-queue
                       (fn [queue reg] (async/>!! calls [:register-events-to-queue queue reg]))
                       :delete-queue
                       (fn [queue] (async/>!! calls [:delete-queue queue]))})]
   (module $)
   (di/startup $)
   (di/do-with! $ [event-bridge]
                (def event-bridge event-bridge))))

"To create a session, `event-bridge` is called with a pair `[c2s s2c]` of channels."
(fact
 (def chan-pair [(async/chan 100) (async/chan 100)])
 (event-bridge chan-pair) => nil)

"The first thing `event-bridge` does is declare a private queue, and assigns a handler to it."
(fact
 (get-call) => [:declare-private-queue]
 (let [[assign queue func] (get-call)]
   assign => :assign-service
   queue => "some-random-queue"
   (def incoming-handler func)))

"Pushing a `:fact` event to the `c2s` channel will cause it to be published."
(fact
 (let [[c2s s2c] chan-pair]
   (async/>!! c2s {:kind :fact
                   :name "foo"
                   :key "bar"
                   :data [1 2]})
   (async/>!! c2s {:kind :fact
                   :name "foo"
                   :key "baz"
                   :data [2 3]})
   (get-call) => [:publish {:kind :fact
                            :name "foo"
                            :key "bar"
                            :data [1 2]}]
   (get-call) => [:publish {:kind :fact
                            :name "foo"
                            :key "baz"
                            :data [2 3]}]))

"Pushing a `:reg` event will cause it to register to a corresponding `:fact` event."
(fact
 (let [[c2s s2c] chan-pair]
   (async/>!! c2s {:kind :reg
                   :name "x"
                   :key "y"})
   (get-call) => [:register-events-to-queue "some-random-queue" {:kind :fact
                                                                 :name "x"
                                                                 :key "y"}]))

"When an event is received, it is pushed to the `s2c` channel."
(fact
 (let [[c2s s2c] chan-pair]
   (incoming-handler {:kind :fact
                      :name "boo"
                      :key "tar"
                      :data [1 2]})
   (let [[pushed chan] (async/alts!! [s2c
                                      (async/timeout 1000)])]
     chan => s2c
     pushed => {:kind :fact
                :name "boo"
                :key "tar"
                :data [1 2]})))

"A `:reg` event may contain a `:get-existing` field.
If such a field exists and is `true`, a request is made to the database (through `database-chan`) to retrieve all existing events matching the registration."
(fact
 (let [[c2s s2c] chan-pair]
   (async/>!! c2s {:kind :reg
                   :name "x"
                   :key "y"
                   :get-existing true})
   ;; Registration is the same
   (get-call) => [:register-events-to-queue "some-random-queue" {:kind :fact
                                                                 :name "x"
                                                                 :key "y"}]
   (let [[[query reply-chan] chan] (async/alts!! [database-chan
                                                  (async/timeout 1000)])]
     chan => database-chan
     (def reply-chan reply-chan))))

"Results returned from the database are pushed to the `s2c` channel."
(fact
 (let [[c2s s2c] chan-pair]
   (async/>!! reply-chan {:kind :fact
                          :name "some"
                          :key "event"
                          :data [1 2 3]})
   (async/>!! reply-chan {:kind :fact
                          :name "some"
                          :key "other event"
                          :data [2 3 4]})
   (async/close! reply-chan)
   (let [[ev chan] (async/alts!! [s2c
                                  (async/timeout 1000)])]
     chan => s2c
     ev => {:kind :fact
            :name "some"
            :key "event"
            :data [1 2 3]})
   (let [[ev chan] (async/alts!! [s2c
                                  (async/timeout 1000)])]
     chan => s2c
     ev => {:kind :fact
            :name "some"
            :key "other event"
            :data [2 3 4]})))


"When the `c2s` channel is closed, `event-bridge` deletes its queue."
(fact
 (let [[c2s s2c] chan-pair]
   (async/close! c2s)
   (get-call) => [:delete-queue "some-random-queue"]))

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
 (def $ (di/injector))
 (module $)
 (di/do-with $ [serve]
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
           :name "count"})))

"For the `serve` resource to initialize, we need to provide an `rabbitmq-config` resource with values needed for the underlying `langohr` library
to find the AMQP broker.
We will use the default values."
(fact
 :integ
 (di/provide $ rabbitmq-config []
             rmq/*default-config*)
 (di/startup $))

"Now our service is ready to roll.  
All we need is to get the fire started is a little match -- a single event."
(fact :integ ; Integration test.  Does not run during continouts integration
      (di/do-with! $ [publish]
                   (println "Kicking the first event")
                   (publish {:kind :fact
                             :name "foo"
                             :key 0
                             :data 1}))
      (println "waiting for the sum to reach ten-milion")
      (while (< @foo-sum 10000000)
        (Thread/sleep 100))
      (println "done!"))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "rabbitmq-service"}]]
"`rabbitmq-service` is a DI resource that represents a running RabbitMQ broker.
It is a map containing the following properties:
- `:conn` -- a connection object to a server.
- `:chan` -- an open channel.
- `:alive` -- an atom holding a Boolean value used for shutting down the conection."

"Initialization also declares the `facts` exchange, which is a topic-based AMQP exchange."
(fact
 (let [$ (di/injector {:rabbitmq-config rmq/*default-config*})]
   (module $)
   (di/startup $)
   (di/do-with! $ [rabbitmq-service]
                [(:conn rabbitmq-service)
                 (:chan rabbitmq-service)
                 @(:alive rabbitmq-service)])) => [..conn.. ..chan.. true]
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
 (defn my-func [ev]
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
 (handle-event my-func alive constantly :the-channel {:delivery-tag 777} my-event-bin) => nil
 ; we execute my-func, which adds the event to received
 @received => [my-event])

"If the wrapped function accepts two parameters, the second parameter is taken to be a `publish` function, which publishes events on the facts exchange.
The event handler (`my-func` in the example below) does not have to specify all fields in the event it publishes (`event2` in the example).
All fields that are not specified in the event default to their values in the event that triggerred the function (`event-that-was-sent` in the example)."
(fact
 (def event2 {:name "foo/baz"
              :key 5555
              :data [1 2 3 4]})
 (defn my-func [ev publish]
   (publish event2))
 (def event-that-was-sent
   (merge my-event event2))
 (handle-event my-func alive constantly :the-channel :meta-attrs my-event-bin) => nil
 (provided
  (nippy/freeze event-that-was-sent) => ..bin..
  (lb/publish :the-channel facts-exch (event-routing-key event2) ..bin.. :meta-attrs) => irrelevant))

"When the `alive` atom evaluates to `false`, the publish function does nothing."
(fact
 (reset! alive false)
 (handle-event my-func alive constantly :the-channel :meta-attrs my-event-bin) => nil
 ; Nothing is published
 )
"If the wrapped function accepts three parameters, the third parameter is taken to be an `ack` function, that explicitly acknowledges the received event."
(fact
 (defn my-func [ev publish ack]
   (ack))
 (handle-event my-func alive constantly :the-channel {:delivery-tag "foo"} my-event-bin) => nil
 (provided
  (lb/ack :the-channel "foo") => irrelevant))

[[:section {:title "declare-private-queue"}]]
"`declare-private-queue` is made to allow the creation of private queues, to be used not for a service, but rather for a specific user session.
Unlike [declare-service](#declare-service), it does not bind an exchange to this queue."

"It is a DI resource, based on `rabbitmq-service`."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (di/do-with! $ [declare-private-queue]
                (def declare-private-queue declare-private-queue))))

"It takes no parameters, and calls [lq/declare-server-named](http://reference.clojurerabbitmq.info/langohr.queue.html#var-declare-server-named)
to create a new queue, named by the AMQP broker, exclusive to this connection, non-durable and auto-deletable."
(fact
 (declare-private-queue) => ..queue..
 (provided
  (lq/declare-server-named :some-chan {:exclusive true}) => ..queue..))

[[:section {:title "delete-queue"}]]
"`delete-queue` deletes a queue of a given name."

"It is a DI resource that depends on `rabbitmq-service`."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (di/do-with! $ [delete-queue]
                (def delete-queue delete-queue))))

"It takes one parameter -- the name of the queue to be deleted, and calls `lq/delete` with that name."
(fact
 (delete-queue ..queue..) => nil
 (provided
  (lq/delete :some-chan ..queue..) => irrelevant))

[[:section {:title "register-events-to-queue"}]]
"`register-events-to-queue` takes a queue name and a partial events,
and makes sure events matching this partial event are sent to this queue."

"It is a DI resource based on `rabbitmq-service`."
(fact
 (let [$ (di/injector {:rabbitmq-service {:chan :some-chan}})]
   (module $)
   (di/startup $)
   (di/do-with! $ [register-events-to-queue]
                (def register-events-to-queue register-events-to-queue))))

"When called, it calls [event-routing-key](#event-routing-key) to convert the given partial event to an AMQP routing key.
Then it calls [lq/bind](http://reference.clojurerabbitmq.info/langohr.queue.html#var-bind) to make sure matching events are placed in the given queue."
(fact
 (register-events-to-queue ..queue.. ..partial..) => nil
 (provided
  (event-routing-key ..partial..) => ..routing-key..
  (lq/bind :some-chan ..queue.. facts-exch {:routing-key ..routing-key..}) => irrelevant))
