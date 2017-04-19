(ns dynamo.srv-test
  (:require [midje.sweet :refer :all]
            [dynamo.srv :refer :all]
            [taoensso.faraday :as far]
            [taoensso.nippy :as nippy]))

[[:chapter {:title "Introduction"}]]
"This is a simple microservice that subscibes to all facts and stores all events to DynamoDB."

(comment [[:chapter {:title "init"}]]
         "`init` receives a map containing credentials and other configurations and stores it in an atom.
It then looks up the list of existing tables and stores it in a set."
         (fact
          (reset! curr-tables #{})
          (init ..config..) => nil
          (provided
           (reset! ddb-config ..config..) => irrelevant
           (far/list-tables ..config..) => [:foo :bar])
          @curr-tables => #{:foo :bar})

         "For the purpose of the following tests we will assign `ddb-config` the mock value `:config`."
         (reset! ddb-config :config))


