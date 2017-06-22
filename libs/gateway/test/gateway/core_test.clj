(ns gateway.core-test
  (:require [midje.sweet :refer :all]
            [gateway.core :refer :all]
            [clojure.core.async :as async]
            [di.core :as di]))

[[:chapter {:title "Introduction"}]]
"This library implement Axiom's gateway tier.
Axiom's architecture is a bit different than that of traditional web apps.
The server side of traditional apps consists of a *logic tier* and a *data tier*.
The logic tier consists of a web server that is typically extended with application code
to answer requests coming from the client side (the *presentation tier*).
To do that it typically consults a database comprising the data tier."

"This traditional three-tier architecture puts a lot of burden on the shoulders of the logic tier.
On the one hand, the client side can be malicious -- an attacker trying to make unauthorized access to user data,
and on the other hand, the data tier stores plain, unencapsulated data, so it typically does not protect itself agains such attacks.
The logic tier alone is thus responsible for proteecting user data against attackers,
while also being responsible for properly serving the application."

"Axiom makes a different distinction between tiers on the server side.
It upgrades the data tier one step up the [DIKW pyramid](https://en.wikipedia.org/wiki/DIKW_pyramid) to become an *information tier*,
While downgrading the logic tier to being a mere *gateway* to the information tier.
The information tier stores both *facts* and *rules*, and is capable of answering queries based on them.
The facts and the rules together define the requirements in terms [confidentiality](cloudlog-events.html#confidentiality)
 and [integrity](cloudlog.html#integrity).
This makes it fully capable of deciding who can see what, and who can do what."

"The gateway tier provides an external interface to the information tier.
Among its roles are authenticating users, querying the information tier for user rights and filtering results according to these rights.
It implements an HTTP server that provides access to [events](cloudlog-events.html#introduction) -- both creating and querying them."

[[:chapter {:title "identity-set"}]]
"The `identity-set` function converts a user ID and a collection of relation names into an [interset](cloudlog.interset.html)
that can be used as a [writer or reader set](cloudlog-events.html#introduction) for that user."

"`identity-set` is a [DI resource](di.html) that depends on the following resource:
- `database-chan` -- A `core.async` channel for retreiving events stored in a database (e.g., [DynamoDB](dynamo.html#database-chan))."

"For an empty collection of relation names, it returns a set containing only the user ID."
(fact
 (let [$ (di/injector {:database-chan (async/chan)})]
   (module $)
   (di/startup $)
   (di/do-with! $ [identity-set]
                (identity-set "foo" []) => #{"foo"})))

"For each relation name in the given collection, `identity-set` will make a query in the database."
(fact
 (def db-chan (async/chan))
 (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (def res
     (async/thread
       (di/do-with! $ [identity-set]
                    (identity-set "foo" ["bar" "baz"]))))))

"For user `u` and relation `rel` it will query the database using `:name = rel` and `:key = u`."
(fact
 (defn get-query []
   (let [[req chan] (async/alts!! [db-chan
                                   (async/timeout 1000)])]
     (when (nil? req)
       (throw (Exception. "Timed out waiting for DB request")))
     req))
 
 (let [[query reply-chan] (get-query)]
   query => {:kind :fact
             :name "bar"
             :key "foo"}
   ;; We will reply to this with :data = quux
   (async/>!! reply-chan {:kind :fact
                          :name "bar"
                          :key "foo"
                          :data ["quux"]})
   (async/close! reply-chan))
 (let [[query reply-chan] (get-query)]
   query => {:kind :fact
             :name "baz"
             :key "foo"}
   ;; We will reply with :data = boo and :data = goo
   (async/>!! reply-chan {:kind :fact
                          :name "baz"
                          :key "foo"
                          :data ["boo"]})
   (async/>!! reply-chan {:kind :fact
                          :name "baz"
                          :key "foo"
                          :data ["goo"]})
   (async/close! reply-chan)))

"For each result we should get a term (sequence) that has the relation name as its first element and the `:data` as the rest of its elements."
(fact
 (async/<!! res) => #{"foo"
                      ["bar" "quux"]
                      ["baz" "boo"]
                      ["baz" "goo"]})
