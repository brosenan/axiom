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

"It takes a URL and a an optional keyword parameter `:ws-ch`, 
which defaults to a [function of the same name in the chord library](https://github.com/jarohen/chord#clojurescript).
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

"`connection` uses [wrap-atomic-updates](#wrap-atomic-updates) to handle atomic updates."
(fact connection-3
  (async done
         (go
           (let [the-chan (async/chan 10)
                 mock-ws-ch (fn [url]
                              (is (= url "ws://some-url"))
                              (go
                                {:ws-channel the-chan}))
                 host (ax/connection "ws://some-url"
                                     :ws-ch mock-ws-ch)]
             (let [test-chan (async/chan 10)]
               ((:sub host) "foo" #(go (async/>! test-chan %)))
               (async/>! the-chan {:message {:name "foo" :data ["bar"] :removed ["baz"] :change 1}})
               (is (= (async/<! test-chan) {:name "foo" :data ["baz"] :change -1}))
               (is (= (async/<! test-chan) {:name "foo" :data ["bar"] :change 1})))
             (done)))))

[[:chapter {:title "ws-url"}]]
"The [connection](#connection) function receives as parameter a WebSocket URL to connect to.
By default, this will be of the form `ws://<host>/ws`, where `<host>` represents the value of `js/document.location.host`."

"`ws-url` takes a host value and returns a WebSocket URL according to the above pattern."
(fact ws-url-1
      (is (= (ax/ws-url "localhost:8080") "ws://localhost:8080/ws")))

"In one case, where the host value corresponds to figwheel running on the local host,
we use `localhost:8080` instead of the original host, to direct WebSockets to Axiom rather than figwheel."
(fact ws-url-2
      (is (= (ax/ws-url "localhost:3449") "ws://localhost:8080/ws")))

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


[[:section {:title "wrap-atomic-updates"}]]
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
