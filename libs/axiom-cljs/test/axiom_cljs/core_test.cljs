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
(fact connection
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
             (async/>! the-chan {:kind :init
                                 :name "some/name"
                                 :identity "alice"})
             (async/<! (async/timeout 1))
             (is (= @(:identity host) "alice"))
             
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

[[:chapter {:title "refresh-on-dev-ver"}]]
"When in development, [Figwheel](https://github.com/bhauman/lein-figwheel) can be used to update client-side artifacts as they are being modified, 
without having to reload the page.
However, when updating the [Cloudlog](cloudlog.html) logic, we wish to update content of the [views]axiom-cljs.macros.html#defview) and [queries](axiom-cljs.macros.html#defquery), and that cannot be done without refreshing."

"`update-on-dev-ver` is a middleware function that can be called on a connection, that subscribes to `axiom/perm-versions`.
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

