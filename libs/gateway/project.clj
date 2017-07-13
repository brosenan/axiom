(defproject axiom-clj/gateway "MONOLITH-SNAPSHOT"
  :description "Axiom Gateway Tier"
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.442"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog-events "MONOLITH-SNAPSHOT"]
                 [ring/ring-core "1.6.1"]
                 [ring/ring-codec "1.0.1"]
                 [compojure "1.6.0"]
                 [jarohen/chord "0.8.1"]
                 [http-kit "2.2.0"]
                 [stylefruits/gniazdo "1.0.0"]]
  :monolith/inherit true
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             :midje {}})


  
