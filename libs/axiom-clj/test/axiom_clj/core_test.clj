(ns axiom-clj.core-test
  (:require [midje.sweet :refer :all]
            [axiom-clj.core :refer :all]
            [di.core :as di]
            [clojure.core.async :as async]
            [zookeeper :as zk]))

[[:chapter {:title "Introduction"}]]
"This library is the integration point between all the components that make up the Axiom application platform.
With the help of our [dependency injection](di.html) library we manage to decouple most libraries from dependency on one another.
This is especially important (and true) for libraries providing resources based on external dependencies, such as Zookeeper or DynamoDB.
Decoupling them is important to replace these dependencies with others, without modifying other parts of the code."

"However, at one point our choice of dependencies must be made concrete.
This library provides this point.
All it provides is a `main` function, that loads a configuration file and starts an injector based on it.
The choice of module to be used when initializing the injector is made here.
After calling [di/startup](di.html#startup) to start all services the `main` function enters an infinite `Thread/sleep` loop.
However, it uses `.addShutdownHook` on the JVM's `Runtime` to register [di/shutdown](di.html#shutdown) when the process is interrupted
or killed from the outside."

"This document consists of integration tests for different parts of Axiom."

[[:chapter {:title "Information Tier Integration Test"}]]
"In this section we build an integration test that tests Axiom's entire data processing pipeline,
including [data migration](migrator.html) and a [topology](storm.html)."

[[:section {:title "Overview"}]]
"We base our test on the [tweetlog-clj](https://github.com/brosenan/tweetlog-clj) project, which introduces
a simple Twitter-like application.
We will stream a sequence of facts to it, and sometime during this stream we will introduce the tweetlog application,
triggerring a migration.
We will test that the derived facts created by the rule in the application take into account all facts,
emitted both before and after introducing the app.
This will demonstrate how the migration process complements the topology,
and how data is not lost in the process."

[[:section {:title "Initialization"}]]
"We use the following configuration:"
(def config
  {:zookeeper-config {:url "127.0.0.1:2181"}
   :zk-plan-config {:num-threads 5
                    :parent "/my-plans"}
   :dynamodb-config {:access-key (str "AXIOM" (rand-int 1000000))
                     :secret-key "XXYY"
                     :endpoint "http://localhost:8006"}
   :num-database-retriever-threads 1
   :dynamodb-default-throughput {:read 1 :write 1}
   :dynamodb-event-storage-num-threads 3
   :amqp-config {:username "guest"
                 :password "guest"
                 :vhost "/"
                 :host "localhost"
                 :port 5672}
   :migration-config {:number-of-shards 3
                      :plan-prefix "/my-plans"
                      :clone-location "/tmp"
                      :clone-depth 10}
   :s3-config {:bucket-name (System/getenv "PERMACODE_S3_BUCKET")
               :access-key (System/getenv "AWS_ACCESS_KEY")
               :secret-key (System/getenv "AWS_SECRET_KEY")}
   :local-storm-cluster true
   :fact-spout {:include [:amqp-config]}
   :store-bolt {:include [:dynamodb-event-storage-num-threads
                          :dynamodb-default-throughput
                          :dynamodb-config]}
   :output-bolt {:include [:amqp-config]}
   :initlal-link-bolt {:include [:s3-config]}
   :link-bolt {:include [:s3-config
                         :dynamodb-config
                         :dynamodb-default-throughput
                         :num-database-retriever-threads]}})

"The `injector` function creates an injector based on the given config,
and calls all the `module` functions for all the dependencies."

(def $ (injector config))

"Now we can start-up the system."
(fact
 :integ
 (di/startup $))

"We need to make sure our state is not stored in Zookeeper,
so we clear the trees that store it."
(fact
 :integ
 (println 0)
 (di/do-with! $ [zookeeper]
              (println 1)
              (when (zk/exists zookeeper "/perms")
                (zk/delete-all zookeeper "/perms"))
              (println 2)
              (zk/create zookeeper "/perms" :persistent? true)
              (println 3)
              (when (zk/exists zookeeper "/my-plans")
                (zk/delete-all zookeeper "/my-plans"))
              (println 4)
              (zk/create zookeeper "/my-plans" :persistent? true)
              (println 5)))

[[:section {:title "Input Data"}]]
"For the purpose of this test we consider a Twitter-like app for numbers.
Our \"users\" will therefore be the numbers between 10 and 100, exclusive.
Numbers follow other numbers if they literally *follow* them.
Specifically, each number \"follows\" the ten numbers that precede it."

"We do this in two phases.
In the first phase all numbers follow the five preceiding numbers,
and in the second phase they follow the five preceding them.
This is done in a thread that waits 100 milliseconds between each publication."
(fact
 :integ
 (def following-thread
   (di/do-with! $ [publish]
                (async/thread
                  (let [ts (atom 1000000)
                        phase (fn [offs]
                                (doseq [u1 (range 10 100)
                                        u2 (range (- u1 5 offs) (- u1 offs))]
                                  (publish
                                   {:kind :fact
                                    :name "tweetlog/follows"
                                    :key (str u1)
                                    :data [(str u2)]
                                    :ts (swap! ts inc)
                                    :change 1
                                    :readers #{}
                                    :writers #{(str u1)}})
                                  (Thread/sleep 300)))]
                    (phase 0)
                    (phase 5))))))

"Each number makes two tweets: Hello and Goodbye."
(fact
 :integ
 (def tweet-thread
   (di/do-with! $ [publish]
                (async/thread
                  (let [ts (atom 1000000)]
                    (doseq [u (range 100)
                            msg ["Hello" "Goodbye"]]
                      (publish
                       {:kind :fact
                        :name "tweetlog/tweeted"
                        :key (str u)
                        :data [(str msg " from " u)]
                        :ts (swap! ts inc)
                        :change 1
                        :readers #{}
                        :writers #{(str u)}})
                      (Thread/sleep 200)))))))

[[:section {:title "Introducing the App"}]]
"To start a migration and a subsequent topology we introduce a `axiom/app-version` fact with the version of our code.
We do this after a 10 second delay intended to allow some (but not all) of the facts to already be stored when this rule is introduced."
(fact
 :integ
 (def app-thread
   (di/do-with! $ [publish]
                (async/thread
                  (Thread/sleep (* 10 1000))
                  (publish {:kind :fact
                            :name "axiom/app-version"
                            :key "https://github.com/brosenan/tweetlog-clj.git"
                            :data ["d3a8c6c5b946279186f857381e751801a657f70c"]
                            :ts 1000
                            :change 1
                            :writers #{}
                            :readers #{}})))))

[[:section {:title "Testing the Output"}]]
"If all works as expected, each number in the range 10 to 100 (exclusive) should have exactly 20 (= 10 followees * 2 tweets) tweets in their timeline.
To make sure this is the case we walk through all the numbers in this range and query their timeline.
If we get a smaller number of tweets we sleep and try again to allow the processing to complete for this user.
If the number exceeds 20 we fail."
(fact
 :integ
 (di/do-with! $ [database-chan]
              (doseq [i (range 10 100)]
                (loop []
                  (let [chan (async/chan)
                        ev {:kind :fact
                            :name "perm.QmdLhmeiaJTMPdv7oT7mUsztdtjHq7f16Dw6nkR6JhxswP/followee-tweets"
                            :key (str i)}]
                    (async/>!! database-chan [ev chan])
                    (let [timeline (->> chan
                                        (async/reduce conj #{})
                                        async/<!!)]
                      (cond (< (count timeline) 20)
                            (do
                              (Thread/sleep 1000)
                              (recur))
                            :else
                            (do
                              (count timeline) => 20))))))))

[[:section {:title "Shutting Down"}]]
"Eventually we wait for all threads to complete and shut down the system."

(fact
 :integ
 (async/<!! following-thread)
 (async/<!! tweet-thread)
 (async/<!! app-thread)
 (di/shutdown $))
