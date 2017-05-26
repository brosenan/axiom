(defproject examples "MONOLITH-SNAPSHOT"
  :description "Axiom usage examples"
  :monolith/inherit true
  :permacode-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [permacode "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [cloudlog "MONOLITH-SNAPSHOT"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}})
             ;; Note that Midje itself is in the `dev` profile to support
             ;; running autotest in the repl.

  