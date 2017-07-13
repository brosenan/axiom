(defproject axiom-clj/axiom-clj "MONOLITH-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [axiom-clj/dynamo "MONOLITH-SNAPSHOT"]
                 [axiom-clj/migrator "MONOLITH-SNAPSHOT" :exclusions [org.slf4j/slf4j-log4j12]]
                 [axiom-clj/rabbit-microservices "MONOLITH-SNAPSHOT"]
                 [axiom-clj/s3 "MONOLITH-SNAPSHOT" :exclusions [com.amazonaws/aws-java-sdk]]
                 [axiom-clj/storm "MONOLITH-SNAPSHOT"]
                 [axiom-clj/zk-plan "MONOLITH-SNAPSHOT" :exclusions [org.slf4j/slf4j-log4j12]]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
