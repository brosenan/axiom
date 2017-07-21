(defproject axiom-clj/lein-axiom "MONOLITH-SNAPSHOT"
  :description "A Leiningen plugin for Axiom"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[axiom-clj/axiom-clj "MONOLITH-SNAPSHOT"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [org.clojure/clojure "1.8.0"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}})
