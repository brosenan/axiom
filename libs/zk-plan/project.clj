(defproject zk-plan "0.0.1-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"] [zookeeper-clj "0.9.4"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]]
                   :plugins [[lein-midje "3.2.1"] [lein-hydrox "0.1.17"]]}})

