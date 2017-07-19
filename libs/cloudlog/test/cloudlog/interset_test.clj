(ns cloudlog.interset_test
  (:use midje.sweet)
  (:require [cloudlog.interset :as interset]))


[[:chapter {:title "Introduction"}]]
"In Axiom, *mathematical sets* are at the base of how we provide integrity and confidentiality.
Each fact is assigned a *writers set* and a *readers set*, representing the set of users who may have stated this fact,
and the set of users allowed to know about it."

"To be able to efficiently reason upon theses sets, we need to find a Clojure representation that is both expressive enough,
and efficient enough to represent and reason upon."

"Intersets (intersection sets) are the representation we chose for Axiom.
Each interset is represented as either a Clojure set (a *simple interset*), or a vector of such set (a *canonical interset*).
The Clojure sets represent *intersection*, and the vectors represent *union*,
so together we have a set expression that represents *a union of intersections* of basic components.
The basic components of these sets are either *strings* or *vectors*, each representing an opaque set,
i.e., a set one cannot further reason upon using interset operations.
Strings represent identities (e.g., user-IDs), and vectors represent logic terms that are true for a set of users,
such as *a friend of some user X*."

"The following example interset may represent the friends of user `alice` in some application:"
(comment #{[:some-app/friend "alice"]})

"The following interset represents the exclusive group of users who are friends with both `alice` and `bob`:"
(comment #{[:some-app/friend "alice"]
           [:some-app/friend "bob"]})

