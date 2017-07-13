(defproject axiom-clj/storm "MONOLITH-SNAPSHOT"
  :description "Cool new project to do things and stuff"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.apache.storm/storm-core "1.1.0"]
                 [org.clojure/core.async "0.3.442"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]
                 [axiom-clj/cloudlog-events "MONOLITH-SNAPSHOT"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}})
             ;; Note that Midje itself is in the `dev` profile to support
             ;; running autotest in the repl.

  
