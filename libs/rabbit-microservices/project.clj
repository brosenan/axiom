(defproject axiom-clj/rabbit-microservices "MONOLITH-SNAPSHOT"
  :description "A RabbitMQ adapter for Axiom"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.novemberain/langohr "3.6.1"]
                 [com.taoensso/nippy "2.13.0"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog-events "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
