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
  (zkp/get-clj-data :zk "/rules/foobar") => 2))
