(ns examples.tweetlog-test
  (:require [midje.sweet :refer :all]
            [examples.tweetlog :refer :all]
            [perm.Qmf6or1t8bjdsKqSpnawvTR9Vo8M4Lp2eNBgDv5AQNK3yR :as c]))

(def rules (for [[k v] (ns-publics 'examples.tweetlog)] @v))
(defn test-rules [facts name arity]
  ((apply c/simulate-rules-with rules app facts) [name arity]))
(defn query [facts q arity readers]
  (apply c/run-query rules q arity app readers facts))

(def story
  [(c/f [:tweetlog/tweeted "maui" "I'm a #demigod!"]
        :writers #{[:user= "maui"]})
   (c/f [:tweetlog/follows "moana" "maui"]
        :writers #{[:user= "moana"]})
   (c/f [:tweetlog/tweeted "tamatoa" "I'm so shiny!"]
        :writers #{[:user= "tamatoa"]})
   (c/f [:tweetlog/follows "moana" "tamatoa"]
        :writers #{[:user= "tamatoa"]})
   (c/f [:tweetlog/tweeted "maui" "@tamatoa is so shiny!"]
        :writers #{[:user= "tamatoa"]})
   (c/f [:examples.tweetlog/followee-tweets "moana" "tamatoa" "I'm so shiny!"]
        :writers #{[:user= "tamatoa"]})
   (c/f [:tweetlog/tweeted "moana" "Hey @maui, time to save the world!"]
        :writers #{[:user= "moana"]})
   (c/f [:tweetlog/tweeted "maui" "I just need to get my hook from @tamatoa"]
        :writers #{[:user= "maui"]}
        :readers #{[:user= "moana"]})])

(fact
 (query story [:tweetlog/timeline "moana"] 2 #{[:user= "moana"]})
 => #{["maui" "I'm a #demigod!"]
      ["maui" "I just need to get my hook from @tamatoa"]}
 (query story [:tweetlog/timeline "maui"] 2 #{[:user= "maui"]})
 => #{["moana" "You were mentioned: Hey @maui, time to save the world!"]}
 (query story [:tweetlog/timeline "tamatoa"] 2 #{[:user= "tamatoa"]})
 => #{})
