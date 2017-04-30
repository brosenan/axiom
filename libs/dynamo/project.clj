(defproject dynamo "0.0.1-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [com.taoensso/faraday "1.9.0"]
                 [com.taoensso/nippy "2.13.0"]
                 [rabbit-microservices "0.0.1-SNAPSHOT"]
                 [org.clojure/core.async "0.3.442"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [com.novemberain/langohr "3.6.1"]
                                  [rabbit-microservices "0.0.1-SNAPSHOT"]]}
             :midje {}})

  
