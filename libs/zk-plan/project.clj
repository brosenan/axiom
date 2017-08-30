(defproject axiom-clj/zk-plan "MONOLITH-SNAPSHOT"
  :description "A batch processing framework based on Zookeeper"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [zookeeper-clj "0.9.4"]
                 [functionalbytes/zookeeper-loop "0.1.0"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]]
                   :plugins [[lein-midje "3.2.1"] [lein-hydrox "0.1.17"]]}})

