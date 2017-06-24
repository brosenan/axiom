(ns gateway.core-test
  (:require [midje.sweet :refer :all]
            [gateway.core :refer :all]
            [clojure.core.async :as async]
            [di.core :as di]
            [cloudlog.interset :as interset]
            [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]))

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

"`identity-set` is an *async function*, meaning that instead of returning an result it returns a channel to which a result will be
posted in the future."

"For an unauthenticated user (user ID of `nil`), `identity-set` returns a [universal interset](cloudlog.interset.html#universe),
that is to say that the user can be any user and we do not have any information about who that might be."
(fact
 (let [$ (di/injector {:database-chan (async/chan)})]
   (module $)
   (di/startup $)
   (di/do-with! $ [identity-set]
                (async/<!! (identity-set nil [:this :is :ignored])) => interset/universe)))

"Given a valid identity, but with no further information (an empty collection as a second argument that we will discuss later), 
`identity-set` returns (via a channel) a set containing only the user ID.
We treat strings as singleton sets, so the user group `\"alice\"` is a group of users containing only one user -- `alice`."
(fact
 (let [$ (di/injector {:database-chan (async/chan)})]
   (module $)
   (di/startup $)
   (di/do-with! $ [identity-set]
                (async/<!! (identity-set "alice" [])) => #{"alice"})))

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
Now consider we query for user `alice`'s identity set with respect to these two rules."

(fact
 (def db-chan (async/chan))
 (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (def res
     (di/do-with! $ [identity-set]
                  (identity-set "alice" ["perm.AAA/friend" "perm.BBB/group-owner"])))))

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

"`alice`'s identity set is now the intersection between her own singleton set (`\"alice\"`),
and the groups of `bob`'s and `charlie`'s friends, as well as the owners of `cats for free wifi`."
(fact
 (async/<!! res) => (interset/intersection #{"alice"}
                                           #{[:perm.AAA/friend "bob"]}
                                           #{[:perm.AAA/friend "charlie"]}
                                           #{[:perm.BBB/group-owner "cats for free wifi"]}))

[[:section {:title "Accumulation"}]]
"Axiom is an [event sourcing](https://martinfowler.com/eaaDev/EventSourcing.html) system.
This means that it doesn't store *state*, but rather events that record *change of state*."

"When discussing user rights, we need to refer to user rights at a specific point in time.
When a user makes some query, the result he or she is supposed to see depend on which groups he or she blongs to at *the moment of the query*.
To know this, we need to look at an [accumulation of the events](cloudlog-events.html#accumulating-events),
and not at the individual events,
that is, the current state of each group and not the individual changes in this state."

"For example, consider `alice` was once a friend of `eve` and then they parted ways.
This friendship will not appear in `alice`'s identity set."
(fact
  (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (let [res
         (di/do-with! $ [identity-set]
                      (identity-set "alice" ["perm.AAA/friend"]))
         [query reply-chan] (get-query)]
     (doseq [[ts change] [[1000 1] ;; Became friends at time 1000
                          [2000 -1] ;; Parted ways at time 2000
                          ]]
       (async/>!! reply-chan {:kind :fact
                              :name "perm.AAA/friend"
                              :key "alice"
                              :data ["eve"]
                              :ts ts
                              :change change
                              :writers #{"perm.AAA"}
                              :readers #{}}))
     (async/close! reply-chan)
     (async/<!! res) => #{"alice"} ;; No mention of friendship with eve
     )))

[[:section {:title "Integrity"}]]
"Integrity and confidentiality are important to take into consideration when calculating a user's identity set."

"Integrity is important because we do not want users to be able to assume permissions they are not entitled to.
This could happen if they publish a fact that looks like a derived fact from a rule giving them access to something the wouldn't have otherwise."

"To avoid this kind of problem we require that facts taken into consideration by `identity-set` are created by rules, 
so that their [writer sets include the fact's namespace](cloudlog.html#integrity)."

"Imagine `eve` wishing to gain access to `alice`'s inner-circle by forging an event that makes her one of `alice`'s friends."
(def forged-event
  {:kind :fact
   :name "perm.AAA/friend"
   :key "eve"
   :data ["alice"]
   :ts 3000
   :change 1
   :writers #{"eve"}
   :readers #{}})

"Note that `eve`'s identity set does not include `perm.AAA` (the fact's namespace), so the `:writers` set does not contain it.
It may contain other groups `eve` is a member of, but they are irrelevant to this discussion."

"When `eve` tries to access Axiom and perform operations (such as reading statuses on our Facebook-like application),
the gateway tier will first call `identity-set` to see which groups she belongs to.
In her request she will ask to identify as a friend of whoever possible, by placing `perm.AAA/friend` in the `rule-names` argument.
However, `identity-set` will know to ignore the forged fact and will not consider her a friend of `alice`."
(fact
 (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (let [res
         (di/do-with! $ [identity-set]
                      (identity-set "eve" ["perm.AAA/friend"]))
         [query reply-chan] (get-query)]
     (async/>!! reply-chan forged-event)
     (async/close! reply-chan)
     (async/<!! res) => #{"eve"} ;; No mention of friendship with alice
     )))

[[:section {:title "Confidentiality"}]]
"The role confidentiality plays here is a bit less obvious.
The identity set returned by `identity-set` is not directly visible to users.
However, if we do not take special measures to prefent this,
users can indirectly learn about things they should not."

"Consider a private message written by `alice` to `bob`:"
(def bobs-message
  {:kind :fact
   :name "social-app/message"
   :key "bob"
   :data ["alice" "I like you!"]
   :ts 1234
   :change 1
   :writers #{"alice"}
   :readers #{"bob"}})

"Note that the `:readers` set includes only `bob`, so `bob` alone can see this message
or even know it exists."

"`eve` suspects `alice`'s effection to `bob`, and is willing to go through extreme measures to see if she is correct.
To do so she creates her own application, and submits code containing the following rule:"
(clg/defrule evil-plan ["eve" msg]
  [:social-app/message "bob" "alice" msg] (clg/by "alice"))

"Note that although the message facts were created by a different application,
Axiom does not restrict that fact to be used only by that application.
This is a fundemental principle in Axiom: User data belongs to *users*, not *applications*."

"If we apply `eve`'s rule to `bob`'s message we get the following event:"
(fact
 (let [em (ev/emitter evil-plan)]
   (def evil-facts (em bobs-message))
   evil-facts => [{:kind :fact
                   :name "gateway.core-test/evil-plan"
                   :key "eve"
                   :data ["I like you!"]
                   :ts 1234
                   :change 1
                   :writers #{"gateway.core-test"}
                   :readers #{"bob"}}]))

"Axiom's confidentiality mechanism made sure that although it was `eve`'s evil rule that created this event,
`eve` herself cannot read it, as its `:readers` set contains only `bob`."

"But this is where `identity-set` could (potentially) help `eve` with her plan.
Her exploit consists of the following facts she stores in Axiom:"
(comment
  [{:kind :fact
    :name "eve-the-evil/exploit"
    :key "love-life"
    :data ["It's toast"]
    :ts 2345
    :change 1
    :writers #{"eve"}
    :readers #{[[:gateway.core-test/evil-plan "I like you!"]]}}
   {:kind :fact
    :name "eve-the-evil/exploit"
    :key "love-life"
    :data ["It's over"]
    :ts 2346
    :change 1
    :writers #{"eve"}
    :readers #{[[:gateway.core-test/evil-plan "I love you!"]]}}
   {:kind :fact
    :name "eve-the-evil/exploit"
    :key "love-life"
    :data ["There's still hope..."]
    :ts 2347
    :change 1
    :writers #{"eve"}
    :readers #{[[:gateway.core-test/evil-plan "I hate you!"]]}}])

"In all these facts she sets the `:readers` set to groups defined by the rule she created herself.
Each of these facts will be visible to her if `alice` sent `bob` a message with the text that appears in the group's description."

"Now all `eve` has to do is make a query (through the gateway) and look for a fact `eve-the-evil/exploit` with key `love-life`, 
providing `gateway.core-test/evil-plan` in the rule-list.
Then she just needs to wait and see if any of her guesses got lucky."

"To make sure `eve`'s evel plan cannot succeed, 
`identity-set` only takes into consideration results for which the user for which the query is made is allowed to know about.
In our case, `eve` is not allowed to know about the different `gateway.core-test/evil-plan` results,
and therefore they are not added to her identity set."
(fact
  (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (let [res
         (di/do-with! $ [identity-set]
                      (identity-set "eve" ["perm.AAA/friend"]))
         [query reply-chan] (get-query)]
     (async/pipe (async/to-chan evil-facts) reply-chan)
     (async/<!! res) => #{"eve"} ;; No results from evil-plan
     )))

"One subtle point to note is the potentially circular relationship between a user's identity set and the confidentiality requirement.
We need to know what a user's identity set is in order to know what facts to consider for the identity set.
We resolve this circular relationship by making the confidentiality requirement event more strict than it (theorecitally) have to be.
For the purpose of group membership (the identity set) we only consider facts that are accessible to the user itself,
without looking up group membership."

[[:chapter {:title "authenticator"}]]
"The starting point for [identity-set](#identity-set) is an authenticated user identity.
But how do we get one?
In practice, user authentication is a difficult problem with many possibilities and serious trade-offs between them.
In this library we defer this problem.
Instead of defining a single authentication scheme, we provide an extension point in the form of a [DI resource](di.html),
and expect concrete implementations to implement it."

"The interface we define here is simple.
An authenticator is a [Ring middleware](https://github.com/ring-clojure/ring/wiki/Concepts#middleware),
that is, a higher-order function that takes and returns a [Ring handler](https://github.com/ring-clojure/ring/wiki/Concepts#handlers).
It should be placed after middleware for parsing the request, e.g., handling cookies and parameters,
but before any middleware that performs operations on behalf of a user."

"Upon successful authentication, an `:identity` field should be added to the request map."

[[:section {:title "dummy-authenticator"}]]
"To define this extension point, as well as to allow testing of this library and \"raw\" Axiom in general,
we provide an *unsafe*, `dummy-authenticator` which does something you are not supposed to do on the internet:
trust the user...
The `dummy-authenticator` takes the user's identity from a query parameter or a cookie."

"The `dummy-authenticator` is based on the `:use-dummy-authenticator` configuration parameter.
If it exists (regardless of its value), a `dummy-authenticator` will be provided as the `authenticator`."
(fact
 (let [$ (di/injector {:use-dummy-authenticator true})]
   (module $)
   (di/startup $)
   (di/do-with! $ [authenticator]
                (def dummy-authenticator authenticator))))

"`dummy-authenticator` assumes the existence of `:cookies` and `:params` keys in the request,
so the [cookie](https://github.com/ring-clojure/ring/wiki/Cookies) and [params](https://github.com/ring-clojure/ring/wiki/Parameter-Middleware)
middleware should be wrapped around it."

"If a request contains a cookie named `user_identity`, the dummy authenticator takes its value as the user's identity."
(fact
 (let [id (atom nil)
       handler (fn [req res raise]
                 (reset! id (:identity req)))
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {"user_identity" "foobar"}
         :params {}} :res :raise)
   @id => "foobar"))

"If a query parameter named `_identity` exists, its value becomes the `:identity`.
Additionally, the new value is stored in a cookie for future reference."
(fact
 (let [resp (atom nil)
       respond (fn [res]
                 (reset! resp res))
       handler (fn [req res raise]
                 (res {:body (str "Hello, " (:identity req))}))
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {"_identity" "barfoo"}} respond :raise)
   @resp => {:body "Hello, barfoo"
             :cookies {"user_identity" "barfoo"}}))

"Adding a cookie works in concert with other cookies..."
(fact
 (let [resp (atom nil)
       respond (fn [res]
                 (reset! resp res))
       handler (fn [req res raise]
                 (res {:body "Hello"
                       :cookies {"some" "cookie"}}))
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {"_identity" "barfoo"}} respond :raise)
   @resp => {:body "Hello"
             :cookies {"user_identity" "barfoo"
                       "some" "cookie"}}))

"If both a cookie and a parameter exist with different identity values, the *parameter wins*."
(fact
 (let [id (atom nil)
       handler (fn [req res raise]
                 (reset! id (:identity req)))
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {"user_identity" "foobar"}
         :params {"_identity" "barfoo"}} :res :raise)
   @id => "barfoo"))

"If none are present, the authenticator does not add an `:identity` entry to the request."
(fact
 (let [res (atom nil)
       respond (fn [r] (reset! res r))
       handler (fn [req res raise]
                 (when (contains? req :identity)
                   (throw (Exception. "Request should not have an :identity field")))
                 (res {:body "Hello"}))
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {}} respond :raise)
   @res => {:body "Hello"}))
