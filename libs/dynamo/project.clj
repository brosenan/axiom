(defproject dynamo "0.0.1-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/faraday "1.9.0"]
                 [rabbit-microservices "0.0.1-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})

  
