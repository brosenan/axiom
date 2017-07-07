(ns gateway.core-test
  (:require [midje.sweet :refer :all]
            [gateway.core :refer :all]
            [clojure.core.async :as async]
            [di.core :as di]
            [cloudlog.interset :as interset]
            [cloudlog.core :as clg]
            [cloudlog-events.core :as ev]
            [ring.util.codec :as codec]
            [clojure.edn :as edn]
            [gniazdo.core :as ws]
            [org.httpkit.server :as htsrv]
            [org.httpkit.client :as http]))

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
       handler (fn [req]
                 (reset! id (:identity req))
                 {:status 200
                  :body "hi"})
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {"user_identity" {:value "foobar"}}
         :params {}})
   @id => "foobar"))

"If a query parameter named `_identity` exists, its value becomes the `:identity`.
Additionally, the new value is stored in a cookie for future reference."
(fact
 (let [handler (fn [req]
                 {:body (str "Hello, " (:identity req))})
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {"_identity" "barfoo"}})
   => {:body "Hello, barfoo"
       :cookies {"user_identity" {:value "barfoo"}}}))

"Adding a cookie works in concert with other cookies..."
(fact
 (let [handler (fn [req]
                 {:body "Hello"
                  :cookies {"some" {:value "cookie"}}})
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {"_identity" "barfoo"}})
   => {:body "Hello"
       :cookies {"user_identity" {:value "barfoo"}
                 "some" {:value "cookie"}}}))

"If both a cookie and a parameter exist with different identity values, the *parameter wins*."
(fact
 (let [id (atom nil)
       handler (fn [req]
                 (reset! id (:identity req))
                 {:status 200
                  :body "hi"})
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {"user_identity" {:value "foobar"}}
         :params {"_identity" "barfoo"}})
   @id => "barfoo"))

"If none are present, the authenticator does not add an `:identity` entry to the request."
(fact
 (let [handler (fn [req]
                 (when (contains? req :identity)
                   (throw (Exception. "Request should not have an :identity field")))
                 {:body "Hello"})
       app (-> handler
               dummy-authenticator)]
   (app {:cookies {}
         :params {}})
   => {:body "Hello"}))

[[:chapter {:title "wrap-authorization"}]]
"`wrap-authorization` is a DI resource that combines [authenticator](#authenticator) and [identity-set](#identity-set) to one middleware function
that authenticates a user and stores his or her identity set as the :identity-set field in the request."

