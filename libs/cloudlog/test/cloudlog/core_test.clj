(ns cloudlog.core_test
  (:use midje.sweet)
  (:use [cloudlog.core]))

[[:chapter {:title "defrule: Rule Definition Macro"}]]
"Definition:"
[[:reference {:refer "cloudlog.core/defrule"}]]

[[:section {:title "Simple Rules"}]]
"`defrule` defines a Cloudlog rule.  Such a rule always starts with a **fact pattern**: 
a vector for which the first element is a keyword representing the fact name, and the rest of the elements
are **bindings**, as we explain later.

The following rule -- `foo-yx`, matches facts of the form `[:test/foo x y]` (facts named `:test/foo` with two arguments
we call `x` and `y`), and for each such fact it creates a new fact of the form `[foo-yz y x]`."

(defrule foo-yx [y x]
  [:test/foo x y] (by-anyone))

"The `(by-anyone)` guard means that we do not care on behalf of whom this fact was published.
Typically, we will care about this, but we delay discussion on this to our discussion of [data integrity](#integrity)."

"What `defrule` actually does is define a Clojure function that, when given the arguments for the source fact (in our case, `:test/foo`),
it returns a sequence of bindings for the target fact (`foo-yx`)."
(fact
      (foo-yx [1 2]) => [[2 1]])

"The function contains metadata regarding the identity of the source fact."
(fact
      (-> foo-yx meta :source-fact) => [:test/foo 2])

"The name and arity of the target fact is also metadata of the function."
(fact
 (-> foo-yx meta :target-fact) => [::foo-yx 2])

"The arguments of both the rule and the source fact are not limited to being variables.
They can also be **values**.

When a fact is introduced to a rule, it is [unified](https://en.wikipedia.org/wiki/Unification_%28computer_science%29) 
with source-fact part of the rule's body.  Variables in the source-fact are bound to the corresponding values in
the input fact, and values are compared.  If the values differ, the rule is not applied to this fact.

For example, the following rule is similar to `foo-yx`, only that it assumes that `x == 1`."
(defrule foo-unify [x 1]
  [:test/foo 1 x] (by-anyone))

"For `[1 2]`, we will get the same result as before:"
(fact
      (foo-unify [1 2]) => [[2 1]])
"But if we introduce a fact in which `x != 1`, the rule will not be applied, and the result sequence will be empty."
(fact
      (foo-unify [2 3]) => empty?)

[[:section {:title "Guards"}]]
"While simple rules are useful in some cases, they are limited to reordering or restructuring the fields in the source fact, 
but cannot do more.  **Guards** fix this by allowing (purely-functional) Clojure functions to be used inside rules.

Guards are Clojure forms such as `let`, `for` or `when`.  The example below uses `let` to create a new binding
(variable `z`), calculated as the sum of `x` and `y`:"
(defrule foo-let [z]
     [:test/foo x y] (by-anyone)
     (let [z (+ x y)]))

"The result is as you would expect."
(fact
      (foo-let [1 2]) => [[3]])

"Below is a more elaborate example.  Here we index text documents by:
1. Extracting all the words from a document, and iterating over them using a `for` guard
2. Converting each word to lower-case (so that indexing becomes case-insensitive) using a `let` guard, and
3. Filterring out \"stopwords\", using a `when-not` guard"

(def stop-words #{"a" "is" "to" "the"})
(defrule index-docs [word id]
     [:test/doc id text] (by-anyone)
     (for [word (clojure.string/split text #"[,!.? ]+")])
     (let [word (clojure.string/lower-case word)])
     (when-not (contains? stop-words word)))

"Now, if we index a document, we will get index entries with the words it contains, lower-case, excluding stopwords."
(fact
 (index-docs [1234 "Hello, to the  worlD!"]) => [["hello" 1234] ["world" 1234]])

"Cloudlog guards differ from the corresponding Clojure forms in that they do not have a body.
In the above code, the `for` form ends after the bindings have been established, and the same goes
for the `let` and `when-not` forms.  A corresponding Clojure implementation could look like this:"
(comment
  (for [word (clojure.string/split text #"[,!.? ]+")]
    (let [word (clojure.string/lower-case word)]
      (when-not (contains? stop-words word)
        (emit some result)))))
"with each form *containing* the following forms.  However, Cloudlog is a logic programming language,
like Prolog or core.logic.  Cloudlog guards are just like predicates.  Bindings in `let` and `for` forms
assert a certain relationship between the bound variable and the expression to its right.
A `when` or `when-not` guards are just like predicates that pass or fail depending on the 
(Clojure-level) predicate given to them."

[[:section {:title "Joins" :tag "joins"}]]
"Even with guards, rules are still limited to considering only a single fact.
Sometimes we need to draw a conclusion based on a combination of facts.
A classical example is applications such as [Twitter](https://twitter.com), in which users can:
1. Follow other users,
2. Post tweets,
3. View their **timelines**, consisting of all the tweets made by users they follow.

To successfully generate a timeline, a rule needs to take into consideration both who follows whom, and
tweets -- two different kinds of facts.  Moreover, there is a data dependency between the two.  We are only
interested in tweets made by users we follow.

Cloudlog rules can depend on more than just the source-fact."
(defrule timeline [user tweet]
     [:test/follows user author] (by-anyone)
     [:test/tweeted author tweet] (by-anyone))

"In such cases, the rule function cannot produce the result right away.
The above rule's source fact is `:test/follows`:"
(fact
 (-> timeline meta :source-fact) => [:test/follows 2])
"However, from a `:test/follows` fact alone we cannot create a timeline entry.
To create such an entry, we need to match it with a `:test/tweeted` fact.

To allow this, functions that represent rules that depend on more than one fact have **continuations**.

Continuations are functions, provided as metadata on the rule function."
(fact
 (-> timeline meta :continuation) => fn?)

"The continuation function itself has metadata, indicating what its source-fact is."
(fact
 (-> timeline meta :continuation meta :source-fact) => [:test/tweeted 2])

"As in the case of simple rules, in case of a join, the rule function also returns a sequence of tuples,
only that this time these tuples are not results, but rather continuations.
Each tuple contains the information that the continuation function needs in order to resume the rule.

For example, the `timeline` rule above will emit the information it learned from the `:test/follows` fact
it encountered."
(fact
 (timeline ["alice" "bob"]) ; Alice follows Bob
   => [["bob" "alice" "bob"]])

"Notice that `\"bob\"` appears twice in the tuple.  Its second appearance is as the value for variable `author`.
Its first appearance is as the **key** for the `:test/tweeted` fact.  We'll discuss keys in more detail below.

This tuple can be used to construct a new rule function based on the continuation."
(fact
 (let [cont (-> timeline meta :continuation) ; The continuation function we got as meta
       rule-func (cont ["bob" "alice" "bob"])] ; The new rule function
   (rule-func ["bob" "Hi, Alice!"]) ; :test/tweeted fact
   ) => [["alice" "Hi, Alice!"]])

"Cloudlog tries to be true to its logic-programming nature, but since it is intended to work with
large amounts of data, some restrictions need to be applied.  In our case, the main restriction is
that in any fact, the first argument is considered the **key**, and there are some restrictions and recommendations
regarding keys.  Generally, choosing the correct key has a significant impact on the performance of the application.
A key must be specific enough so that all facts with the same key can be stored in memory at the same time.

When writing rules with joins, we need to make sure the key parameter for the joined fact is bound.
For example, in the `timeline` rule, we chose the order of facts for a reason.
`:test/tweeted` is keyed by `author`, and `:test/follows` is keyed by `user`.  If we get the `:test/follows`
first, we learn about the `author` who's tweets we need to consider.  However, when we consider `:test/tweeted`
first, this does not give us a clue regarding the `:test/follows` facts we need to consider for this tweet,
since it does not provide value for `user`.

We provide a compile-time error in such cases."
(fact
 (macroexpand `(defrule timeline [user tweet]
                 [:test/tweeted author tweet] (by-anyone)
                 [:test/follows user author] (by-anyone)))
   => (throws "variables #{cloudlog.core_test/user} are unbound in the key for :test/follows"))

"Of-course, guards are supported with joins."
(defrule foobar-range [w]
     [:test/foo x y] (by-anyone)
     (let [z (+ x y)])
     (for [w (range z)])
     [:test/bar z w] (by-anyone))

"In the above rule we take `x` and `y` from fact `:test/foo`, sum them to get `z`, and span the range `0..z`.
We return `w` values for which there is a fact `[:test/bar z w]`.

The rule function for `foobar-range` will return a tuple based on the guards (and not based on `:test/bar`,
which is to be considered afterwards).  The first element in each tuple is a key to be used against `:test/bar` (`z`),
followed by the values of `w` and `z`:"
(fact
 (foobar-range [1 2]) => [[3 0 3] [3 1 3] [3 2 3]])

"If a matching `:test/bar` fact exists, a result will be produced."
(fact
 (let [cont (-> foobar-range meta :continuation)
       rule-func (cont [3 1 3])]
   (rule-func [3 1])) => [[1]])
"However, if it does not, an empty result will be produced."
(fact
 (let [cont (-> foobar-range meta :continuation)
       rule-func (cont [3 1 3])]
   (rule-func [4 1])) => [])

[[:section {:title "Derived Facts" :tag "derived-facts"}]]
"Each rule defines a derived fact, i.e., each tuple produced by a rule is stored as a fact.
The name of this fact is the fully-quaified name of the rule function.
This fact can then be used in other rules.

For example, if we wish to create a \"trending\" timeline, aggregating the timelines 
of users identified as \"influencers\", we would probably write a rule of the following form:"
(defrule trending [tweet]
  [:test/influencer influencer] (by-anyone)
  [timeline influencer tweet] (by-anyone))

"Now we can simulate our rule (using [simulate-with](#simulate-with)):"
(fact
 (simulate-with trending :test
                [:test/influencer "gina"]
                [:test/influencer "tina"]
                [::timeline "tina" "purple is the new black!"]
                [::timeline "gina" "pink is the new purple!"]
                [::timeline "some-lamo" "orange is the new bananna"])
 => #{["purple is the new black!"]
      ["pink is the new purple!"]})

[[:section {:title "Integrity" :tag "integrity"}]]
"> Integrity of information refers to protecting information from being modified by unauthorized parties.
([Confidentiality, Integrity, Availability: The Three Components of the CIA Triad](http://security.blogoverflow.com/2012/08/confidentiality-integrity-availability-the-three-components-of-the-cia-triad/))

Cloudlog takes a liberal approach to integrity.  Any user can publish any fact, as long as he or she is a member of the fact's *writers set*.
We explain writer sets in [our discussion of events](cloudlog-events.core.html#writers), but for now we can just say the writer set is a piece of
information telling us to whom a certain fact is attributed to.
Any user within the fact's writer set can, for example, delete it and replace it with another.

When a rule is applied to a fact, it takes ownership over the result (see [events](cloudlog-events.core.html#writers)).
This makes the rule responsible for the integrity of the result."

"To allow rules to specify integrity requirements we introduce the `by` guard.
Look at the `timeline` rule defined [above](#joins).  The `:test/tweeted` fact indicates the user who tweeted.
However, there is no guarantee that the user who appears in the fact as the author of the tweet is indeed the user who created that fact.
For example, consider the fact:"
(comment
  [:test/tweeted "alice" "I hate Bob!"])
"What guarantee do we have that it is Alice who created this fact, and not Eve, who's been trying to 
break up Alice and Bob for years?

The answer is we don't actually know that, because the Cloudlog is liberal about integrity, and allows any user 
(including Eve) to create any fact (including tweets by Alice).

So what do we do?  Do we allow Eve to succeed in her evil plan?
No, we do not.  This is where the `by` guard comes to save the day (and Alice's love life).
Below is a secure version of the `timeline` rule, that only takes into account tweets made by the advertized user."
(defrule secure-timeline [user tweet]
  [:test/follows user author] (by-anyone)
  [:test/tweeted author tweet] (by [:user= author]))

"Now, if both Eve and Alice create tweets alegedly made by Alice, 
Bob (who follows Alice) will only see the ones genuinly made by Alice."
(fact
 (simulate-with secure-timeline :test
                (f [:test/follows "bob" "alice"] :writers #{[:user= "bob"]})
                (f [:test/tweeted "alice" "Alice loves Bob"] :writers #{[:user= "alice"]})
                (f [:test/tweeted "alice" "Alice hates Bob"] :writers #{[:user= "eve"]}))
 => #{["bob" "Alice loves Bob"]})

"Now we come back to `by-anyone` and why we had to use it all over the place.
If we do not use a `by` guard on one of the source facts in a rule we put our application at risk
of having unauthorized updates take effect. For example, in the `secure-timeline` rule above
we checked the integrity of tweets, but \"forgot\" to check the integrity of following relationships.
This mistake can help Eve get messages through to Bob although he does not follow her:"
(fact
 (simulate-with secure-timeline :test
                (f [:test/follows "bob" "eve"] :writers #{[:user= "eve"]})
                (f [:test/tweeted "eve" "Alice hates Bob"] :writers #{[:user= "eve"]}))
 => #{["bob" "Alice hates Bob"]})
"Both facts were submitted (legally) by Eve.  The only flaw was in our logic -- we did not protect against this.
The `by-anyone` guard is here to set a warning that we are doing something wrong.
Typically we never want to use it, unless we have a really good reason to."

"But what if we don't use *any guard whatsoever*?
Cloudlog will not allow us to do this.  For example, if we forget to place a `by*` guard on `foo-yx`, we get the following error:"
(fact
 (macroexpand '(defrule foo-yx [y x]
                 [:test/foo x y]))
 => (throws "Rule is insecure. :test/foo is not checked."))

"The same goes for rules with joins:"
(fact
 (macroexpand '(defrule secure-timeline [user tweet]
                 [:test/follows user author] (by-anyone)
                 [:test/tweeted author tweet]))
 => (throws "Rule is insecure. :test/tweeted is not checked."))

[[:chapter {:title "defclause: Top-Down Logic" :tag "defclause"}]]
"Regular rules defined using `defrule` define *bottom-up logic*.  Bottom-up logic is applied when facts are added or removed,
creating or removing derived facts as needed.
Unfortunately, this is often not enough.  One limitation of bottom-up reasoning (logic)
is that it cannot take into account a goal -- a clue for what we are trying to find.
It tries to build all possible result tuples, but without guidance this can be hard at times.
Clauses fix this by performing logic at query time, starting with a provided goal.

Consider the `index-docs` example above.  When a new `:test/doc` fact is created, the `index-docs` rule creates
an index entry for each keyword in the text.  Now imagine we wish to use this index for retrieval.
What we want is to allow users to provide a sequence of search keywords, and get the full text of all the document
that match *all* these keywords.

Doing this with bottom-up reasoning is quite hard.  We need to create index elements for every combination of keywords.
Clauses allow us to start with a particular combination, retrieve documents based on one element (say, the first keyword)
retrieve full texts and only accept those in which the other keywords appear.

The following clause does just that."
(defclause multi-keyword-search
  [:test/multi-keyword-search keywords -> text]
  (let [keywords (map clojure.string/lower-case keywords)
        first-kw (first keywords)])
  [index-docs first-kw id] (by :test)
  [:test/doc id text] (by-anyone)
  (let [lc-text (clojure.string/lower-case text)])
  (when (every? #(clojure.string/includes? lc-text %)
                (rest keywords))))

"`multi-keyword-search` is the name of the function to be created (similar to a rule function).
`:test/multi-keyword-search` is the *predicate* to be associated with this clause.
Unlike the function name which needs to be unique, the predicate can be shared across clauses.
Users will eventually query predicates, not clauses.
The vector `[keywords]` contains the input arguments to the predicate, and `[text]` is the
vector of output arguments.  In either case you can have more than one argument.
Following these headers are the body element of the clause."

"The source-fact for a clause is a question of the form `:predicate-keyword?`.  For example:"
(fact
 (-> multi-keyword-search meta :source-fact) => [:test/multi-keyword-search? 2])

"Here, `:test/multi-keyword-search?` is a *question*, a fact containing the input arguments,
preceded by a `$unique$` parameter, which identifies a specific question (hence the arity `2`)."

"The *answer* is a derived fact of the form `:predicate-keyword!`:"
(fact
 (loop [rulefunc multi-keyword-search]
   (or (-> rulefunc meta :target-fact)
       (recur (-> rulefunc meta :continuation))))
 => [:test/multi-keyword-search! 2])

"In this case, the answer's arity is also `2` -- the unique identifier that matches the question
and the output paramter."
(fact (simulate-with multi-keyword-search :test
                     [:test/multi-keyword-search? 1234 ["hello" "world"]]
                     [:test/multi-keyword-search? 2345 ["foo" "bar"]]
                     (f [::index-docs "foo" "doc1"] :writers #{:test})
                     (f [::index-docs "foo" "doc2"] :writers #{:test})
                     (f [::index-docs "hello" "doc5"] :writers #{:test})
                     [:test/doc "doc1" "Foo goes into a Bar..."]
                     [:test/doc "doc2" "Foo goes into a Pub..."]
                     [:test/doc "doc5" "World peace starts with a small Hello!"])
      => #{[1234 "World peace starts with a small Hello!"]
           [2345 "Foo goes into a Bar..."]})

"Definition:"
[[:reference {:refer "cloudlog.core/defclause"}]]

[[:file {:src "libs/cloudlog/test/cloudlog/core_test_sim.clj"}]]
[[:file {:src "libs/cloudlog/test/cloudlog/core_test_facttable.clj"}]]

