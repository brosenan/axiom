(ns axiom-cljs.core-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [axiom-cljs.core :as ax]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]
                   [axiom-cljs.macros :refer [defview]]))

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

[[:chapter {:title "defview"}]]
"The `defview` macro defines a *view*, which is a data structure that aggregates events and allows query over these events."

"`defview` four positional parameters:
1. The name of the view (a symbol).  This becomes the name of a function that retrieves data from that view.
2. An argument list for the view (a vector).  This becomes the argument list for the function.
3. A [connection](#connection) object.
4. A pattern of a fact or clause to be queried by this view."
(fact defview-1
      (defonce host {:to-host (async/chan 10)
                     :pub (async/pub (async/chan 1) :foo)})
      (defview my-tweets [user]
        host
        [:tweetlog/tweeted user tweet]))

"The defined view is a function with the parameters defined in the view definition."
(fact defview-2
      (is (fn? my-tweets)))

"When called for the first time, the function will send a `:reg` event with key according to the parameters,
and return an empty sequence with a meta field `:pending` indicating that the function should be consulted again later."
(fact defview-3
      (async done
             (go
               (let [res (my-tweets "alice")]
                 (is (= res []))
                 (is (= (-> res meta :pending) true))
                 (is (= (async/<! (:to-host host)) {:kind :reg
                                                    :name "tweetlog/tweeted"
                                                    :key "alice"})))
               (done))))

"An optional keyword parameter `:store-in` takes an atom and initially `reset!`s is to an empty map."
(fact defview-4
      (defonce host2 (let [from-host (async/chan 10)]
                       {:from-host from-host
                        :pub (async/pub from-host :name)}))
      (defonce my-atom2 (atom nil))
      (defview my-tweets2 [user]
        host2
        [:tweetlog/tweeted user tweet]
        :store-in my-atom2)
      (is (map? @my-atom2)))

"When events are received from the host they are placed in this map.
This is a two-level map, where the first level of keys corresponds to the view argument vector,
mapping a set of results to each combination of arguments.
The second level of keys consists of the received [events](cloudlog-events.html#introduction), 
with the `:ts` and `:change` fields omitted.
The values are the accumulated `:change` of all matching events."
(fact defview-5
      (async done
             (go
               (reset! my-atom2 {})
               (async/>! (:from-host host2)
                         {:kind :fact
                          :name "tweetlog/tweeted"
                          :key "alice"
                          :data ["hello"]
                          :ts 1000
                          :change 1
                          :readers #{}
                          :writers #{"alice"}})
               (async/>! (:from-host host2)
                         {:kind :fact
                          :name "tweetlog/tweeted"
                          :key "alice"
                          :data ["world"]
                          :ts 2000
                          :change 1
                          :readers #{}
                          :writers #{"alice"}})
               (async/>! (:from-host host2)
                         {:kind :fact
                          :name "tweetlog/tweeted"
                          :key "alice"
                          :data ["world"]
                          :ts 3000
                          :change 1
                          :readers #{}
                          :writers #{"alice"}})
               (async/<! (async/timeout 1))
               (is (contains? @my-atom2 ["alice"]))
               (is (= (get-in @my-atom2 [["alice"]
                                         {:kind :fact
                                          :name "tweetlog/tweeted"
                                          :key "alice"
                                          :data ["hello"]
                                          :readers #{}
                                          :writers #{"alice"}}]) 1))
               (is (= (get-in @my-atom2 [["alice"]
                                         {:kind :fact
                                          :name "tweetlog/tweeted"
                                          :key "alice"
                                          :data ["world"]
                                          :readers #{}
                                          :writers #{"alice"}}]) 2))
               (done))))
