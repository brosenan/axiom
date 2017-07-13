(defproject axiom-clj/cloudlog "MONOLITH-SNAPSHOT"
  :description "FIXME: write description"
  :monolith/inherit true
  :permacode-paths ["src"]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [axiom-clj/permacode "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]
                                  [org.clojure/core.logic "0.8.11"]]}})
