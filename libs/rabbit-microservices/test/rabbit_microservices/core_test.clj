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
