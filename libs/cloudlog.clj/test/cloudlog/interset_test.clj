(ns cloudlog.interset_test
  (:use midje.sweet)
  (:require [cloudlog.interset :as interset]))


[[:chapter {:title "Introduction"}]]
"Intersets (intersection sets) are a representation of mathematical sets.
Each interset is represented as a Clojure set of *components*, each representing a *named set*, which is an
opaque set.  The interset is then an intersection of all these sets.

For example, when considering the Clojure set"
(comment #{:a :b :c})
"as an interset, it actually represents the intersection of sets `:a`, `:b` and `:c`, where `:a`, `:b` and `:c`
are names of sets.

This method is useful for representing *user sets*, an important notion in *secure cloudlog*.
The named sets (or named user groups) are opaque.  We do not want to actually list all users in each group.
Instead, we just name the group.  A user who is a member of groups `:a`, `:b` and `:c` can be represented
as the interset `#{:a :b :c}`.  All users in group `:a` are represented by interset `#{:a}`."

[[:chapter {:title "universe"}]]
"The universal set is the intersection of no sets.  It is therefore:"
(fact
 interset/universe => #{})

[[:chapter {:title "intersection"}]]
"Intersecting two intersets is a union of their underlying sets:"
(fact
 (interset/intersection #{:a :b} #{:a :c}) => #{:a :b :c})
"This is true because element `x` is a member of the intersection of intersets `A` and `B`
if and only if it is a member of the both `A` and `B`.  To be a member of `A` it needs
to be a member of all the named sets composing `A`, and to be a member of `B`
it needs to be a member of all of `B`'s components.  Therefore, `x` needs to be a member of
all the named sets composing both `A` and `B` -- their union (as Clojure sets)."

"We allow any number of sets to be intersected"
(fact
 (interset/intersection #{:a :b} #{:b :c} #{:c :d}) => #{:a :b :c :d})

[[:chapter {:title "subset?"}]]
"The `subset?` predicate checks if one interset is a subset of the other.  For example:"
(fact
 (interset/subset? #{:a} interset/universe) => true
 (interset/subset? interset/universe #{:a}) => false
 (interset/subset? (interset/intersection #{:a} #{:b}) #{:a}) => true
 (interset/subset? (interset/intersection #{:a} #{:b}) #{:c}) => false)
"In particular, a set is considered a subset of itself."
(fact
 (interset/subset? #{:a} #{:a}) => true)
"Interset `A` is a subset of interset `B` if and only if every member `x` of `A` is also a member of `B`.
If `x` is a member of `A` it is a member of all `A`'s components.
To be a member of `B` it needs to also be a member of all `B`'s components.
This means that every component of `B` must also be a component of `A`, or in other words,
Clojure set `B` must be a subset of Clojure set `A`."
[[:reference {:refer "cloudlog.interset/subset?"}]]

[[:chapter {:title "super-union"}]]
"The main disadvantage of intersets is the fact that intersets are not closed for the union operation.
This means that given two intersets `A` and `B`, their union is not necessarily an interset by itself.

The thing that can be provided is a `super-union` -- an interset that is guaranteed to contain both (or all)
intersets joined by this operator, and by that be a superset of their union."
(let [AB #{:a :b}
      BC #{:b :c}
      union (interset/super-union AB BC)]
  (fact
   union =not=> empty? ; The super-union is still not the universal set
   (interset/subset? AB union) => true
   (interset/subset? BC union) => true))


[[:chapter {:title "empty?"}]]
"It is sometimes useful to test if an interset is empty.  For example, a Cloudlog fact designated for an empty audience
can be dropped.
By default we consider intersets to be non-empty, i.e., we consider named sets to be intersecting, unless otherwise specified."
(fact
 (interset/empty? #{:a :b}) => false)

"An interset is empty if it is intersected with the `:empty` set."
(fact
 (interset/empty? #{:a :empty :b}) => true)

"Another case in which an interset is considered empty is when it contains two disjoined named sets.
A named set is considered a *partition* if it has the form `[:some-key= \"some-value\"]`."
(fact
 (interset/partition? [:something= 123]) => true
 (interset/partition? :something) => false
 (interset/partition? [:something 123]) => false)
"An interset is considered empty if it is an intersection of two partitions of the same key, with different values."
(fact
 (interset/empty? #{:a [:foo= "foo"] [:foo= "bar"]}) => true
 (interset/empty? #{:a [:foo= "foo"]}) => false)

