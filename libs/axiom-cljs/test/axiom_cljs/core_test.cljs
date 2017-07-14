(ns axiom-cljs.core-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [axiom-cljs.core :as ax]
            [cljs.core.async :as async]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]))

(enable-console-print!)

[[:chapter {:title "connection"}]]
"`connection` creates a connection to the host."

"It takes an optional keyword parameter `:ws-ch`, which defaults to a [function of the same name in the chord library](https://github.com/jarohen/chord#clojurescript).
It returns a channel to which it will provide a map containing the following keys:
1. `:from-host`: A `core.async` channel for delivering events from the host,
2. `:to-host`: A `core.async` channel for delivering events to the host,
3. `:err`: An `atom` holding the latest error, or `nil` if communication is intact.
4. `:pub`: A `core.async` [pub](https://github.com/clojure/core.async/wiki/Pub-Sub), dispatching on `:name`.
Additionally, it contains the fields of the `:init` event it receives from the server-side once the connection is open."
(fact connection
  (async done
         (go
           (let [mock-ws-ch (fn [url]
                              (go
                                (let [ch (async/chan 1)]
                                  (async/>! ch {:kind :init
                                                :foo :bar})
                                  {:ws-channel ch})))
                 target-chan (async/chan 10)
                 host (async/<! (ax/connection :ws-ch mock-ws-ch))]
             (is (map? host))
             (is (contains? host :from-host))
             (is (contains? host :to-host))
             (is (= (:foo host) :bar))
             (async/sub (:pub host) "foo" target-chan)
             ;; The mock provides one channel that is used for both
             ;; :from-host and :to-host comms.
             (async/>! (:to-host host) {:name "foo"
                                        :key "key"})
             ;; :pub is subscribed on :from-host
             (is (= (async/<! target-chan) {:name "foo"
                                            :key "key"})))
           (done))))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "pubsub"}]]
"`pubsub` is a simple synchronous publish/subscribe mechanism.
The `pubsub` function takes a dispatch function as parameter, and returns a map containing two functions: `:sub` and `:pub`."
(fact pubsub-1
      (let [ps (ax/pubsub (fn [x]))]
        (is (fn? (:pub ps)))
        (is (fn? (:sub ps)))))

"When calling the `:pub` function with a value, the dispatch function is called with that value."
(fact pubsub-2
      (let [val (atom nil)
            ps (ax/pubsub (fn [x]
                            (reset! val x)))]
        ((:pub ps) [1 2 3])
        (is (= @val [1 2 3]))))

"The `:sub` function takes a dispatch value, and a function.
When the dispatch function returns that dispatch value, the `:sub`scribed function is called with the `:pub`lished value."
(fact pubsub-3
      (let [val (atom nil)
            ps (ax/pubsub :name)]
        ((:sub ps) "alice" (partial reset! val))
        ((:pub ps) {:name "alice" :age 28})
        ((:pub ps) {:name "bob" :age 31})
        (is (= @val {:name "alice" :age 28}))))
