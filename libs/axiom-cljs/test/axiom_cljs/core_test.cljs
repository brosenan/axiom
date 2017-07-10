(ns axiom-cljs.core-test
  (:require [cljs.test :refer-macros [is testing async]]
            [devcards.core :refer-macros [deftest]]
            [axiom-cljs.core :as ax]
            [cljs.core.async :as async])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [axiom-cljs.tests :refer [fact]]
                   [axiom-cljs.macros :refer [defview]]))

[[:chapter {:title "connection"}]]
"`connection` creates a connection to the host."

"It takes an optional keyword parameter `:ws-ch`, which defaults to a [function of the same name in the chord library](https://github.com/jarohen/chord#clojurescript).
It returns a channel to which it will provide a map containing the following keys:
1. `:from-host`: A `core.async` channel for delivering events from the host,
2. `:to-host`: A `core.async` channel for delivering events to the host,
3. `:err`: An `atom` holding the latest error, or `nil` if communication is intact.
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
                 host (async/<! (ax/connection :ws-ch mock-ws-ch))]
             (is (map? host))
             (is (contains? host :from-host))
             (is (contains? host :to-host))
             (is (= (:foo host) :bar))
             ;; The mock provides one channel that is used for both
             ;; :from-host and :to-host comms
             (async/>! (:to-host host) {:some :event})
             (is (= (async/<! (:from-host host)) {:some :event})))
           (done))))

[[:chapter {:title "defview"}]]
"The `defview` macro defines a *view*, which is a data structure that aggregates events and allows query over these events."

"`defview` four positional parameters:
1. The name of the view (a symbol).  This becomes the name of a function that retrieves data from that view.
2. An argument list for the view (a vector).  This becomes the argument list for the function.
3. A [connection](#connection) object.
4. A pattern of a fact or clause to be queried by this view."
(fact defview-1
      (let [host {}]
        (defview user-tweets [user]
          host
          [:tweetlog/tweets user tweet])
        (is (fn? user-tweets))))
