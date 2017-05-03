(defproject migrator "0.0.1-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [permacode "0.1.1-SNAPSHOT"]
                 [zookeeper-clj "0.9.4"]
                 [zk-plan "0.0.1-SNAPSHOT"]
                 [cloudlog-events "0.1.0-SNAPSHOT"]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [cloudlog "MONOLITH-SNAPSHOT"]
                                  [examples "MONOLITH-SNAPSHOT"]]}
             :midje {}})

  
