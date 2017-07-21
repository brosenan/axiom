(defproject axiom-clj/examples "MONOLITH-SNAPSHOT"
  :description "Axiom usage examples"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :permacode-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]]
  :xxx-plugins [[axiom-clj/lein-axiom "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}})
             ;; Note that Midje itself is in the `dev` profile to support
             ;; running autotest in the repl.

  
