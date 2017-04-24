(ns migrator.core-test
  (:require [midje.sweet :refer :all]
            [migrator.core :refer :all]
            [permacode.core :as perm]
            [di.core :as di]
            [clojure.core.async :as async]
            [zk-plan.core :as zkp]
            [zookeeper :as zk]))

[[:chapter {:title "Introduction"}]]
"`migrator` is a microservice that listens to events describing new rules being published,
and initiates data migration using [zk-plan](zk-plan.html)."

[[:chapter {:title "extract-version-rules"}]]
"`extract-version-rules` is a service function which registers to `:axiom/version` events.
As such it depends on the `serve` function we will mock in order to get hold of the function itself and the registration it is making."
(fact
 (let [reg (async/chan)
       $ (di/injector {:serve (fn [f r]
                                (async/>!! reg [f r]))})]
   (module $)
   (let [[f r] (async/<!! reg)]
     r => {:kind :fact
           :name "axiom/version"}
     (def extract-version-rules f))))

"`:axiom/version` events report on addition or removal of `permacode` module versions.
`extract-version-rules` extracts all the rule functions from such a version and publishes
corresponding `:axiom/rule` events."

"It publishes only rule functions, identified by having a `:source-fact` meta field."
(fact
 (extract-version-rules {:key "perm.1234ABC"} ..pub..) => nil
 (provided
  (perm/module-publics 'perm.1234ABC) => {'foo (with-meta (fn []) {:source-fact ["foo" 1]})
                                          'bar (with-meta (fn []) {:source-fact ["bar" 1]})
                                          'baz (fn [])} ; baz will not be published
  (..pub.. {:name "axiom/rule"
            :key 'perm.1234ABC/foo}) => irrelevant
  (..pub.. {:name "axiom/rule"
            :key 'perm.1234ABC/bar}) => irrelevant))

[[:chapter {:title "rule-tracker"}]]
"`rule-tracker` registers to `:axiom/rule` and tracks the quantity of each rule by summing the `:change` [field of the event](cloudlog-events.html#introduction)."

"It depends on the resources [zookeeper-counter-add](#zookeeper-counter-add) and `serve`, which we will mock."
(fact
 (def mock-counters (transient {"/rules/perm.1234ABC.foo" 2}))
 (let [serve-params (async/chan)
       $ (di/injector {:zookeeper-counter-add (fn [path change]
                                                (let [old (mock-counters path 0)
                                                      new (+ old change)]
                                                  (assoc! mock-counters path new)
                                                  new))
                       :serve (fn [func reg]
                                (when (= reg {:kind :fact
                                              :name "axiom/rule"})
                                  (async/>!! serve-params func)))})]
   (module $)
   (let [[func chan] (async/alts!! [serve-params
                                    (async/timeout 1000)])]
     chan => serve-params
     (def rule-tracker func))))

"The `rule-tracker` service function is given an `:axiom/rule` event and a `publish` function."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/foo
                :change 3} (fn publish [ev]
                             (throw (Exception. "This should not be called")))) => nil)

"It calls `zookeeper-counter-add` to increment the counter corresponding to the rule."
(fact
 (mock-counters "/rules/perm.1234ABC.foo") => 5)

"If the rule goes from 0 to a positive count, an `:axiom/rule-exists` event with `:change = 1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/bar
                :change 2} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/rule-exists"
            :key 'perm.1234ABC/bar
            :change 1}) => irrelevant))

"This of-course only happens when the change is positive."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/baz
                :change -2} (fn publish [ev]
                             (throw (Exception. "This should not be called")))) => nil)

"If the aggregated value of the rule goes down to 0, an `:axiom/rule-exists` event with `:change = -1` is published."
(fact
 (rule-tracker {:kind :fact
                :name "axiom/rule"
                :key 'perm.1234ABC/foo
                :change -5} ..pub..) => nil
 (provided
  (..pub.. {:kind :fact
            :name "axiom/rule-exists"
            :key 'perm.1234ABC/foo
            :change -1}) => irrelevant))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "zookeeper-counter-add"}]]
"`zookeeper-counter-add` depends on the `zookeeper` resource as dependency, and uses it to implement a global atomic counter."
(let [$ (di/injector {:zookeeper :zk})]
  (module $)
  (def zookeeper-counter-add (di/wait-for $ zookeeper-counter-add)))

"`zookeeper-counter-add` takes a path to a counter and a number to be added.
If a node corresponding to the given path does not exist, it is assumed to be equal 0, and is therefore created using the given number.
The new value, which is the given number, is returned."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 3
 (provided
  (zk/exists :zk "/rules/foobar") => nil
  (zk/create :zk "/rules/foobar" :persistent? true) => "/rules/foobar"
  (zkp/set-initial-clj-data :zk "/rules/foobar" 3) => irrelevant))

"If a node exists, its value is updated to add the given number."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 5
 (provided
  (zk/exists :zk "/rules/foobar") => {:some :values
                                      :version 7}
  (zkp/get-clj-data :zk "/rules/foobar") => 2
  (zkp/to-bytes "5") => ..bin..
  (zk/set-data :zk "/rules/foobar" ..bin.. 7) => irrelevant))

"`zookeeper-counter-add` takes an extra `retries` parameter which defaults to 3.
If `zk/set-data` throws an exception for any reason, the update is retried.
This is to account for the possibility of concurrent update."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => 6
 (provided
  (zk/exists :zk "/rules/foobar") =streams=> [{:some :values
                                               :version 7}
                                              {:some :values
                                               :version 8}]
  (zkp/get-clj-data :zk "/rules/foobar") =streams=> [2 3]
  (zkp/to-bytes "5") => ..bin1..
  (zk/set-data :zk "/rules/foobar" ..bin1.. 7) =throws=> (Exception. "boo")
  (zkp/to-bytes "6") => ..bin2..
  (zk/set-data :zk "/rules/foobar" ..bin2.. 8) => irrelevant))

"When the retries are exhasted, the function throws."
(fact
 (zookeeper-counter-add "/rules/foobar" 3) => (throws "boo")
 (provided
  (zk/exists :zk "/rules/foobar") =streams=> [{:some :values
                                               :version 7}
                                              {:some :values
                                               :version 8}
                                              {:some :values
                                               :version 9}]
  (zkp/get-clj-data :zk "/rules/foobar") =streams=> [2 3 4]
  (zkp/to-bytes irrelevant) => irrelevant
  (zk/set-data :zk "/rules/foobar" irrelevant irrelevant) =throws=> (Exception. "boo")))
