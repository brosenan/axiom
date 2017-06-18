(defproject axiom-clj "MONOLITH-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [di "MONOLITH-SNAPSHOT"]
                 [dynamo "MONOLITH-SNAPSHOT"]
                 [migrator "MONOLITH-SNAPSHOT" :exclusions [org.slf4j/slf4j-log4j12]]
                 [rabbit-microservices "MONOLITH-SNAPSHOT"]
                 [s3 "MONOLITH-SNAPSHOT" :exclusions [com.amazonaws/aws-java-sdk]]
                 [storm "MONOLITH-SNAPSHOT"]
                 [zk-plan "MONOLITH-SNAPSHOT" :exclusions [org.slf4j/slf4j-log4j12]]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