"The following interset represents `alice`, but only if she's friends with `bob` (otherwise the sent is considered empty):"
(comment #{"alice" [:some-app/friend "bob"]})

"The following interset includes both `alice` and all of `alice`'s friends:"
(comment [#{"alice"} #{[:some-app/friend "alice"]}])

"The following inteset is empty, because `alice` and `bob` are two seperate identities:"
(comment #{"alice" "bob"})

[[:chapter {:title "universe and empty-set" :tag "universe"}]]
"The universal set is the intersection of no sets.  It is therefore:"
(fact
 interset/universe => #{})

"Similarly, the empty set is a union of no sets:"
(fact
 interset/empty-set => [])

[[:chapter {:title "intersection"}]]
"Intersecting two simple intersets is a union of their underlying sets:"
(fact
 (interset/intersection #{[:some-app/friend "alice"]
                          [:some-app/friend "bob"]}
                        #{[:some-app/friend "alice"]
                          [:some-app/friend "charlie"]})
 => #{[:some-app/friend "alice"]
      [:some-app/friend "bob"]
      [:some-app/friend "charlie"]})

"This is true because element `x` is a member of the intersection of intersets `A` and `B`
if and only if it is a member of the both `A` and `B`.  To be a member of `A` it needs
to be a member of all the named sets composing `A`, and to be a member of `B`
it needs to be a member of all of `B`'s components.  Therefore, `x` needs to be a member of
all the named sets composing both `A` and `B` -- their union (as Clojure sets)."

"For canonical intersets we return a union of the intersections of all simple intersets on one side, with all intersets on the other side,
as long as they are not disjoint."

"Consider for example two intersets containing, for `alice` and `bob` respectively, the user *and* his or her friends.
Intersecting these two sets will result in a 3-component canonical set:
1. Friends of both `alice` and `bob`.
2. `alice`, as long as she's friends with `bob`, and
3. `bob`, as long as he's friends with `alice`.
The fourth combination -- the intersection of `alice` and `bob` is omitted, because the set of *all `alice`s who are also `bob`s* is empty."
(fact
 (interset/intersection [#{[:some-app/friend "alice"]} #{"alice"}]
                        [#{[:some-app/friend "bob"]} #{"bob"}])
 => [#{[:some-app/friend "alice"]
       [:some-app/friend "bob"]}
     #{"bob" [:some-app/friend "alice"]}
     #{"alice" [:some-app/friend "bob"]}])

[[:chapter {:title "union"}]]
"A union of two inrsets can be acheived by concatenating their canonical forms.
For example, a set containing either `alice` or `bob` is derived the following way:"
(fact
 (interset/union #{"alice"} #{"bob"}) => [#{"alice"} #{"bob"}])

"However, a union can be more minimal than that.
If any of the components of the second arguments are subsets of the first argument, they are omitted."
(fact
 (interset/union [#{"alice"} #{"bob"}] #{"bob" [:some-app/friend "alice"]}) => [#{"alice"} #{"bob"}])

[[:chapter {:title "subset?"}]]
"The `subset?` predicate checks if one interset is a subset of the other.  For example:"
(fact
 (interset/subset? #{:a} interset/universe) => true
 (interset/subset? interset/universe #{:a}) => false
 (interset/subset? #{:a} interset/empty-set) => false
 (interset/subset? interset/empty-set #{:a}) => true
 (interset/subset? (interset/intersection #{:a} #{:b}) #{:a}) => true
 (interset/subset? (interset/intersection #{:a} #{:b}) #{:c}) => false)

"In particular, a set is considered a subset of itself."
(fact
 (interset/subset? #{:a} #{:a}) => true)

"For simple intersets, interset `A` is subset of interset `B` if and only if
Clojure set `B` is a subset of Clojure set `A`."
(fact
 (interset/subset? #{"alice" [:some-app/friend "bob"]}
                   #{[:some-app/friend "bob"]}) => true
 ;; but...
  (interset/subset? #{[:some-app/friend "bob"]}
                    #{"alice" [:some-app/friend "bob"]}) => false)

"This is true because the set containing `alice` only if `alice` is friends with `bob` is a subset of `bob`'s friends."

"If the right-hand argument is a canonical interset, `subset?` returns `true` if it is true for *at least one* of the right-hand argument's components."
(fact
 (interset/subset? #{"alice" [:some-app/friend "bob"]}
                   [#{[:some-app/friend "bob"]} #{[:some-app/friend "charlie"]}]) => true)

"If the left-hand argument is canonical, `subset?` returns `true` only if *all* components are subsets."
(fact
 (interset/subset? [#{"alice" [:some-app/friend "bob"]} #{[:some-app/friend "charlie"]}]
                   [#{[:some-app/friend "bob"]} #{[:some-app/friend "charlie"]}]) => true
 ;; but...
  (interset/subset? [#{"alice" [:some-app/friend "bob"]} #{[:some-app/friend "charlie"]}]
                    #{[:some-app/friend "bob"]}) => false)


[[:chapter {:title "enum-groups"}]]
"Sometimes we wish to iterate over all groups mentioned in an interset.
`enum-groups` allows us to do so by returning a sequence of these groups."

"For an empty or universal intersets, an empty sequence is returned."
(fact
 (interset/enum-groups interset/universe) => []
 (interset/enum-groups interset/empty-set) => [])

"For a simple interset, all compoments are returned."
(fact
 (interset/enum-groups #{[:foo "bar"] [:bar "foo"]}) => [[:foo "bar"] [:bar "foo"]])

"For a cannonical interset, all components of all elements are returned."
(fact
 (interset/enum-groups [#{[:foo "bar"]} #{[:bar "foo"]}]) => [[:foo "bar"] [:bar "foo"]])

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "canonical"}]]
"`canonical` takes an interset of any kind, and returns a canonical interset."

"If the given interset is already canonical, it returns the interset as-is."
(fact
 (interset/canonical [#{"foo"} #{"bar"}]) => [#{"foo"} #{"bar"}])

"If the given interset is a simple one, it wraps it in a vector."
(fact
 (interset/canonical #{"foo"}) => [#{"foo"}])

[[:section {:title "uncanonical"}]]
"`uncanonical` inverts the effect of `canonical`, if it is possible."

"When given a canonical interset of size 1, it returns the underlying set."
(fact
 (interset/uncanonical [#{"foo"}]) => #{"foo"})

"When given a canonical interset with more elements, it returns the canonical interset."
(fact
 (interset/uncanonical [#{"foo"} #{"bar"}]) => [#{"foo"} #{"bar"}])

"`uncanonical` is idempotent in the sense that calling it on a simple interset will result in the same interset."
(fact
 (interset/uncanonical #{"foo"}) => #{"foo"})

[[:section {:title "disjoint?"}]]
"Two simple intersets are (definitely) disjoint when each of them references a concrete user identity (a string), and these identities are different."

"The `disjoint?` function takes two *simple* intersets and returns whether they are disjoint."

"It will return `false` if at least one of the two sets does not have an identity."
(fact
 (interset/disjoint? #{"foo"} #{[:some-app/friend "bar"]}) => false)

"In this case, `disjoint?` does not know for sure whether or not `foo` is a friend of `bar`, so it returns the safe answer `false`."

"In case each inteset has a different identity we know for sure these sets are disjoint."
(fact
 (interset/disjoint? #{"foo"} #{[:some-app/friend "bar"] "baz"}) => true)

"However, if both sets reference the same identity, they are not disjoint."
(fact
 (interset/disjoint? #{"foo"} #{[:some-app/friend "bar"] "foo"}) => false)
