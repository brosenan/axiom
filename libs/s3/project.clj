(defproject axiom-clj/s3 "MONOLITH-SNAPSHOT"
  :description "S3 storage provider"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [clj-aws-s3 "0.3.8"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]
                 [axiom-clj/di "MONOLITH-SNAPSHOT"]
                 [http-kit "2.2.0"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]]}
             ;; You can add dependencies that apply to `lein midje` below.
             ;; An example would be changing the logging destination for test runs.
             :midje {}})
             ;; Note that Midje itself is in the `dev` profile to support
             ;; running autotest in the repl.

  
