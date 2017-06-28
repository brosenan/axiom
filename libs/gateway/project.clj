(defproject gateway "MONOLITH-SNAPSHOT"
  :description "Axiom Gateway Tier"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.442"]
                 [di "MONOLITH-SNAPSHOT"]
                 [cloudlog "MONOLITH-SNAPSHOT"]
                 [cloudlog-events "MONOLITH-SNAPSHOT"]
                 [ring/ring-core "1.6.1"]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