"`wrap-authorization` depends on `authenticator` and `identity-set`.
`authenticator` provides an `:identity` field, and `identity-set` converts this identity to an identity set.
In order to do so, it must be given a `rule-list` parameter.
By default, this parameter will be an empty list."
(fact
 (let [$ (di/injector {:use-dummy-authenticator true
                       :identity-set (fn [id rules]
                                       (async/go
                                         #{id rules}))})]
   (module $)
   (di/startup $)
   (di/do-with! $ [wrap-authorization]
                (let [req-with-id-set (async/chan 10)
                      handler (fn [req]
                                (async/>!! req-with-id-set req)
                                {:status 200})
                      app (wrap-authorization handler)]
                  (app {:cookies {"user_identity" {:value "foo"}}})
                  (let [[result chan] (async/alts!! [req-with-id-set
                                                     (async/timeout 1000)])]
                    chan => req-with-id-set
                    (:identity-set result) => #{"foo" []})))))

"If a header named `Axiom-Id-Rules` exists, its content is decoded as EDN and treated as the `rule-list`."
(fact
 (let [$ (di/injector {:use-dummy-authenticator true
                       :identity-set (fn [id rules]
                                       (async/go
                                         #{id rules}))})]
   (module $)
   (di/startup $)
   (di/do-with! $ [wrap-authorization]
                (let [req-with-id-set (async/chan 10)
                      handler (fn [req]
                                (async/>!! req-with-id-set req)
                                {:status 200})
                      app (wrap-authorization handler)]
                  (app {:cookies {"user_identity" {:value "foo"}}
                        :headers {"Axiom-Id-Rules" ['foo-rule 'bar-rule]}})
                  (let [[result chan] (async/alts!! [req-with-id-set
                                                     (async/timeout 1000)])]
                    (:identity-set result) => #{"foo" ['foo-rule 'bar-rule]})))))

[[:chapter {:title "version-selector"}]]
"Axiom is intended to be multi-tenant.
In is not only intended to host multiple applications, but also to host different *versions* of the same application.
Control over this allows features to be introduced gradually and things like A/B testing."

"A `version-selector` in Axiom is a DI resource that acts as Ring middleware, and adds a `:app-version` key to the request."

[[:section {:title "cookie-version-selector"}]]
"The most straightforward way to determine a version is by taking it off a cookie.
By managing versions in cookies, applications can manipulate them through Javascript.
The `cookie-version-selector` is a middleware function that does just that.
It assumes a `:cookies` field exists in the request (i.e., the [cookies middleware](https://github.com/ring-clojure/ring/wiki/Cookies) is wrapping it),
and that a cookie named `app-version` is defined.
It copies its content to the `:app-version` key in the request."
(fact
 (let [ver (atom nil)
       handler (fn [req]
                 (reset! ver (:app-version req)))
       app (cookie-version-selector handler)]
   (app {:cookies {"app-version" {:value "ver123"}}})
   @ver => "ver123"))

[[:section {:title "Picking a Version based on Domain"}]]
"But how do we get the initial version value?
The complete answer is complicated and involves a few factors.
The best way to determine a version starts with the domain name.
Since Axiom is multi-tenant, different applications are expected to have different domain names.
The application provides own their domains, and direct traffic to an instance of Axiom by managing DNS records.
The `HOST` HTTP header conveys the domain name used by the user when accessing the application, and can be used to determine which application
needs to run.
But how do we determine the version?"

"First, we need to know who to trust.
We want to take the domain owner's word for it.
A common way to establish this kind of trust is by asking the domain owner to set up some `TXT` DNS record, with some fixed text and his or her identity,
Something like: `TXT: Axiom Identity: foo@example.com`, where `foo@example.com` is an identity verifyable by the [authenticator](#authenticator) we use.
This tells Axiom the the owner of the domain trusts the mentioned user to manage content in Axiom for that domain."

"The mapping between domain names and user identities should be queried using a DNS lookup and cached.
Then a query should be made for a fact stated by that user, connecting the domain to a version.
If more than one such version exists, one of them should be picked at random.
Javascript code that runs on behalf of the application can update the `app-version` cookie with another version
if a random choice is not the correct one for this application."

"At this point we do not implement this selector."

[[:section {:title "dummy-version-selector"}]]
"To support development, we provide `dummy-version-selector`.
It is a provider of the `version-selector` resource.
It depends on `:dummy-version` -- a string containing a version."
(fact
 (let [$ (di/injector {:dummy-version "ver456"})]
   (module $)
   (di/startup $)
   (di/do-with! $ [version-selector]
                (def ver-selector version-selector))))

"If an `app-version` cookie already exists, the `dummy-version-selector` lets it be, to allow Javascript code to manipulate the version as it sees fit,
and allowes the `cookie-version-selector` to expose its value as `:app-version`."
(fact
 (let [ver (atom nil)
       handler (fn [req]
                 (reset! ver (:app-version req)))
       app (ver-selector handler)]
   (app {:cookies {"app-version" {:value "ver123"}}})
   @ver => "ver123"))

"If an `app-version` cookie does not exist, `dummy-version-selector` creates one with the value from `:dummy-version`."
(fact
 (let [ver (atom nil)
       handler (fn [req]
                 (reset! ver (:app-version req)))
       app (ver-selector handler)]
   (app {:cookies {}})
   @ver => "ver456"))

[[:chapter {:title "static-handler"}]]
"Axiom applications serve static files that originate in the `/static` directory of the source repository when [pushing a new version](migrator.html#push-handler).
The content of these files is [hashed](permacode.hasher.html), and the hash-code is stored in `axiom/perm-versions` events, keyed by the git version of the application."

"`static-handler` is a DI resource that provides a [Ring handler](https://github.com/ring-clojure/ring/wiki/Concepts#handlers)
for retreiving static files.
It is depends on the following:
1. `version-selector`, to determine the version for which to retrieve the static file.
2. `database-chan`, to retrieve `axiom/perm-versions` events.
3. `hasher`, to retrieve the static content."
(fact
 (let [unhash (fn [h]
                (when (not= h "the-hash-code")
                  (throw (Exception. (str "Bad hashcode: " h))))
                (.getBytes "the content"))
       $ (di/injector {:dummy-version "ver123"
                       :database-chan db-chan
                       :hasher [:hash unhash]})]
   (module $)
   (di/startup $)
   (di/do-with! $ [static-handler]
                (def static-handler static-handler))))

"`static-handler` handles requests by querying the database for the `axiom/perm-versions` for the given version."
(fact
 (def response (async/thread
                 (static-handler {:cookies {} :uri "/foo.html"})))
 (let [[query resp-chan] (get-query)]
   query => {:kind :fact
             :name "axiom/perm-versions"
             :key "ver123"}
   (async/>!! resp-chan {:kind :fact
                         :name "axiom/perm-versions"
                         :key "ver123"
                         :data [{} {"/foo.html" "the-hash-code"}]})
   (async/close! resp-chan)))

"Upon receiving the response, `static-handler` gets the file's content from the hasher, and creates a `200` response."
(fact
 (let [[res chan] (async/alts!! [response
                                 (async/timeout 1000)])]
   chan => response
   (:status res) => 200
   (:body res) => #(instance? java.io.InputStream %)
   (slurp (:body res)) => "the content"
   ;; Content-Type is assigned according to file extension
   (get-in res [:headers "Content-Type"]) => "text/html"))

"If the path does not exist in that version of the application,
`static-handler` responds with a status of `404`."
(fact
 (def response
   (async/thread
     (static-handler {:cookies {}
                      :uri "/bar.html"})))
 ;; Same response as before...
 (let [[query resp-chan] (get-query)]
   (async/>!! resp-chan {:kind :fact
                         :name "axiom/perm-versions"
                         :key "ver123"
                         :data [{} {"/foo.html" "the-hash-code"}]})
   (async/close! resp-chan))
 (let [[res chan] (async/alts!! [response
                                 (async/timeout 1000)])]
   chan => response
   (:status res) => 404))

[[:chapter {:title "event-gateway"}]]
"`event-gateway` is a function that sets up a bidirectional event filter for events flowing between a client and a server.
It is intended to support confidentiality and integrity, protecting both the connected client and the rest of the users against this client."

"It is a DI resource that depends on [rule-version-verifier](#rule-version-verifier)."
(let [$ (di/injector {:rule-version-verifier
                      (fn [ver writers]
                        (contains? writers (str "hash-in-" ver)))})]
  (module $)
  (di/startup $)
  (di/do-with! $ [event-gateway]
               (def event-gateway event-gateway)))

"This function works on a bidirectional channel pair of the form `[c2s s2c]`, where `c2s` stands for *client to server*,
and `s2c` stands for *server to client*.
`event-gateway` takes such a pair as its first parameter, and returns such a pair.
The pair it is given is intended to connect the gateway to the client (`[c-c2s c-s2c]`),
and the returned pair (`[s-c2s s-s2c]`) is intended to connect the gateway to the server (e.g., to [an event bridge](rabbit-microservices.html#event-bridge)).
Apart for a channel-pair, `event-gateway` takes the [identity set](#identity-set) of the connected user and the `:app-version` of the application the user logged on to."
(fact
 (def c-c2s (async/chan 10))
 (def c-s2c (async/chan 10))
 (let [[s-c2s s-s2c] (event-gateway [c-c2s c-s2c] #{"alice" :some-cred} "ver123")]
   (def s-c2s s-c2s)
   (def s-s2c s-s2c)))

"Once called, events pushed to `c-c2s` flow to `s-c2s`..."
(fact
 (async/>!! c-c2s {:kind :fact
                   :name "foo"
                   :writers #{"alice"}
                   :readers #{}})
 (async/<!! s-c2s) => {:kind :fact
                       :name "foo"
                       :writers #{"alice"}
                       :readers #{}})

"... and from `s-s2c` to `c-s2c`:"
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "foo"
                   :writers #{"alice"}
                   :readers #{}})
 (async/<!! c-s2c) => {:kind :fact
                       :name "foo"
                       :writers #{"alice"}
                       :readers #{}})

[[:section {:title "Integrity Protection for the Client"}]]
"The `event-gateway` will pass an event from the server to the client if one of three conditions hold for the event's `:writers` set.
First, an event will pass if the user's `:identity-set` is included in the `:writers` set, that is, the user could have written this event."
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "foo"
                   :writers #{"alice"}
                   :readers #{}})
 (let [[ev chan] (async/alts!! [c-s2c
                                (async/timeout 1000)])]
   chan => c-s2c))

"Second, an event will pass if it was written by the application the client logged on to."
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "foo"
                   :writers #{"hash-in-ver123"}
                   :readers #{}})
 (let [[ev chan] (async/alts!! [c-s2c
                                (async/timeout 1000)])]
   chan => c-s2c))

"Third, an event will pass if it is self-verified, that is, if its `:writers` set is a subset of `#{ns}`, where `ns` is the event's namespace."
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "some-ns/foo"
                   :writers #{"some-ns" :some-other-cred}
                   :readers #{}})
 (let [[ev chan] (async/alts!! [c-s2c
                                (async/timeout 1000)])]
   chan => c-s2c))

"However, it will be blocked in any other case."
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "foo"
                   :writers #{"unauthorized"}
                   :readers #{}})
 (let [to (async/timeout 100)
       [ev chan] (async/alts!! [c-s2c to])]
   ;; We expect a timeout...
   chan => to))

[[:section {:title "Integrity Protection Against Mallicious Clients"}]]
"A mallicious client may attempt to state a fact on behalf of someone else.
To protect against that, we block events for which the `:writers` set does not represent a *superset* of the user's `:identity-set`.
In other words, a user may only publish a fact if he or she belongs to the `:writers` set of that fact."
(fact
 (async/>!! c-c2s {:kind :fact
                   :name "foo"
                   :writers #{"bob" :some-cred}
                   :readers #{}})
 (let [to (async/timeout 100)
       [ev chan] (async/alts!! [s-c2s to])]
   ;; We expect a timeout...
   chan => to))

[[:section {:title "Confidentiality Protection"}]]
"For confidentiality, we need to make sure that regardless of what the client requested, events from the server only reach it if the client's `identity-set` is included
in the event's `:readers` set, that is, if all possible users who can identify themselves this way are allowed to see this event."
(fact
 (async/>!! s-s2c {:kind :fact
                   :name "foo"
                   :writers #{"alice"}
                   :readers #{"not-alice" :some-cred}})
 (let [to (async/timeout 100)
       [ev chan] (async/alts!! [c-s2c to])]
   ;; We expect a timeout...
   chan => to))

[[:section {:title "Registration Events"}]]
"Valid `:reg`istration events should pass from client to server."
(fact
 (async/>!! c-c2s {:kind :reg
                   :name "foo"
                   :key "bar"})
 (let [[ev chan] (async/alts!! [s-c2s
                                (async/timeout 1000)])]
   chan => s-c2s))

"However, if a `:reg` event is missing either a `:name` or a `:key`, it is blocked by the `event-gateway`."
(fact
 (async/>!! s-c2s {:kind :reg
                   :name "foo"})
 (async/>!! s-c2s {:kind :reg
                   :key "bar"})
 (let [to (async/timeout 100)
       [ev chan] (async/alts!! [s-c2s to])]
   ;; We expect a timeout...
   chan => to))

[[:chapter {:title "websocket-handler"}]]
"The gateway tier exposes Axiom's information tier through [WebSockets](https://en.wikipedia.org/wiki/WebSocket).
The `websocket-handler` is a RING handler that provides this functionality.
It assumes it is wrapped with [chord](https://github.com/jarohen/chord)'s `wrap-websocket-handler` or something equivalent,
which translates WebSockets to `core.async` channels."

"`websocket-handler` is a DI resource that depends on [event-gateway](#event-gateway) to filter events,
and `event-bridge` (e.g., [this](rabbit-microservices.html#event-bridge)) to interact with the information tier.
It also depends on the middleware resources [wrap-websocket-handler](#wrap-websocket-handler), [wrap-authorization](#wrap-authorization)
and [version-selector](#version-selector)."
(fact
 (def pair [(async/chan 10) (async/chan 10)])
 (def ws-pair [(async/chan 10) (async/chan 10)])
 (let [$ (di/injector {:event-gateway
                       (fn [[c-c2s c-s2c] id-set app-ver]
                         (let [s-c2s (async/chan 10 (comp
                                                     (map #(assoc % :id-set id-set))
                                                     (map #(assoc % :app-ver app-ver))))
                               s-s2c (async/chan 10 (comp
                                                     (map #(assoc % :id-set id-set))
                                                     (map #(assoc % :app-ver app-ver))))]
                           (async/pipe c-c2s s-c2s)
                           (async/pipe s-s2c c-s2c)
                           [s-c2s s-s2c]))
                       :event-bridge
                       (fn [[c2s s2c]]
                         (async/pipe c2s (first pair))
                         (async/pipe (second pair) s2c))
                       :wrap-authorization
                       (fn [handler]
                         (fn [req]
                           (-> req
                               (assoc :identity-set :the-id-set)
                               handler)))
                       :version-selector
                       (fn [handler]
                         (fn [req]
                           (-> req
                               (assoc :app-version :the-app-ver)
                               handler)))
                       :wrap-websocket-handler
                       (fn [handler]
                         (fn [req]
                           (-> req
                               (assoc :ws-channel-pair ws-pair)
                               handler)))})]
   (module $)
   (di/startup $)
   (di/do-with! $ [websocket-handler]
                (def websocket-handler websocket-handler))))

"`websocket-handler` is a RING handler function that assumes a `:ws-channel-pair` field exists in the request,
which has the form `[c2s s2c]`, where `c2s` contains messages went from the client to the server,
and `s2c` can take messages from the server to the client.
(Please not that chord's `wrap-websocket-handler` provides only one channel (`:ws-channel`), which is bidirectional.
We use our own `wrap-websocket-handler` middleware similar to chord's, which supports a 3-parameter handler and provides the channel as a pair).
To use `websocket-handler` with `wrap-websocket-handler` one needs to add a wrapper that converts `:ws-channel` to `:ws-channel-pair`.)"
(fact
 (let [[c2s s2c] ws-pair]
   (websocket-handler {})
   (async/>!! c2s {:foo :bar})
   (let [[ev chan] (async/alts!! [(first pair) (async/timeout 1000)])]
     chan => (first pair)
     (:foo ev) => :bar
     (:id-set ev) => :the-id-set
     (:app-ver ev) => :the-app-ver)
   (async/>!! (second pair) {:bar :foo})
   (let [[ev chan] (async/alts!! [s2c (async/timeout 1000)])]
     chan => s2c
     (:bar ev) => :foo
     (:id-set ev) => :the-id-set
     (:app-ver ev) => :the-app-ver)))

[[:section {:title "ring-handler"}]]
"`ring-handler` is the main entry-point for the gateway tier."

"It is a DI resource that depends on [websocket-handler](#websocket-handler) and [static-handler](#static-handler) -- the two handlers it serves."
(fact
 (def the-handler (atom nil))
 (let [$ (di/injector {:static-handler (fn [req]
                                         (reset! the-handler :static)
                                         {:status 200
                                          :body "foo bar"})
                       :websocket-handler (fn [req]
                                            (reset! the-handler :ws)
                                            {:status :200
                                             :body "bar foo"})})]
   (module $)
   (di/startup $)
   (di/do-with! $ [ring-handler]
                (def ring-handler ring-handler))))

"The location `/ws` is handled by the [websocket-handler](#websocket-handler)."
(fact
 (ring-handler {:request-method :get
                :uri "/ws"})
 @the-handler => :ws)

"Any other location is handled by the `/static` handler."
(fact
 (ring-handler {:request-method :get
                :uri "/something/else"})
 @the-handler => :static)

[[:chapter {:title "http-server"}]]
"At its essence, the gateway tier is an HTTP server, represented by the `http-server` resource."

"As a DI resource, it depends on [ring-handler](#ring-handler) to provide content, and `http-config` to provide parameters such as `:ip` and `:port`.
See [http-kit's documentation](http://www.http-kit.org/server.html#options) for the complete list."
(fact
 (let [$ (di/injector {:http-config {:port 44444}
                       :ring-handler (fn [req]
                                       {:status 200
                                        :body "hello"})})]
   (module $)
   (di/startup $)
   (di/do-with! $ [http-server]
                (let [resp @(http/get "http://localhost:44444")
                      {:keys [status headers body error]} resp]
                  (when error
                    (throw error))
                  status => 200
                  (slurp body) => "hello"))
   (di/shutdown $)))

"The server resource contains information about the server, such as its `:local-port` -- the actual port that was allocated to it on the local machine
(as in the case where we provided `:port 0`, requesting it to choose a random available port)."
(fact
 (let [$ (di/injector {:http-config {:port 0}
                       :ring-handler (fn [req]
                                       {:status 200
                                        :body "hello"})})]
   (module $)
   (di/startup $)
   (di/do-with! $ [http-server]
                (let [resp @(http/get (str "http://localhost:" (:local-port http-server)))
                      {:keys [status headers body error]} resp]
                  (when error
                    (throw error))
                  status => 200))
   (di/shutdown $)))

"The `ring-handler` is wrapped with a [parameters middleware](https://github.com/ring-clojure/ring/wiki/Parameters),
so query params are parsed into the `:params` field of the request."
(fact
 (let [$ (di/injector {:http-config {:port 44444}
                       :ring-handler (fn [req]
                                       {:status 200
                                        :body (str "hello, " (get-in req [:params "name"]))})})]
   (module $)
   (di/startup $)
   (di/do-with! $ [http-server]
                (let [resp @(http/get "http://localhost:44444?name=foo")
                      {:keys [status headers body error]} resp]
                  (when error
                    (throw error))
                  status => 200
                  (slurp body) => "hello, foo"))
   (di/shutdown $)))

"It is also wrapped with the [cookies middleware](https://github.com/ring-clojure/ring/wiki/Cookies)."
(fact
 (let [$ (di/injector {:http-config {:port 44444}
                       :ring-handler
                       (fn [req]
                         {:status 200
                          :body (str "hello, " (get-in req [:cookies "name" :value]))})})]
   (module $)
   (di/startup $)
   (di/do-with! $ [http-server]
                (let [resp @(http/get "http://localhost:44444"
                                      {:headers {"Cookie" "name=foo"}})
                      {:keys [status headers body error]} resp]
                  (when error
                    (throw error))
                  status => 200
                  (slurp body) => "hello, foo"))
   (di/shutdown $)))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "rule-version-verifier"}]]
"To maintain the [integrity of query results](#integrity-poll), we need to associate the `:writers` set of an event to a version of an application
(given as the `:app-version` key in the request)."

"`rule-version-verifier` is a DI resource that depends on `database-chan`."
(fact
 (let [$ (di/injector {:database-chan db-chan})]
   (module $)
   (di/startup $)
   (di/do-with! $ [rule-version-verifier]
                (def rule-version-verifier rule-version-verifier))))

"It is a function that takes two arguments:
1. a version (the `:app-version` from the request, as provided by the [version-selector](#version-selector)), and
2. the `:writers` set of an event."
(fact
 (def result (async/thread
               (rule-version-verifier "ver123" #{"some-hash" :something-else}))))

"It queries the database for a `axiom/perm-versions` fact associated with the given version."
(fact
 (let [[q reply-chan] (get-query)]
   q => {:kind :fact
         :name "axiom/perm-versions"
         :key "ver123"}
   (async/>!! reply-chan {:kind :fact
                          :name "axiom/perm-versions"
                          :key "ver123"
                          :data [{'foo 'some-hash
                                  'bar 'some-other-hash} {}]})
   (async/close! reply-chan)))

"Because `some-hash` exists in the `axiom/perm-versions` for this version, the function should return `true`."
(fact
 (async/<!! result) => true)

"If the `:writers` set does not mention a hash that exists, we return `false`."
(fact
 (def result (async/thread
               (rule-version-verifier "ver234" #{"some-hash-that-does-not-exist" :something-else})))
 (let [[q reply-chan] (get-query)]
   q => {:kind :fact
         :name "axiom/perm-versions"
         :key "ver234"}
   (async/>!! reply-chan {:kind :fact
                          :name "axiom/perm-versions"
                          :key "ver234"
                          :data [{'foo 'some-hash
                                  'bar 'some-other-hash} {}]})
   (async/close! reply-chan))
 (async/<!! result) => false)

"Database output is cached, so that when we ask about a version we already asked about, the answer is given without involving the database."
(fact
 (rule-version-verifier "ver123" #{"some-other-hash" :something-else})
 => true
 (rule-version-verifier "ver123" #{"some-hash-that-does-not-exist" :something-else})
 => false)

[[:chapter {:title "wrap-websocket-handler"}]]
"`wrap-websocket-handler` is based on [chord](https://github.com/jarohen/chord)'s implementation of middleware of the same name.
It does the same, i.e., exposes WebSockets as `core.async` channels, but with the following differences:
1. Instead of providing one bidirectional channel in `:ws-channel`, it provides two unidirectional channels in `:ws-channel-pair`.
2. It extracts the `:message` packaging, and provides raw content on the channels."

"To demonstrate how it works we will create a handler that receives maps, 
and replies to each such event with a similar event, with the `:data` field (a number) incremented."
(fact
 (defn handler [req]
   (if-let [[ws-in ws-out] (:ws-channel-pair req)]
     (async/go-loop []
       (let [input (async/<! ws-in)
             output (update-in input [:data] inc)]
         (async/>! ws-out output)))
     ;; else
     {:status 200
      :body "No WebSockets..."})))

"Now we launch a web server based on this handler with the `wrap-websocket-handler` middleware."
(fact
  (def srv (htsrv/run-server (-> handler
                                 wrap-websocket-handler) {:port 33333})))

"Now let's connect a client to that server and send a map."
(fact
 (def client-resp (async/chan 2))
 (def socket (ws/connect
               "ws://localhost:33333"
               :on-receive #(async/>!! client-resp %)))
 (ws/send-msg socket "{:data 1 :foo :bar}"))


"Now we wait for the response."
(fact
 (let [[resp chan] (async/alts!! [client-resp (async/timeout 1000)])]
   chan => client-resp
   resp  => "{:data 2, :foo :bar}"))

"Finally, let's close both server and client connections."
(fact
 (ws/close socket)
 (srv))
