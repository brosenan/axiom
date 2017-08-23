(ns axiom-cljs.macros-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [axiom-cljs.core :as ax])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]
                   [axiom-cljs.macros :refer [defview defquery]]))

(enable-console-print!)

[[:chapter {:title "defview"}]]
"The `defview` macro defines a *view*, which is a data structure that aggregates events and allows query over these events."

"`defview` four positional parameters:
1. The name of the view (a symbol).  This becomes the name of a function that retrieves data from that view.
2. An argument list for the view (a vector).  This becomes the argument list for the function.
3. A [connection](axiom-cljs.html#connection) object.
4. A pattern of a fact or clause to be queried by this view.
It also takes optional keyword parameters that will be discussed later."
(fact defview-1
      (defonce published1 (atom nil))
      (reset! published1 nil)
      (defonce host {:pub (fn [ev]
                            (swap! published1 conj ev))
                     :sub (fn [key f])})
      (defview my-tweets [user]
        [:tweetlog/tweeted user tweet]))

"The defined view is a function with the parameters defined in the view definition."
(fact defview-2
      (is (fn? my-tweets)))

"When called for the first time, the function will send a `:reg` event with key according to the parameters,
and return an empty sequence with a meta field `:pending` indicating that the function should be consulted again later."
(fact defview-3
      (let [res (my-tweets host "alice")]
        (is (= res []))
        (is (= (-> res meta :pending) true))
        (is (= @published1 [{:kind :reg
                             :name "tweetlog/tweeted"
                             :key "alice"
                             :get-existing true}]))))

"An optional keyword parameter `:store-in` takes an atom and initially `reset!`s is to an empty map."
(fact defview-4
      (defonce ps2 (ax/pubsub :name))
      (defonce published2 (atom nil))
      (reset! published2 nil)
      (defonce host2 {:sub (:sub ps2)
                      :pub #(swap! published2 conj %)
                      :time (constantly 12345)
                      :identity (atom "alice")})
      (defonce my-atom2 (atom nil))
      (defview my-tweets2 [user]
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
      (reset! my-atom2 {})
      (my-tweets2 host2 "alice") => []  ;; Make the inital call that returns an empty collection
      ((:pub ps2)
       {:kind :fact
        :name "tweetlog/tweeted"
        :key "alice"
        :data ["hello"]
        :ts 1000
        :change 1
        :readers #{}
        :writers #{"alice"}})
      ((:pub ps2)
       {:kind :fact
        :name "tweetlog/tweeted"
        :key "alice"
        :data ["world"]
        :ts 2000
        :change 1
        :readers #{}
        :writers #{"alice"}})
      ((:pub ps2)
       {:kind :fact
        :name "tweetlog/tweeted"
        :key "alice"
        :data ["world"]
        :ts 3000
        :change 1
        :readers #{}
        :writers #{"alice"}})
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
                                 :writers #{"alice"}}]) 2)))

"The view function returns, for a given combination of arguments, a collection of all data elements with a positive total count.
In our case, both \"hello\" and \"world\" tweets are taken (in some order)."
(fact defview-6
      (is (= (count (my-tweets2 host2 "alice")) 2))
      (is (= (-> (my-tweets2 host2 "alice")
                 meta :pending) false)))

"Each element in the returned collection is a *value map*, a map in which the keys correspond to the symbols in the fact pattern provided in the view definition.
In our case these are `:user` and `:tweet`.
The values are their corresponding values in each event."
(fact defview-7a
      (doseq [result (my-tweets2 host2 "alice")]
        (is (= (:user result) "alice"))
        (is (contains? #{"hello" "world"} (:tweet result)))))

"Along with the data fields, each value map also contains the `:-readers` and `:-writers` of the corresponding events."
(fact defview-7b
      (doseq [result (my-tweets2 host2 "alice")]
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
      (is (= (count (my-tweets2 host2 "alice")) 0)))

[[:section {:title "Event-Emitting Functions"}]]
"A view provides functions that allow users to emit event for creating, updating and deleting facts."

"For *creating facts*, a sequence returned by a view function has an `:add` meta-field.
This is a function that takes a value map as input, and emits a corresponding event with the following keys:
1. `:kind` is always `:fact`.
2. `:name` is the first element in the fact pattern, converted to string.
3. `:key` and `:data` are derived from the value map.
4. `:ts` consults the host's `:time` function.
5. `:change` is always 1.
6. `:writers` defaults to the set representing the user.
7. `:readers` defaults to the universal set.
Parameters already given to the view function (e.g., `:user \"alice\"` in the following example) should be omitted."
(fact defview-ev-1a
      (reset! published2 [])
      (let [{:keys [add]} (meta (my-tweets2 host2 "alice"))]
        (is (fn? add))
        (add {:tweet "Hola!"})
        (is (= @published2
               [{:kind :fact
                  :name "tweetlog/tweeted"
                  :key "alice"
                  :data ["Hola!"]
                  :ts 12345
                  :change 1
                  :writers #{"alice"}
                 :readers #{}}]))))

"The `:readers` and `:writers` sets in newly-added facts are controlled by the optional `:readers` and `:writers` keyword arguments in the view definition.
These arguments are expressions that are evaluated for every invocation of `add`.
They can use any of the symbols in the fact pattern (which will evaluate to their corresponding value in the value map given to `add`),
and the symbol `$user`, which represents the identity of the current user."

"In the following example we define a view for `tweetlog/follows` facts, indicating that one user follows another.
We set the `:writers` set to be the (following) user using both his or her system identity (`$user`) and alias,
and the `:readers` set to contain the user being followed by alias."
(fact defview-ev-1b
      (defonce published-follows (atom nil))
      (reset! published-follows nil)
      (let [my-atom (atom nil)
            ps (ax/pubsub :name)
            host {:sub (:sub ps)
                  :pub #(reset! published-follows %)
                  :identity (atom "alice")
                  :time (constantly 2345)}]
        (defview my-following [follower]
          [:tweetlog/follows follower followee]
          :writers #{$user [:tweetlog/has-alias follower]}
          :readers #{[:tweetlog/has-alias followee]})
        ((:pub ps) {:kind :fact
                    :name "tweetlog/follows"
                    :key "alice123"
                    :data ["bob345"]
                    :ts 1234
                    :change 1
                    :writers #{"alice"}
                    :readers #{}})
        (let [add (-> (my-following host "alice123")
                      meta :add)]
          (add {:follower "alice123"
                :followee "charlie678"}))
        (is (= @published-follows {:kind :fact
                                   :name "tweetlog/follows"
                                   :key "alice123"
                                   :data ["charlie678"]
                                   :ts 2345
                                   :change 1
                                   :writers #{"alice" [:tweetlog/has-alias "alice123"]}
                                   :readers #{[:tweetlog/has-alias "charlie678"]}}))))

"In value maps where the user is a writer, a `:del!` entry contains a function that deletes the corresponding fact.
It creates an event with all the same values, but with a new `:ts` and a `:change` value equal to the negative of the count value of the original event."
(fact defview-ev-2
      (defonce my-atom3 (atom nil))
      (defonce published3 (atom nil))
      (reset! published3 nil)
      (defonce host3 {:pub #(swap! published3 conj %)
                      :sub (fn [key f])
                      :time (constantly 23456)})
      (defview my-tweets3 [user]
        [:tweetlog/tweeted user tweet]
        :store-in my-atom3)
      (swap! my-atom3 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/tweeted"
                                 :key "alice"
                                 :data ["hello"]
                                 :readers #{}
                                 :writers #{"alice"}}] 3)
      (let [valmap (first (my-tweets3 host3 "alice"))]
        (is (fn? (:del! valmap)))
        ((:del! valmap))
        (is (= @published3
               [{:kind :fact
                  :name "tweetlog/tweeted"
                  :key "alice"
                  :data ["hello"]
                  :ts 23456
                  :change -3
                  :readers #{}
                  :writers #{"alice"}}]))))

"A `:swap!` function provides a way to update a value map.
It takes a function (and optionally arguments) that is applied to the value map, and emits an [atomic update](cloudlog-events.html#atomic-updates)
event from the original state to the state reflected by the modified value map."
(fact defview-ev-3
      (defonce my-atom4 (atom nil))
      (defonce published4 (atom nil))
      (reset! published4 nil)
      (defonce host4 {:pub #(swap! published4 conj %)
                      :sub (fn [key f])
                      :time (constantly 34567)})
      (defview my-tweets4 [user]
        [:tweetlog/tweeted user tweet ts]
        :store-in my-atom4)
      (swap! my-atom4 assoc-in [["alice"]
                                {:kind :fact
                                 :name "tweetlog/tweeted"
                                 :key "alice"
                                 :data ["hello" 12345]
                                 :readers #{}
                                 :writers #{"alice"}}] 3)
      (let [valmap (first (my-tweets4 host4 "alice"))]
        (is (fn? (:swap! valmap)))
        ((:swap! valmap) assoc :tweet "world")
        (is (= @published4
               [{:kind :fact
                  :name "tweetlog/tweeted"
                  :key "alice"
                  :removed ["hello" 12345]
                  :data ["world" 12345]
                  :ts 34567
                  :change 3
                  :readers #{}
                  :writers #{"alice"}}]))))

[[:section {:title "Filtering"}]]
"Views can define client-side filtering for facts."

"Imagine we are only interested in tweets that contain a hash-tag, i.e., tweets that match the regular expression `#\".*#[a-zA-Z0-9]+.*\"`.
We define such filtering using an optional `:when` key in `defview`."
(fact defview-filt
      (defonce my-atom5 (atom nil))
      (defonce ps5 (ax/pubsub :name))
      (defonce host5 {:sub (:sub ps5)
                      :pub (constantly nil)})
      (defview hashtags-only [user]
        [:tweetlog/tweeted user tweet]
        :store-in my-atom5
        :when (re-matches #".*#[a-zA-Z0-9]+.*" tweet))
      (reset! my-atom5 {})
      (hashtags-only host5 "alice") => [] ;; Initial call to view function
      ((:pub ps5)
       {:kind :fact
        :name "tweetlog/tweeted"
        :key "alice"
        :data ["No hashtags here..."]
        :ts 1000
        :change 1
        :readers #{}
        :writers #{"alice"}})
      ((:pub ps5)
       {:kind :fact
        :name "tweetlog/tweeted"
        :key "alice"
        :data ["This one hash #hashtags..."]
        :ts 2000
        :change 1
        :readers #{}
        :writers #{"alice"}})
      (is (= (count (@my-atom5 ["alice"])) 1)))

[[:section {:title "Sorting"}]]
"The optional keyword argument `:order-by` directs the view function to sort the elements it returns according to the given expression.
The expression can rely on symbols from the fact pattern, and must result in a [comparable expression](https://clojure.org/guides/comparators)."
(fact defview-sort
      (defonce my-atom6 (atom nil))
      (defonce host6 {:sub (fn [key f])})
      (defview my-sorted-tweets [user]
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
      (let [tweets-in-order (for [result (my-sorted-tweets host6 "alice")]
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
      (defonce unique7 (atom nil))
      (reset! unique7 0)
      (defonce ps7 (ax/pubsub :name))
      (defonce published7 (atom nil))
      (reset! published7 [])
      (defonce host7 {:sub (:sub ps7)
                      :pub #(swap! published7 conj %)
                      :identity (atom "alice")
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
      (reset! my-atom7 {})
      (let [result (my-query7 "alice")]
        (is (= result []))
        (is (= (-> result meta :pending) true)))
      (is (= @published7
             [{:kind :reg
                :name "tweetlog/timeline!"
               :key "SOMEUUID1"}
              {:kind :fact
               :name "tweetlog/timeline?"
               :key "SOMEUUID1"
               :data ["alice"]
               :ts 12345
               :change 1
               :writers #{"alice"}
               :readers #{"alice"}}])))

"Incoming events that carry query results update the map in this atom.
The main difference between this and what `defview` does is that 
while the events that `defview` receives contain all the data necessary for calculating both the keys and the values,
The results here do not contain the information for the key (the function's arguments).
Instead, the event contains a unique ID, which is mapped by `defquery` to input arguments."
(fact defquery-4
      (reset! my-atom7 {})
      ((:pub ps7) {:kind :fact
                   :name "tweetlog/timeline!"
                   :key "SOMEUUID1"
                   :data ["bob" "hi there"]
                   :ts 23456
                   :change 1
                   :writers #{"XXYY"}
                   :readers #{}})
      (is (= (get-in @my-atom7
                     [["alice"]
                      {:kind :fact
                       :name "tweetlog/timeline!"
                       :key "SOMEUUID1"
                       :data ["bob" "hi there"]
                       :writers #{"XXYY"}
                       :readers #{}}]) 1)))

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
      (defonce ps8 (ax/pubsub :name))
      (defonce host8 {:pub (fn [ev])
                      :sub (:sub ps8)
                      :identity (atom "alice")
                      :uuid (constantly "SOMEUUID")
                      :time (constantly 12345)})
      (defonce my-atom8 (atom nil))
      (defquery tweets-by-authors-that-begin-in-b [user]
        host8
        [:tweetlog/timeline user -> author tweet]
        :store-in my-atom8
        :when (str/starts-with? author "b")
        :order-by tweet)
      (tweets-by-authors-that-begin-in-b "alice") ;; Start listenning...
      ((:pub ps8)
                {:kind :fact
                 :name "tweetlog/timeline!"
                 :key "SOMEUUID"
                 :data ["bob" "D"]
                 :ts 1000
                 :change 1
                 :writers #{"XXYY"}
                 :readers #{}})
      ((:pub ps8)
                {:kind :fact
                 :name "tweetlog/timeline!"
                 :key "SOMEUUID"
                 :data ["charlie" "C"] ;; Dropped
                 :ts 2000
                 :change 1
                 :writers #{"XXYY"}
                 :readers #{}})
      ((:pub ps8)
                {:kind :fact
                 :name "tweetlog/timeline!"
                 :key "SOMEUUID"
                 :data ["boaz" "B"]
                 :ts 3000
                 :change 1
                 :writers #{"XXYY"}
                 :readers #{}})
      ((:pub ps8)
                {:kind :fact
                 :name "tweetlog/timeline!"
                 :key "SOMEUUID"
                 :data ["bob" "A"]
                 :ts 4000
                 :change 1
                 :writers #{"XXYY"}
                 :readers #{}})
      (let [results (tweets-by-authors-that-begin-in-b "alice")]
        (is (= (count results) 3))
        (is (= (->> results
                    (map :tweet))
               ["A" "B" "D"]))))

