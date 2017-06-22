(ns gateway.core-test
  (:require [midje.sweet :refer :all]
            [gateway.core :refer :all]
            [clojure.core.async :as async]
            [di.core :as di]
            [cloudlog.interset :as interset]))

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
The facts and the rules together define the requirements in terms confidentiality and integrity.
This makes it fully capable of deciding who can see what, and who can do what."

"The gateway tier provides an external interface to the information tier.
Among its roles are authenticating users, querying the information tier for user rights and filtering results according to these rights.
It implements an HTTP server that provides access to [events](cloudlog-events.html#introduction) -- both creating and querying them."

[[:chapter {:title "identity-set"}]]
"Axiom has a unique method for managing user rights.
Instead of checking, for each action, if a user may or may not perform that action,
Axiom marks [each event that flows through its information tier](cloudlog-events.html#introduction) with two annotations:
it *reader set* and its *writer set*.
The reader set represents a mathematical set of users who are allowed to know about this event,
and the writer set represents a mathematical set of users who may have caused this event.
[The reader sets are used to guarantee confidentiality](cloudlog-events.html#confidentiality), 
while [writer sets guarantee integrity](cloudlog.html#integrity)."

"We treat these sets as mathematical sets, but in reality we need an efficient programatic representation for them.
We use [intersets](cloudlog.interset.html) for that.
The intersets are expressed in terms of *user groups*, which are (mathematical) sets of users that are provided by name, and not by content.
Without further information it is impossible to determine if a user `u` is or is not a member of a writer set or a reader set attached to an event."

"Instead of asking the question *which users belong in a readers/writers set*, the `identity-set` function
answers the opposite question: *what is the smallest known user set that `u` belongs to?*"

"`identity-set` is a [DI resource](di.html) that depends on the following resource:
- `database-chan` -- A `core.async` channel for retreiving events stored in a database (e.g., [DynamoDB](dynamo.html#database-chan))."

"With no further information (an empty collection as a second argument that we will discuss later), 
`identity-set` returns a set containing only the user ID.
We treat strings as singleton sets, so the user group `\"alice\"` is a group of users containing only one user -- `alice`."
(fact
 (let [$ (di/injector {:database-chan (async/chan)})]
   (module $)
   (di/startup $)
   (di/do-with! $ [identity-set]
                (identity-set "alice" []) => #{"alice"})))

"A user can belong to many groups, and is therefore a member of the intersection of all these groups with his or her own singleton group
(represented by his or her user ID).
But how do we determine which user belongs to which group?"

"Axiom has an original solution for that as well.
Instead of explicitly assigining users to groups (as some access control systems suggest),
Axiom uses [rules](cloudlog.html#defrule) to define user groups."

"Any rule that create derived facts of the form `[rule-name user args...]` defines a user group of the form `[rule-name args...]`,
which contains any `user` for which such a derived fact exists."

"`identity-set` takes as its second argument a list of *rule names* based on which we would like to search for identities.
Let us consider a Facebook-like application, in which users can have rights based on who their friends are, and which groups they own.
Two rules: `perm.AAA/friend` and `perm.BBB/group-owner` convey this relationships, respectively.
Now consider we query for user `alice`'s user-set with respect to these two rules."

(fact
 (def db-chan (async/chan))
 (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (def res
     (async/thread
       (di/do-with! $ [identity-set]
                    (identity-set "alice" ["perm.AAA/friend" "perm.BBB/group-owner"]))))))

"We will use the following function to safely retrieve request made to our mock database:"
(defn get-query []
  (let [[req chan] (async/alts!! [db-chan
                                  (async/timeout 1000)])]
    (when (nil? req)
      (throw (Exception. "Timed out waiting for DB request")))
    req))

"`identity-set` queries the database for all derived facts created by these rules, where `:key` is `alice`."
(fact
 (let [[query reply-chan] (get-query)]
   query => {:kind :fact
             :name "perm.AAA/friend"
             :key "alice"}
   ;; Alice has two friends: bob and charlie
   (doseq [friend ["bob" "charlie"]]
     (async/>!! reply-chan {:kind :fact
                            :name "perm.AAA/friend"
                            :key "alice"
                            :data [friend]
                            :ts 1000
                            :change 1
                            :readers #{}
                            :writers #{"perm.AAA"}}))
   (async/close! reply-chan))
 (let [[query reply-chan] (get-query)]
   query => {:kind :fact
             :name "perm.BBB/group-owner"
             :key "alice"}
   ;; Alice owns one group: "cats for free wifi"
   (async/>!! reply-chan {:kind :fact
                          :name "perm.BBB/group-owner"
                          :key "alice"
                          :data ["cats for free wifi"]
                          :ts 2000
                          :change 1
                          :readers #{}
                          :writers #{"perm.BBB"}})
   (async/close! reply-chan)))

"`alice`'s user-set is now the intersection between her own singleton set (`\"alice\"`),
and the groups of `bob`'s and `charlie`'s friends, as well as the owners of `cats for free wifi`."
(fact
 (async/<!! res) => (interset/intersection #{"alice"}
                                           #{[:perm.AAA/friend "bob"]}
                                           #{[:perm.AAA/friend "charlie"]}
                                           #{[:perm.BBB/group-owner "cats for free wifi"]}))

"TODO: Accumulation, integrity and confidentiality."
