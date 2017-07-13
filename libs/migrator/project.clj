(defproject axiom-clj/migrator "MONOLITH-SNAPSHOT"
  :description "Automatic migration for Axiom apps"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]
                 [zookeeper-clj "0.9.4"]
                 [axiom-clj/zk-plan "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog-events "MONOLITH-SNAPSHOT"]
                 [axiom-clj/s3 "MONOLITH-SNAPSHOT" :exclusions [com.amazonaws/aws-java-sdk]]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]
                                  [axiom-clj/zk-plan "MONOLITH-SNAPSHOT"]
                                  [axiom-clj/rabbit-microservices "MONOLITH-SNAPSHOT"]
                                  [axiom-clj/dynamo "MONOLITH-SNAPSHOT"]]}
             :midje {}})

  
