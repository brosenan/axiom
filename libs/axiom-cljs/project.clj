(defproject axiom-cljs "MONOLITH-SNAPSHOT"
  :description "A client library for Axiom"
  :url "http://axiom-clj.org"
  :aliases {"im" ["doo" "phantom" "test" "once"]}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/clojurescript "1.9.671"]
                 [lein-doo "0.1.7"]
                 [devcards "0.2.3"]
                 [org.clojure/core.async "0.3.442"]]
  :plugins [[lein-cljsbuild "1.1.4" :exclusions [[org.clojure/clojure]]]
            [lein-figwheel "0.5.6"]
            [lein-doo "0.1.7"]]
  :clean-targets ^{:protect false} [:target-path "out" "resources/public/cljs"]
  :cljsbuild {
              :test-commands {"test" ["lein" "doo" "phantom" "test" "once"]}
              :builds [{:id "dev"             ; development configuration
                        :source-paths ["src"] ; Paths to monitor for build
                        :figwheel true        ; Enable Figwheel
                        :compiler {:main axiom-cljs.core     ; your main namespace
                                   :asset-path "cljs/out"                       ; Where load-dependent files will go, mind you this one is relative
                                   :output-to "resources/public/cljs/main.js"   ; Where the main file will be built
                                   :output-dir "resources/public/cljs/out"      ; Directory for temporary files
                                   :source-map-timestamp true}                  ; Sourcemaps hurray!
                        }
                       {:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:main runners.doo
                                   :optimizations :none
                                   :output-to "resources/public/cljs/tests/all-tests.js"}}
                       {:id "devcards-test"
                        :source-paths ["src" "test"]
                        :figwheel {:devcards true}
                        :compiler {:main runners.browser
                                   :optimizations :none
                                   :asset-path "cljs/tests/out"
                                   :output-dir "resources/public/cljs/tests/out"
                                   :output-to "resources/public/cljs/tests/all-tests.js"
                                   :source-map-timestamp true}}]}
  :main ^:skip-aot axiom-cljs.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
