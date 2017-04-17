(defproject migrator "0.0.1-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [permacode "0.1.1-SNAPSHOT"]
                 [zk-plan "0.0.1-SNAPSHOT"]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})

  
