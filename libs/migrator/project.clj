(defproject migrator "MONOLITH-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [permacode "MONOLITH-SNAPSHOT"]
                 [zookeeper-clj "0.9.4"]
                 [zk-plan "MONOLITH-SNAPSHOT"]
                 [cloudlog-events "MONOLITH-SNAPSHOT"]
                 [s3 "MONOLITH-SNAPSHOT" :exclusions [com.amazonaws/aws-java-sdk]]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [cloudlog "MONOLITH-SNAPSHOT"]
                                  [zk-plan "MONOLITH-SNAPSHOT"]
                                  [rabbit-microservices "MONOLITH-SNAPSHOT"]
                                  [dynamo "MONOLITH-SNAPSHOT"]]}
             :midje {}})

  
