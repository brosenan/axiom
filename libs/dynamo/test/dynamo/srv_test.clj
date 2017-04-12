(ns dynamo.srv-test
  (:require [midje.sweet :refer :all]
            [dynamo.srv :refer :all]
            [taoensso.faraday :as far]))

[[:chapter {:title "Introduction"}]]
"This is a simple microservice that subscibes to all facts and stores all events to DynamoDB."

[[:chapter {:title "init"}]]
"`init` receives a map containing credentials and other configurations and stores it in an atom.
It then looks up the list of existing tables and stores it in a set."
(fact
 (init ..config..) => nil
 (provided
  (reset! ddb-config ..config..) => irrelevant
  (far/list-tables ..config..) => ["foo" "bar"])
 @curr-tables => #{"foo" "bar"})

"For the purpose of the following tests we will assign `ddb-config` the mock value `:config`."
(reset! ddb-config :config)

[[:chapter {:title "store-fact"}]]
"`store-fact` is a microservice function that subscribes to all fact events."
(fact
 (-> #'store-fact meta :reg) => {:kind :fact})

"`store-fact` responds to events by storing them in DynamoDB."
(fact
 (store-fact {:kind :fact
              :name "foo"
              :key 1234
              :data [1 2 3 4]
              :ts 1000
              :change 1
              :writers #{}
              :readers #{}}) => nil
 (provided
  ))
