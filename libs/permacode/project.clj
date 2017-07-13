(defproject axiom-clj/permacode "MONOLITH-SNAPSHOT"
  :description "A pure dialect of Clojure with content-addressed modules"
  :url "https://github.com/brosenan/axiom"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [mvxcvi/multihash "2.0.1"]
                 [com.taoensso/nippy "2.13.0"]
                 [aysylu/loom "1.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]]}}
  :repositories [["releases" {:url "https://clojars.org/repo"}]])
