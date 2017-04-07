(defproject axiom "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]]
  :plugins [[lein-monolith "0.3.2"]
            [lein-cprint "1.2.0"]]
  :monolith {:project-dirs ["libs/*"]})
