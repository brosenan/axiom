(defproject axiom "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://brosenan.github.io/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :plugins [[lein-monolith "0.3.2"]
            [lein-cprint "1.2.0"]]
  :aliases {"im" ["do" ["midje" ":filter" "-integ"] ["install"]]
            "eim" ["monolith" "each" "im"]
            "autotest" ["midje" ":autotest" ":filter" "-integ"]
            "integ" ["midje" ":filter" ":integ"]
            "einteg" ["monolith" "each" "integ"]}
  :monolith {:project-dirs ["libs/*"]
             :inherit [:aliases]}
  :profiles {:dev {:dependencies [[im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]]}}
  :publish {:theme  "bolton"
            :template {:site   "axiom"
                       :author "Boaz Rosenan"
                       :email  "brosenan@gmail.com"
                       :url "https://github.com/brosenan/axiom"}
            :output "docs"
            :files {"cloudlog"
                    {:input "libs/cloudlog/test/cloudlog/core_test.clj"
                     :title "cloudlog.core"
                     :subtitle "Rule semantics"}
                    "cloudlog.interset"
                    {:input "libs/cloudlog/test/cloudlog/interset_test.clj"
                     :title "cloudlog.interset"
                     :subtitle "Intersection Sets"}
                    "cloudlog.graph"
                    {:input "libs/cloudlog/test/cloudlog/graph_test.clj"
                     :title "cloudlog.graph"
                     :subtitle "Graph Utilities"}
                    "cloudlog-events"
                    {:input "libs/cloudlog-events/test/cloudlog_events/core_test.clj"
                     :title "cloudlog-events.core"
                     :subtitle "Event Processing"}
                    "zk-plan"
                    {:input "libs/zk-plan/test/zk_plan/core_test.clj"
                     :title "zk-plan.core"
                     :subtitle "A batch processing framework"}
                    "rabbit-microservices"
                    {:input "libs/rabbit-microservices/test/rabbit_microservices/core_test.clj"
                     :title "rabbit-microservices.core"
                     :subtitle "A Microservices Framwork Based on RabbitMQ"}
                    "dynamo"
                    {:input "libs/dynamo/test/dynamo/core_test.clj"
                     :title "dynamo.core"
                     :subtitle "DynamoDB Integration"}
                    "dynamo.srv"
                    {:input "libs/dynamo/test/dynamo/srv_test.clj"
                     :title "dynamo.srv"
                     :subtitle "A microservice for Updating DynamoDB Tables"}
                    "permacode"
                    {:input "libs/permacode/test/permacode/core_test.clj"
                     :title "permacode.core"
                     :subtitle "Subsetting Clojure"}
                    "permacode.hasher"
                    {:input "libs/permacode/test/permacode/hasher_test.clj"
                     :title "permacode.hasher"
                     :subtitle "Content Addressable Storage"}
                    "permacode.validate"
                    {:input "libs/permacode/test/permacode/validate_test.clj"
                     :title "permacode.validate"
                     :subtitle "Static Analysis"}
                    "permacode.symbols"
                    {:input "libs/permacode/test/permacode/symbols_test.clj"
                     :title "permacode.symbols"
                     :subtitle "Extracting Symbols Used by Expressions"}
                    "permacode.publish"
                    {:input "libs/permacode/test/permacode/publish_test.clj"
                     :title "permacode.publish"
                     :subtitle "Store Local Code and Get Hashes"}
                    "migrator"
                    {:input "libs/migrator/test/migrator/core_test.clj"
                     :title "migrator.core"
                     :subtitle "Initiating Data Migrations for Rules"}
                    "di"
                    {:input "libs/di/test/di/core_test.clj"
                     :title "di.core"
                     :subtitle "Dependency Injection"}}})
