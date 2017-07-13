(defproject axiom "0.1.1"
  :description "FIXME: write description"
  :url "http://brosenan.github.io/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :plugins [[lein-monolith "1.0.0"]
            [lein-cprint "1.2.0"]
            [lein-midje "3.2.1"]
            [axiom-clj/permacode "0.1.0-SNAPSHOT"]]
  :aliases {"im" ["do"
                  ["midje" ":filter" "-integ"]
                  ["install"]]
            "eim" ["monolith" "each" "im"]
            "autotest" ["midje" ":autotest" ":filter" "-integ"]
            "integ" ["midje" ":filter" "integ"]
            "einteg" ["monolith" "each" "integ"]
            "epub" ["monolith" "each" "permacode" "publish"]}
  :monolith {:project-dirs ["libs/*"]
             :inherit [:aliases :plugins]}
  :repositories [["releases" {:url "https://clojars.org/repo"
                              :creds :gpg}]]
  :profiles {:dev {:dependencies [[im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]]}}
  :publish {:theme  "bolton"
            :template {:site   "axiom"
                       :author "Boaz Rosenan"
                       :email  "brosenan@gmail.com"
                       :url "https://github.com/brosenan/axiom"}
            :output "docs"
            :files {"docs"
                    {:template "docs.html"}
                    "cloudlog"
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
                     :subtitle "Dependency Injection"}
                    "s3"
                    {:input "libs/s3/test/s3/core_test.clj"
                     :title "s3.core"
                     :subtitle "An Amazon AWS-S3-based Hasher"}
                    "storm"
                    {:input "libs/storm/test/storm/core_test.clj"
                     :title "storm.core"
                     :subtitle "Converting Cloudlog Rules to Apache Storm Topologies"}
                    "gateway"
                    {:input "libs/gateway/test/gateway/core_test.clj"
                     :title "gateway.core"
                     :subtitle "Axiom's External Interface"}
                    "axiom-clj"
                    {:input "libs/axiom-clj/test/axiom_clj/core_test.clj"
                     :title "axiom-clj.core"
                     :subtitle "An Integration of All Dependencies"}
                    "axiom-cljs"
                    {:input "libs/axiom-cljs/test/axiom_cljs/core_test.cljs"
                     :title "axiom-cljs.core"
                     :subtitle "A Client Library"}}})
