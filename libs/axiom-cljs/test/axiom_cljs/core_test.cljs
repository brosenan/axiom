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

"It takes a URL and a an optional keyword parameter `:ws-ch`, 
which defaults to a [function of the same name in the chord library](https://github.com/jarohen/chord#clojurescript).
It returns a map containing the following keys:
1. A `:sub` function for subscribing to events coming from the host.
2. A `:pub` function for publishing events.
3. A `:time` function which returns the current time in milliseconds.
4. A `:uuid` function which returns some universally-unique identifier."
(fact connection
  (async done
         (go
           (let [the-chan (async/chan 10)
                 mock-ws-ch (fn [url]
                              (is (= url "ws://some-url"))
                              (go
                                (async/>! the-chan {:kind :init
                                                    :foo :bar})
                                {:ws-channel the-chan}))
                 host (ax/connection "ws://some-url"
                                     :ws-ch mock-ws-ch)]
             (is (map? host))
             (is (fn? (:pub host)))
             (is (fn? (:sub host)))
             (let [test-chan (async/chan 10)]
               ((:sub host) "foo" #(go (async/>! test-chan %)))
               (async/>! the-chan {:name "bar" :some :event})
               (async/>! the-chan {:name "foo" :other :event})
               (is (= (async/<! test-chan) {:name "foo" :other :event}))
               ;; Since our mock `ws-ch` creates a normal channel, publishing into this channel
               ;; will be captured by subscribers
               ((:pub host) {:name "foo" :third :event})
               (is (= (async/<! test-chan) {:name "foo" :third :event})))

             (is (fn? (:time host)))
             (let [time-before ((:time host))]
               (async/<! (async/timeout 1))
               (is (< time-before ((:time host)))))

             (is (fn? (:uuid host)))
             (is (= (count ((:uuid host))) 36))
             (is (not= ((:uuid host)) ((:uuid host))))
             (done)))))

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
