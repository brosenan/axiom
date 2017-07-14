(ns axiom-cljs.core-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [axiom-cljs.core :as ax]
            [cljs.core.async :as async]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]
                   [axiom-cljs.macros :refer [defview defquery]]))

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
                        :to-host (async/chan 10)
                        :pub (async/pub from-host :name)
                        :time (constantly 12345)
                        :identity "alice"}))
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

"The view function returns, for a given combination of arguments, a collection of all data elements with a positive total count.
In our case, both \"hello\" and \"world\" tweets are taken (in some order)."
(fact defview-6
      (is (= (count (my-tweets2 "alice")) 2))
      (is (= (-> (my-tweets2 "alice")
                 meta :pending) false)))

"Each element in the returned collection is a *value map*, a map in which the keys correspond to the symbols in the fact pattern provided in the view definition.
In our case these are `:user` and `:tweet`.
The values are their corresponding values in each event."
(fact defview-7a
      (doseq [result (my-tweets2 "alice")]
        (is (= (:user result) "alice"))
        (is (contains? #{"hello" "world"} (:tweet result)))))

"Along with the data fields, each value map also contains the `:-readers` and `:-writers` of the corresponding events."
(fact defview-7b
      (doseq [result (my-tweets2 "alice")]
        (is (= (:-readers result) #{}))
        (is (= (:-writers result) #{"alice"}))))

"Elements with zero or negative count values are not shown."
(fact defview-8
      (reset! my-atom2 {})
      (swap! my-atom2 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/tweeted"
                                 :key "alice"
                                 :data ["hello"]
                                 :readers #{}
                                 :writers #{"alice"}}] 0)
      (swap! my-atom2 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/tweeted"
                                 :key "alice"
                                 :data ["world"]
                                 :readers #{}
                                 :writers #{"alice"}}] -1)
      (is (= (count (my-tweets2 "alice")) 0)))

[[:section {:title "Event-Emitting Functions"}]]
"A view provides functions that allow users to emit event for creating, updating and deleting facts."

"For *creating facts*, a non-pending sequence returned by a view function has an `:add` meta-field.
This is a function that takes a value map as input, and emits a corresponding event with the following keys:
1. `:kind` is always `:fact`.
2. `:name` is the first element in the fact pattern, converted to string.
3. `:key` and `:data` are derived from the value map.
4. `:ts` consults the host's `:time` function.
5. `:change` is always 1.
6. `:writers` defaults to the set representing the user.
7. `:readers` defaults to the universal set."
(fact defview-ev-1
      (async done
             (go
               (let [add (-> (my-tweets2 "alice")
                             meta :add)]
                 (is (fn? add))
                 (add {:user "alice"
                       :tweet "Hola!"})
                 (is (= (async/<! (:to-host host2))
                        {:kind :fact
                         :name "tweetlog/tweeted"
                         :key "alice"
                         :data ["Hola!"]
                         :ts 12345
                         :change 1
                         :writers #{"alice"}
                         :readers #{}})))
               (done))))

"In value maps where the user is a writer, a `:-delete!` entry contains a function that deletes the corresponding fact.
It creates an event with all the same values, but with a new `:ts` and a `:change` value equal to the negative of the count value of the original event."
(fact defview-ev-2
      (async done
             (defonce my-atom3 (atom nil))
             (let [from-host (async/chan 10)]
               (defonce host3 {:to-host (async/chan 10)
                               :pub (async/pub from-host :name)
                               :time (constantly 23456)}))
             (defview my-tweets3 [user]
               host3
               [:tweetlog/tweeted user tweet]
               :store-in my-atom3)
             (go
               (swap! my-atom3 assoc-in [["alice"]
                                         {:kind :fact
                                          :name "tweetlog/tweeted"
                                          :key "alice"
                                          :data ["hello"]
                                          :readers #{}
                                          :writers #{"alice"}}] 3)
               (let [valmap (first (my-tweets3 "alice"))]
                 (is (fn? (:-delete! valmap)))
                 ((:-delete! valmap))
                 (is (= (async/<! (:to-host host3))
                        {:kind :fact
                         :name "tweetlog/tweeted"
                         :key "alice"
                         :data ["hello"]
                         :ts 23456
                         :change -3
                         :readers #{}
                         :writers #{"alice"}})))
               (done))))

"A `:-swap!` function provides a way to update a value map.
It takes a function (and optionally arguments) that is applied to the value map, and emits an [atomic update](cloudlog-events.html#atomic-updates)
event from the original state to the state reflected by the modified value map."
(fact defview-ev-3
      (async done
             (defonce my-atom4 (atom nil))
             (let [from-host (async/chan 10)]
               (defonce host4 {:to-host (async/chan 10)
                               :pub (async/pub from-host :name)
                               :time (constantly 34567)}))
             (defview my-tweets4 [user]
               host4
               [:tweetlog/tweeted user tweet]
               :store-in my-atom4)
             (go
               (swap! my-atom4 assoc-in [["alice"]
                                         {:kind :fact
                                          :name "tweetlog/tweeted"
                                          :key "alice"
                                          :data ["hello"]
                                          :readers #{}
                                          :writers #{"alice"}}] 3)
               (let [valmap (first (my-tweets4 "alice"))]
                 (is (fn? (:-swap! valmap)))
                 ((:-swap! valmap) assoc :tweet "world")
                 (is (= (async/<! (:to-host host4))
                        {:kind :fact
                         :name "tweetlog/tweeted"
                         :key "alice"
                         :removed ["hello"]
                         :data ["world"]
                         :ts 34567
                         :change 3
                         :readers #{}
                         :writers #{"alice"}})))
               (done))))

[[:section {:title "Filtering"}]]
"Views can define client-side filtering for facts."

"Imagine we are only interested in tweets that contain a hash-tag, i.e., tweets that match the regular expression `#\".*#[a-zA-Z0-9]+.*\"`.
We define such filtering using an optional `:when` key in `defview`."
(fact defview-filt
      (async done
             (defonce my-atom5 (atom nil))
             (defonce from-host5 (async/chan 10))
             (defonce host5 {:to-host (async/chan 10)
                             :pub (async/pub from-host5 :name)})
             (defview hashtags-only [user]
               host5
               [:tweetlog/tweeted user tweet]
               :store-in my-atom5
               :when (re-matches #".*#[a-zA-Z0-9]+.*" tweet))
             (reset! my-atom5 {})
             (go
               (async/>! from-host5
                         {:kind :fact
                          :name "tweetlog/tweeted"
                          :key "alice"
                          :data ["No hashtags here..."]
                          :ts 1000
                          :change 1
                          :readers #{}
                          :writers #{"alice"}})
               (async/>! from-host5
                         {:kind :fact
                          :name "tweetlog/tweeted"
                          :key "alice"
                          :data ["This one hash #hashtags..."]
                          :ts 2000
                          :change 1
                          :readers #{}
                          :writers #{"alice"}})
               (async/<! (async/timeout 1))
               (is (= (count (@my-atom5 ["alice"])) 1))
               (done))))

[[:section {:title "Sorting"}]]
"The optional keyword argument `:order-by` directs the view function to sort the elements it returns according to the given expression.
The expression can rely on symbols from the fact pattern, and must result in a [comparable expression](https://clojure.org/guides/comparators)."
(fact defview-sort
      (defonce my-atom6 (atom nil))
      (defonce from-host6 (async/chan 10))
      (defonce host6 {:to-host (async/chan 10)
                      :pub (async/pub from-host6 :name)})
      (defview my-sorted-tweets [user]
        host6
        [:tweetlog/tweeted user tweet timestamp]
        :store-in my-atom6
        :order-by (- timestamp) ;; Later tweets first
        )
      (reset! my-atom6 {})
      (swap! my-atom6 assoc-in
             [["alice"]
              {:kind :fact
               :name "tweetlog/tweeted"
               :key "alice"
               :data ["my first tweet" 1000]
               :readers #{}
               :writers #{"alice"}}] 1)
      (swap! my-atom6 assoc-in
             [["alice"]
              {:kind :fact
               :name "tweetlog/tweeted"
               :key "alice"
               :data ["my second tweet" 2000]
               :readers #{}
               :writers #{"alice"}}] 1)
      (swap! my-atom6 assoc-in
             [["alice"]
              {:kind :fact
               :name "tweetlog/tweeted"
               :key "alice"
               :data ["my third tweet" 3000]
               :readers #{}
               :writers #{"alice"}}] 1)
      (let [tweets-in-order (for [result (my-sorted-tweets "alice")]
                              (:tweet result))]
        (is (= tweets-in-order ["my third tweet"
                                "my second tweet"
                                "my first tweet"]))))

[[:chapter {:title "defquery"}]]
"`defquery` is similar to [defview](#defview), but instead of querying for facts, it queries for results contributed by [clauses](cloudlog.html#defclause)."

"This macro's structure is similar to `defview`, with one exception -- instead of providing a fact pattern, we provide a *predicate pattern*, of the form:"
(comment
  [:some/name input argument -> output arguments])

(fact defquery-1
      (defonce from-host7 (async/chan 10))
      (defonce unique7 (atom nil))
      (reset! unique7 0)
      (defonce host7 {:to-host (async/chan 10)
                      :pub (async/pub from-host7 :name)
                      :identity "alice"
                      :uuid (fn []
                              (str "SOMEUUID" (swap! unique7 inc)))
                      :time (constantly 12345)})
      (defonce my-atom7 (atom nil))
      (defquery my-query7 [user]
        host7
        [:tweetlog/timeline user -> author tweet]
        :store-in my-atom7 ;; Optional
        ))

"The optional `:store-in` arguement provides an atom in which received events are stored.
The structure is similar to that used by `defview`."
(fact defquery-2
      (is (map? @my-atom7)))

"As in `defview`, `defquery` defines a function.
When called, two events are emitted:
1. A `:reg` event for registering to results, and
2. A `:fact` event to make the query.
In such a case the function returns an empty sequence with a `:pending` meta field set to `true`."
(fact defquery-3
      (async done
             (go
               (reset! my-atom7 {})
               (let [result (my-query7 "alice")]
                 (is (= result []))
                 (is (= (-> result meta :pending) true)))
               (is (= (async/<! (:to-host host7))
                      {:kind :reg
                       :name "tweetlog/timeline!"
                       :key "SOMEUUID1"}))
               (is (= (async/<! (:to-host host7))
                      {:kind :fact
                       :name "tweetlog/timeline?"
                       :key "SOMEUUID1"
                       :data ["alice"]
                       :ts 12345
                       :change 1
                       :writers #{"alice"}
                       :readers #{"alice"}}))
               (done))))

"Incoming events that carry query results update the map in this atom.
The main difference between this and what `defview` does is that 
while the events that `defview` receives contain all the data necessary for calculating both the keys and the values,
The results here do not contain the information for the key (the function's arguments).
Instead, the event contains a unique ID, which is mapped by `defquery` to input arguments."
(fact defquery-4
      (async done
             (reset! my-atom7 {})
             (go
               (async/>! from-host7
                         {:kind :fact
                          :name "tweetlog/timeline!"
                          :key "SOMEUUID1"
                          :data ["bob" "hi there"]
                          :ts 23456
                          :change 1
                          :writers #{"XXYY"}
                          :readers #{}})
               (async/<! (async/timeout 1))
               (is (= (get-in @my-atom7
                              [["alice"]
                               {:kind :fact
                                :name "tweetlog/timeline!"
                                :key "SOMEUUID1"
                                :data ["bob" "hi there"]
                                :writers #{"XXYY"}
                                :readers #{}}]) 1))
               (done))))

"With results in place, the query function returns a (non-`:pending`) list of value maps.
This time, the value maps capture the query's *output parameters* only."
(fact defquery-5
      (let [result (my-query7 "alice")]
        (is (= (-> result meta :pending) false))
        (is (= result
               [{:author "bob"
                 :tweet "hi there"}])) ))

"As with `defview`, results with a count value of zero or under are omitted."
(fact defquery-6
      (reset! my-atom7 {})
      (swap! my-atom7 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/timeline!"
                                 :key "SOMEUUID1"
                                 :data ["bob" "hi there"]
                                 :writers #{"XXYY"}
                                 :readers #{}}] 0)
      (swap! my-atom7 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/timeline!"
                                 :key "SOMEUUID1"
                                 :data ["bob" "bye there"]
                                 :writers #{"XXYY"}
                                 :readers #{}}] -1)
      (is (= (my-query7 "alice") [])))

[[:section {:title "Filtering and Sorting"}]]
"Filtering and sorting work exactly as with `defview`."
(fact defquery-filt
      (async done
             (defonce from-host8 (async/chan 10))
             (defonce host8 {:to-host (async/chan 10)
                             :pub (async/pub from-host8 :name)
                             :identity "alice"
                             :uuid (constantly "SOMEUUID")
                             :time (constantly 12345)})
             (defonce my-atom8 (atom nil))
             (defquery tweets-by-authors-that-begin-in-b [user]
               host8
               [:tweetlog/timeline user -> author tweet]
               :store-in my-atom8
               :when (str/starts-with? author "b")
               :order-by tweet)
             (go
               (tweets-by-authors-that-begin-in-b "alice") ;; Start listenning...
               (async/>! from-host8
                         {:kind :fact
                          :name "tweetlog/timeline!"
                          :key "SOMEUUID"
                          :data ["bob" "D"]
                          :ts 1000
                          :change 1
                          :writers #{"XXYY"}
                          :readers #{}})
               (async/>! from-host8
                         {:kind :fact
                          :name "tweetlog/timeline!"
                          :key "SOMEUUID"
                          :data ["charlie" "C"] ;; Dropped
                          :ts 2000
                          :change 1
                          :writers #{"XXYY"}
                          :readers #{}})
               (async/>! from-host8
                         {:kind :fact
                          :name "tweetlog/timeline!"
                          :key "SOMEUUID"
                          :data ["boaz" "B"]
                          :ts 3000
                          :change 1
                          :writers #{"XXYY"}
                          :readers #{}})
               (async/>! from-host8
                         {:kind :fact
                          :name "tweetlog/timeline!"
                          :key "SOMEUUID"
                          :data ["bob" "A"]
                          :ts 4000
                          :change 1
                          :writers #{"XXYY"}
                          :readers #{}})
               (async/<! (async/timeout 1))
               (let [results (tweets-by-authors-that-begin-in-b "alice")]
                 (is (= (count results) 3))
                 (is (= (->> results
                             (map :tweet))
                        ["A" "B" "D"])))
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
