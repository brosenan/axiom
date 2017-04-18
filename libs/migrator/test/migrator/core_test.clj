(ns migrator.core-test
  (:require [midje.sweet :refer :all]
            [migrator.core :refer :all]
            [permacode.core :as perm]))

[[:chapter {:title "Introduction"}]]
"`migrator` is a microservice that listens for events describing new rules being published,
and initiates data migration using [zk-plan](zk-plan.html)."

[[:chapter {:title "extract-version-rules"}]]
"`extract-version-rules` handles `:axiom/version` events."
(fact
 (-> #'extract-version-rules meta :reg) => {:kind :fact
                                            :name "axiom/version"})

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
