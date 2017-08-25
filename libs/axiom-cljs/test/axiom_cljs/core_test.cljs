(ns axiom-cljs.core-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [axiom-cljs.core :as ax]
            [cljs.core.async :as async]
            [clojure.string :as str]
            [goog.net.cookies :as cookies])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]))

(enable-console-print!)

[[:chapter {:title "connection"}]]
"`connection` creates a connection to the host."

"It takes a URL and a the following optional keyword parameter:
- `:ws-ch`, which defaults to a [function of the same name in the chord library](https://github.com/jarohen/chord#clojurescript), and
- `:atom`, which defaults to `clojure.core/atom`, and is used to construct a mutable box to place the user's identity.
It returns a map containing the following keys:
1. A `:sub` function for subscribing to events coming from the host.
2. A `:pub` function for publishing events.
3. A `:time` function which returns the current time in milliseconds.
4. A `:uuid` function which returns some universally-unique identifier.
5. An `:identity` atom, which will contain the user's identity once an `:init` event is received from the host."
(fact connection-1
  (async done
         (go
           (let [the-chan (async/chan 10)
                 mock-ws-ch (fn [url]
                              (is (= url "ws://some-url"))
                              (go
                                {:ws-channel the-chan}))
                 host (ax/connection "ws://some-url"
                                     :ws-ch mock-ws-ch)]
             (is (map? host))
             (is (= @(:identity host) nil)) ;; Initial value
             ;; The host sends an `:init` event
             (async/>! the-chan {:message {:kind :init
                                           :name "some/name"
                                           :identity "alice"}})
             (async/<! (async/timeout 1))
             (is (= @(:identity host) "alice"))
             
             (is (fn? (:pub host)))
             (is (fn? (:sub host)))
             (let [test-chan (async/chan 10)]
               ((:sub host) "foo" #(go (async/>! test-chan %)))
               (async/>! the-chan {:message {:name "bar" :some :event}})
               (async/>! the-chan {:message {:name "foo" :other :event}})
               (is (= (async/<! test-chan) {:name "foo" :other :event}))
               ;; Since our mock `ws-ch` creates a normal channel, publishing into this channel
               ;; will be captured by subscribers
               ((:pub host) {:message {:name "foo" :third :event}})
               (is (= (async/<! test-chan) {:name "foo" :third :event})))

             (is (fn? (:time host)))
             (let [time-before ((:time host))]
               (async/<! (async/timeout 1))
               (is (< time-before ((:time host)))))

             (is (fn? (:uuid host)))
             (is (= (count ((:uuid host))) 36))
             (is (not= ((:uuid host)) ((:uuid host))))
             (done)))))

"The connection map also has an `:status` field, which is an atom.
It holds the value `:ok` as long as the WebSocket channel is open, and `:err` once the channel has been closed."
(fact connection-2
  (async done
         (go
           (let [the-chan (async/chan 10)
                 mock-ws-ch (fn [url]
                              (is (= url "ws://some-url"))
                              (go
                                {:ws-channel the-chan}))
                 host (ax/connection "ws://some-url"
                                     :ws-ch mock-ws-ch)]
             (is (= @(:status host) :ok))
             (async/close! the-chan)
             (async/<! (async/timeout 1))
             (is (= @(:status host) :err))
             (done)))))

[[:chapter {:title "ws-url"}]]
"The [connection](#connection) function receives as parameter a WebSocket URL to connect to.
By default, this will be of the form `ws://<host>/ws`, where `<host>` represents the value of `js/document.location.host`."

"`ws-url` takes a `js/document.location` object and returns a WebSocket URL according to the above pattern."
(fact ws-url-1
      (is (= (ax/ws-url (js-obj "host" "localhost:8080")) "ws://localhost:8080/ws")))

"In one case, where the host value corresponds to figwheel running on the local host,
we use `localhost:8080` instead of the original host, to direct WebSockets to Axiom rather than figwheel."
(fact ws-url-2
      (is (= (ax/ws-url (js-obj "host" "localhost:3449")) "ws://localhost:8080/ws")))

"If a `hash` exists in the location, and if it contains a `?`, 
everything right of the `?` is appended to the URL as a query string."
(fact ws-url-3
      (let [loc (js-obj "host" "localhost:8080"
                        "hash" "#foo?bar")]
        (is (= (ax/ws-url loc) "ws://localhost:8080/ws?bar"))))

"If a hash exists, but does not have a query string, no change is made to the URL."
(fact ws-url-4
      (let [loc (js-obj "host" "localhost:8080"
                        "hash" "#foobar")]
        (is (= (ax/ws-url loc) "ws://localhost:8080/ws"))))

[[:chapter {:title "update-on-dev-ver"}]]
"When in development, [Figwheel](https://github.com/bhauman/lein-figwheel) can be used to update client-side artifacts as they are being modified, 
without having to reload the page.
However, when updating the [Cloudlog](cloudlog.html) logic, we wish to update content of the [views]axiom-cljs.macros.html#defview) and [queries](axiom-cljs.macros.html#defquery), and that cannot be done without refreshing."

"`update-on-dev-ver` is a middleware function that can be called on a connection map, and returns a valid connection map."
(fact update-on-dev-ver-0
      (let [ps (ax/pubsub :name)
            host (->  {:sub (:sub ps)
                       :pub (fn [& _])}
                      ax/update-on-dev-ver)
            received (atom nil)]
        ((:sub host) "foo/bar" (partial reset! received))
        ((:pub ps) {:kind :fact
                    :name "foo/bar"
                    :key 1})
        (is (= @received {:kind :fact
                          :name "foo/bar"
                          :key 1}))))

"It subscribes to `axiom/perm-versions` events.
For each such event it will set the `app-version` cookie to contain the new version, so that refreshing the browser will capture the new version."
(fact update-on-dev-ver-1
      (let [ps (ax/pubsub :name)
            published (atom [])
            host (->  {:sub (:sub ps)
                       :pub (partial swap! published conj)}
                      ax/update-on-dev-ver)
            new-ver (str "dev-" (rand-int 1000000))]
        (is (=@published [{:kind :reg
                           :name "axiom/perm-versions"}]))
        ((:pub ps) {:kind :fact
                    :name "axiom/perm-versions"
                    :key new-ver
                    :change 1})
        (is (= (.get goog.net.cookies "app-version") new-ver))))

"This only applies to events with a positive `:change` (introduction of new versions), 
and only applies to development versions (that begin with `dev-`)."
(fact update-on-dev-ver-2
      (let [ps (ax/pubsub :name)
            host (->  {:sub (:sub ps)
                       :pub (fn [& _])}
                      ax/update-on-dev-ver)
            new-ver (str "dev-" (rand-int 1000000))]
        ((:pub ps) {:kind :fact
                    :name "axiom/perm-versions"
                    :key new-ver
                    :change 0})
        ((:pub ps) {:kind :fact
                    :name "axiom/perm-versions"
                    :key new-ver
                    :change -1})
        ((:pub ps) {:kind :fact
                    :name "axiom/perm-versions"
                    :key (str "not" new-ver)
                    :change 1})
        (is (not= (.get goog.net.cookies "app-version") new-ver))
        (is (not= (.get goog.net.cookies "app-version") (str "not" new-ver)))))

[[:chapter {:title "wrap-feed-forward"}]]
"Valid events that are `:pub`lished by the client later return from the server almost unchanged.
`:sub`scribers will receive these events with a certain delay.
Unfortunately, this delay may cause undesired behavior, when updates to the desplay are delayed until the events return from the server-side.
To overcome this problem, the `wrap-feed-forward` middleware propagates events immediately from publishers to subscribers, on the client."

"`wrap-feed-forward` wraps a connection map.
It preserves the connection's properties, such that events `:pub`lished on the client are forwarded to the server,
and events coming from the server are received by `:sub`scribers on the client."
(fact wrap-feed-forward-1
 (let [ps (ax/pubsub :name)
       published (atom [])
       subscribed (atom [])
       host (-> {:pub (partial swap! published conj)
                 :sub (:sub ps)}
                ax/wrap-feed-forward)]
   ;; Events coming from the server
   ((:sub host) "foo" (partial swap! subscribed conj))
   ((:pub ps) {:kind :fact
               :name "foo"
               :key 1
               :data [2 3]
               :ts 1000
               :change 1
               :readers #{}
               :writers #{}})
   (is (= @subscribed [{:kind :fact
                        :name "foo"
                        :key 1
                        :data [2 3]
                        :ts 1000
                        :change 1
                        :readers #{}
                        :writers #{}}]))
   ;; Event published by the client
   ((:pub host) {:kind :fact
                 :name "bar"
                 :key 1
                 :data [2 3]
                 :ts 1000
                 :change 1
                 :readers #{}
                 :writers #{}})
   (is (= @published [{:kind :fact
                       :name "bar"
                       :key 1
                       :data [2 3]
                       :ts 1000
                       :change 1
                       :readers #{}
                       :writers #{}}]))))

"However, for events published by the client, subscribers on the client get immediate response."
(fact wrap-feed-forward-2
 (let [ps (ax/pubsub :name)
       subscribed (atom [])
       host (-> {:pub (fn [& _])
                 :sub (:sub ps)}
                ax/wrap-feed-forward)]
   ((:sub host) "foo" (partial swap! subscribed conj))
   ;; Event published by the client
   ((:pub host) {:kind :fact
                 :name "foo"
                 :key 1
                 :data [2 3]
                 :ts 1000
                 :change 1
                 :readers #{}
                 :writers #{}})
   ;; is received by the client
   (is (= @subscribed [{:kind :fact
                        :name "foo"
                        :key 1
                        :data [2 3]
                        :ts 1000
                        :change 1
                        :readers #{}
                        :writers #{}}]))))

"`wrap-feed-forward` de-duplicates, so that once an event that was forwarded comes back from the server, it is not forwarded again to subscribers."
(fact wrap-feed-forward-3
 (let [ps (ax/pubsub :name)
       subscribed (atom [])
       host (-> {:pub (fn [& _])
                 :sub (:sub ps)}
                ax/wrap-feed-forward)]
   ((:sub host) "foo" (partial swap! subscribed conj))
   ;; Event published by the client
   ((:pub host) {:kind :fact
                 :name "foo"
                 :key 1
                 :data [2 3]
                 :ts 1000
                 :change 1
                 :readers #{"foo"}
                 :writers #{}})
   ;; and received from the server
   ((:pub ps) {:kind :fact
               :name "foo"
               :key 1
               :data [2 3]
               :ts 1000
               :change 1
               ;; The server may change the readers
               :readers #{"bar"}
               :writers #{}})
   ;; is received only once by subscribers
   (is (= (count @subscribed) 1))
   ;; However, if the event is received again it is not blocked.
   ((:pub ps) {:kind :fact
               :name "foo"
               :key 1
               :data [2 3]
               :ts 1000
               :change 1
               :readers #{"bar"}
               :writers #{}})
   (is (= (count @subscribed) 2))))

[[:chapter {:title "wrap-atomic-updates"}]]
"`wrap-atomic-updates` is a connection middleware that handles [atomic updates](cloudlog-events.html#atomic-updates),
by treating them as two separate events."

"For normal events, `wrap-atomic-updates` does not modify the way `:sub` works."
(fact wrap-atomic-updates-1
      (let [ps (ax/pubsub :name)
            host (-> {:sub (:sub ps)}
                     ax/wrap-atomic-updates)
            published (atom [])]
        ((:sub host) "foo" (partial swap! published conj))
        ((:pub ps) {:name "foo" :key "bar" :data [1 2 3] :change 1})
        (is (= @published [{:name "foo" :key "bar" :data [1 2 3] :change 1}]))))

"However, for atomic updates, the subscribed function is called twice: A first time with a negative `:change` and the `:removed` value in place of `:data`,
and the a second time with the original value of `:change` and `:removed` removed."
(fact wrap-atomic-updates-2
      (let [ps (ax/pubsub :name)
            host (-> {:sub (:sub ps)}
                     ax/wrap-atomic-updates)
            published (atom [])]
        ((:sub host) "foo" (partial swap! published conj))
        ((:pub ps) {:name "foo" :key "bar" :data [2 3 4] :removed [1 2 3] :change 1})
        (is (= @published [{:name "foo" :key "bar" :data [1 2 3] :change -1}
                           {:name "foo" :key "bar" :data [2 3 4] :change 1}]))))

[[:chapter {:title "wrap-reg"}]]
"The WebSocket protocol this library uses to communicate with the [gateway](gateway.html) uses a special event not used in other places of axiom:
the `:reg` event.
This event registers the connection to streams of `:fact` events, and possibly asks for all existing facts that match cerain criteria.
The `wrap-reg` middleware is used to ensure correct semantics in situations where multiple views or queries register to the same kind of events."

"Consider a situation where two views register to the same `:fact` pattern (`:name`+`:key` combination).
Since each view has its own storage atom, they will not know about each other.
Each will create a `:reg` event with these `:name` and `:key` and register to incoming events.
They will also set the `:get-existing` to `true`, to receive all existing facts that match.
Unfortunately, since `:get-existing` was sent twice, the back-end will send two copies of each event.
These events will be received by both views and so the value stored in the atom related to these events will be double its actual value on the server."

"`wrap-reg` is intended to solve this problem.
It is a connection middleware that augments the connection's `:pub` method.
If each `:reg` event is sent only once, the `:pub` method's behavior remains unchanged."
(fact wrap-reg-1
      (let [ps (ax/pubsub :name)
            host (-> {:pub (:pub ps)}
                     ax/wrap-reg)
            published (atom [])]
        ((:sub ps) "some/fact" (partial swap! published conj))
        ((:pub host) {:kind :reg :name "some/fact" :key 1})
        ((:pub host) {:kind :reg :name "some/fact" :key 2})
        (is (= @published [{:kind :reg :name "some/fact" :key 1}
                           {:kind :reg :name "some/fact" :key 2}]))))

"However, if a `:reg` event repeats itself, `wrap-reg` will make sure it is only sent to the back-end once."
(fact wrap-reg-2
      (let [ps (ax/pubsub :name)
            host (-> {:pub (:pub ps)}
                     ax/wrap-reg)
            published (atom [])]
        ((:sub ps) "some/fact" (partial swap! published conj))
        ((:pub host) {:kind :reg :name "some/fact" :key 1})
        ((:pub host) {:kind :reg :name "some/fact" :key 2})
        ((:pub host) {:kind :reg :name "some/fact" :key 1}) ;; Sent twice
        (is (= @published [{:kind :reg :name "some/fact" :key 1}
                           {:kind :reg :name "some/fact" :key 2}]))))

"`wrap-reg` only de-duplicates `:reg` events.
Other kinds of events (e.g., `:fact`) pass normally."
(fact wrap-reg-3
      (let [ps (ax/pubsub :name)
            host (-> {:pub (:pub ps)}
                     ax/wrap-reg)
            published (atom [])]
        ((:sub ps) "some/fact" (partial swap! published conj))
        (doseq [_ (range 10)]
          ((:pub host) {:kind :fact :name "some/fact" :key 1 :data [2]}))
        (is (= (count @published) 10))))

[[:chapter {:title "wrap-late-subs"}]]
"One of the problems that exist in the pub/sub pattern is the problem of *late subscribers*.
A late subscriber is a subscriber that subscribes to a topic after content has already been published on that topic.
In our case this could be a view function that is consulted late in the process, after another view has already requested the data it was interested in.
Because we [de-duplicate registration](#wrap-reg), the events are not re-sent to the new subscriber.
This results in the new subscriber not having the information it needs."

"`wrap-late-subs` is intended to solve this problem by persisting all incoming events, and repeating relevant ones to new subscribers.
It is a connection middleware that augments the `:sub` method.
If no relevant publications have been made before the call to `:sub`, the callback will receive only new events."
(fact wrap-late-subs-1
      (let [ps (ax/pubsub :name)
            host (-> {:sub (:sub ps)}
                     ax/wrap-late-subs)
            published (atom [])]
        ((:sub host) "some/fact" (constantly nil)) ;; First subscription that causes events to come from the server
        ((:sub host) "some/fact" (partial swap! published conj)) ;; Our subscription
        ((:pub ps) {:kind :fact :name "some/fact" :key 1 :data [2]})
        ((:pub ps) {:kind :fact :name "some/fact" :key 2 :data [3]})
        (is (= @published [{:kind :fact :name "some/fact" :key 1 :data [2]}
                           {:kind :fact :name "some/fact" :key 2 :data [3]}]))))

"`wrap-late-subs` makes sure the order between subscribing and publishing is not important.
Even if `:sub` was called after the events have already been `:pub`lished, they will still be provided to the callback."
(fact wrap-late-subs-2
      (let [ps (ax/pubsub :name)
            host (-> {:sub (:sub ps)}
                     ax/wrap-late-subs)
            published (atom [])]
        ((:sub host) "some/fact" (constantly nil)) ;; First subscription that causes events to come from the server
        ((:pub ps) {:kind :fact :name "some/fact" :key 1 :data [2]})
        ((:pub ps) {:kind :fact :name "some/fact" :key 2 :data [3]})
        ((:sub host) "some/fact" (partial swap! published conj)) ;; Our subscription
        (is (= @published [{:kind :fact :name "some/fact" :key 1 :data [2]}
                           {:kind :fact :name "some/fact" :key 2 :data [3]}]))))

[[:chapter {:title "default-connection"}]]
"`default-connection` is a high-level function intended to provide a connection-map using the default settings.
It uses [ws-url](#ws-url) to create a WebSocket URL based on `js/document.location`,
it calls [connection](#connection) to create a connection to that URL,
and uses [wrap-feed-forward](#wrap-feed-forward) and [wrap-atomic-updates](#wrap-atomic-updates) to augment this connection to work well with views and queries."

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
