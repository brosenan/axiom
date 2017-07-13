(defproject axiom-clj/rabbit-microservices "MONOLITH-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.novemberain/langohr "3.6.1"]
                 [com.taoensso/nippy "2.13.0"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
