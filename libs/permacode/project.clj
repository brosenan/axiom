(defproject permacode "0.1.1-SNAPSHOT"
  :description "A pure dialect of Clojure with content-addressed modules"
  :url "http://github.com/brosenan/permacode"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [mvxcvi/multihash "2.0.1"]
                 [com.taoensso/nippy "2.13.0"]
                 [aysylu/loom "1.0.0"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]]
                   :plugins [[lein-midje "3.2.1"]
                             [lein-pprint "1.1.2"]
                             [permacode/permacode "0.1.0-SNAPSHOT"]]}}
  :repositories [["releases" {:url "https://clojars.org/repo"}]])
