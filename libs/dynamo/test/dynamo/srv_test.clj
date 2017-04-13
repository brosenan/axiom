(ns dynamo.srv-test
  (:require [midje.sweet :refer :all]
            [dynamo.srv :refer :all]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]))

[[:chapter {:title "Introduction"}]]
"This is a simple microservice that subscibes to all facts and stores all events to DynamoDB."

[[:chapter {:title "init"}]]
"`init` receives a map containing credentials and other configurations and stores it in an atom.
It then looks up the list of existing tables and stores it in a set."
(fact
 (reset! curr-tables #{})
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
 (def my-event {:kind :fact
              :name "foo"
              :key 1234
              :data [1 2 3 4]
              :ts 1000
              :change 1
              :writers #{}
              :readers #{}})
 (store-fact my-event) => nil
 (provided
  (nippy/freeze {:data [1 2 3 4]
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (far/put-item :config :foo {:key "1234"
                               :ts 1000
                               :event ..bin..}) => irrelevant))

"If the table does not exist, it is created."
(fact
 (def my-event2 (assoc my-event :name "baz"))
 (store-fact my-event2) => nil
 (provided
  (nippy/freeze {:data [1 2 3 4]
                 :change 1
                 :writers #{}
                 :readers #{}}) => ..bin..
  (far/ensure-table :config :baz [:key :s] ; :key is the partition key
                    :range-keydef [:ts :n] ; and :ts is the range key
                    :throughput default-throughput) => irrelevant
  (far/put-item :config :baz {:key "1234"
                               :ts 1000
                               :event ..bin..}) => irrelevant))

"The new table is then added to the set."
(fact
 @curr-tables => #{"foo" "bar" "baz"})

"Events with `:name` values that end with \"?\" or \"!\" should not be persisted, and are therefore ignored."
(fact
 (store-fact (assoc my-event :name "foo?")) => nil
 (provided
  ; No side effect
  )
 (store-fact (assoc my-event :name "bar!")) => nil
 (provided
  ; No side effect
  ))
