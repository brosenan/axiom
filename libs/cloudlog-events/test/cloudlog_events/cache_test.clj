(ns cloudlog-events.cache_test
  (:use [midje.sweet]
        [cloudlog-events.cache])
  (:require [clojure.core.async :as async]
            [cloudlog-events.core :as ev]
            [cloudlog-events.core_test :refer [timeline event]]))

[[:chapter {:title "Time-Window Cache"}]]
"NoSQL databases often offer great scalability at the expence of atomicity.
Many such databases are *eventually consistent*, meaning that queries made to them may not reflect recent updates.
If left unhandled, this may cause a problem for [matchers](cloudlog-events.html#matcher), which rely on finding all relevant facts for a given rule and vice versa."

"To overcome this problem, we provide a time-window cache that stores events for a specified amount of time, and fetches them upon request."

"`wrap-time-window` is a matcher middleware, that is, 
it is a higher-order function that takes as parameters a function with [matcher](cloudlog-events.html#matcher)'s signature,
and returns a function with the same signature and contract.
However, the returned function will create matchers that leverage a time-window cache."

"`wrap-time-window` takes two additional parameter:
- a function that returns the current timestamp, and
- the retention period in the same time units used by the former function."

"For example, consider we wrap the original `matcher` function with a cached-one, and call it `cached-matcher`."
(fact
 (let [current-time (atom 1000)
       get-time (partial swap! current-time + 10)]
   (def cached-matcher (-> ev/matcher
                           (wrap-time-window get-time 30)))))

"Recall that the `matcher` function is used to create a matcher, based on a rule, a link number and a database channel."
(fact
 (def db-chan (async/chan 10))
 (def timeline-cached-matcher (cached-matcher timeline 1 db-chan)))

"We now simulate an empty database, by replying to each request with no events."
(fact
 (async/go-loop []
   (let [[q ch] (async/<! db-chan)]
     (async/close! ch))
   (recur)))

"Now let's send our matcher a `:rule` event and a matching `:fact` event (based on [this](cloudlog-events.html#matching-facts-for-rules) example)
For the `:rule` event we do not expect any response, because the database is empty."
(fact
 (let [res-chan (async/chan 2)]
   (timeline-cached-matcher (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]) res-chan)
   (let [[res ch] (async/alts!! [res-chan (async/timeout 1000)])]
     ch => res-chan
     res => nil ;; No results at this point
     )))

"But now, when we provide a matching fact, we expect the `:rule` event to be remembered by the cached-mather."
(fact
 (let [res-chan (async/chan 2)]
   (timeline-cached-matcher (event :fact "test/tweeted" "bob" ["hello"]) res-chan)
   (let [[res ch] (async/alts!! [res-chan (async/timeout 1000)])]
     ch => res-chan
     res => (event :fact "cloudlog-events.core_test/timeline" "alice" ["hello"]))))

"Now let's simulate a database that has one `:rule` event: `eve` follows `bob`."
(fact
 (let [db-chan (async/chan 10)]
   (async/go-loop []
     (let [[q ch] (async/<! db-chan)]
       (when (= (:kind q) :rule)
         (async/>! ch (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["eve" "bob"])))
       (async/close! ch))
     (recur))
   (def timeline-cached-matcher (cached-matcher timeline 1 db-chan))))

"Now when we repeat this excercise, the rule still does not give us any results, but the fact gives us two."
(fact
 (let [res-chan (async/chan 2)]
   (timeline-cached-matcher (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["alice" "bob"]) res-chan)
   (let [[res ch] (async/alts!! [res-chan (async/timeout 1000)])]
     ch => res-chan
     res => nil ;; No results at this point
     ))
 (let [res-chan (async/chan 2)]
   (timeline-cached-matcher (event :fact "test/tweeted" "bob" ["hello"]) res-chan)
   (let [[res ch] (async/alts!! [(async/reduce conj #{} res-chan) (async/timeout 1000)])]
     res => #{(event :fact "cloudlog-events.core_test/timeline" "alice" ["hello"])
              (event :fact "cloudlog-events.core_test/timeline" "eve" ["hello"])})))

"The result is de-dupped, so that we do not get duplicate output events in the (highly probablye) case when events we get from the cache also exist in the database."
(fact
 (let [db-chan (async/chan 10)]
   (async/go-loop []
     (let [[q ch] (async/<! db-chan)]
       (when (= (:kind q) :rule)
         (async/>! ch (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["eve" "bob"])))
       (async/close! ch))
     (recur))
   (let [timeline-cached-matcher (cached-matcher timeline 1 db-chan)
         res-chan (async/chan 2)]
     (timeline-cached-matcher (event :rule "cloudlog-events.core_test/timeline!0" "bob" ["eve" "bob"]) res-chan)
     (let [[res ch] (async/alts!! [res-chan (async/timeout 1000)])]
       ch => res-chan)
     (let [res-chan (async/chan 2)]
       (timeline-cached-matcher (event :fact "test/tweeted" "bob" ["hello"]) res-chan)
       (let [[res ch] (async/alts!! [(async/reduce conj [] res-chan) (async/timeout 1000)])]
         (count res) => 1)))))

[[:section {:title "Under the Hood"}]]
"The time-window cache is a tuple containing:
1. a map between event pairs of a `:name` and a `:key` to sets of complete events sharing a `:name` and a `:key`, and
2. a double-list containing the events in the order of their arrival along with the time of their arrival.
3. the retention period (in the time-units used for timestamps).  This figure is given as parameter
The `time-window-cache` function creates this data structure."
(fact
 (time-window-cache 30) => [{} [] 30])

"The function `tw-update` receives a time-window, and adds the given event to it.
The event is stored alongside the timestamp that is passed to `tw-update` as its third argument."
(fact
 (let [ev1 {:name "foo" :key "bar" :data [1 2 3]}]
   (tw-update (time-window-cache 30) ev1 1000)
   => [{["foo" "bar"] #{ev1}} [[ev1 1000]] 30]))

"`tw-update` also prunes events older than the retention period.
For example, if we have 3 events coming at times `1000`, `1020` and `1040` to a cache with a retention period of `30`,
after the third update we should only have the last two events in the cache."
(fact
 (let [ev1 {:name "foo" :key "bar" :data [1 2 3]}
       ev2 {:name "foo" :key "bar" :data [2 3 4]}
       ev3 {:name "foo" :key "baz" :data [3 4 5]}]
   (-> (time-window-cache 30)
       (tw-update ev1 1000)
       (tw-update ev2 1020)
       (tw-update ev3 1040))
   => [{["foo" "bar"] #{ev2}
        ["foo" "baz"] #{ev3}}
       [[ev2 1020] [ev3 1040]]
       30]))

"`tw-get` returns all events that match a certain `:name` and `:key`."
(fact
 (let [ev1 {:name "foo" :key "bar" :data [1 2 3]}
       ev2 {:name "foo" :key "bar" :data [2 3 4]}
       ev3 {:name "foo" :key "baz" :data [3 4 5]}]
   (-> (time-window-cache 30)
       (tw-update ev1 1000)
       (tw-update ev2 1001)
       (tw-update ev2 1002)
       (tw-get {:name "foo" :key "bar"}))
   => #{ev1 ev2}))

"It returns an empty set if not found."
(fact
 (let [ev1 {:name "foo" :key "bar" :data [1 2 3]}
       ev2 {:name "foo" :key "bar" :data [2 3 4]}
       ev3 {:name "foo" :key "baz" :data [3 4 5]}]
   (-> (time-window-cache 30)
       (tw-update ev1 1000)
       (tw-update ev2 1001)
       (tw-update ev2 1002)
       (tw-get {:name "quux" :key "fix"}))
   => #{}))
