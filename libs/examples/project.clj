(defproject axiom-clj/examples "MONOLITH-SNAPSHOT"
  :description "Axiom usage examples"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :permacode-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]]
  :plugins [[axiom-clj/lein-axiom "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}}
  :axiom-config {:zookeeper-config {:url "127.0.0.1:2181"}
                 :zk-plan-config {:num-threads 5
                                  :parent "/my-plans"}
                 :dynamodb-config {:access-key "STANDALONE-DB"
                                   :secret-key "XXYY"
                                   :endpoint "http://localhost:8006"}
                 :num-database-retriever-threads 1
                 :dynamodb-default-throughput {:read 1 :write 1}
                 :dynamodb-event-storage-num-threads 3
                 :rabbitmq-config {:username "guest"
                                   :password "guest"
                                   :vhost "/"
                                   :host "localhost"
                                   :port 5672}
                 :migration-config {:number-of-shards 3
                                    :plan-prefix "/my-plans"
                                    :clone-location "/tmp"
                                    :clone-depth 10}
                 :storage-local-path "/tmp/axiom-perms"
                 :storage-fetch-url "https://s3.amazonaws.com/brosenan-test"
                 :local-storm-cluster true
                 :fact-spout {:include [:rabbitmq-config]}
                 :store-bolt {:include [:dynamodb-event-storage-num-threads
                                        :dynamodb-default-throughput
                                        :dynamodb-config]}
                 :output-bolt {:include [:rabbitmq-config]}
                 :initlal-link-bolt {:include [:s3-config]}
                 :link-bolt {:include [:s3-config
                                       :dynamodb-config
                                       :dynamodb-default-throughput
                                       :num-database-retriever-threads]}
                 :use-dummy-authenticator true ;; Completely remove this entry to avoid the dummy authenticator
                 :dummy-version "12345"
                 :http-config {:port 8080}})

 
