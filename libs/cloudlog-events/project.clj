(defproject axiom-clj/cloudlog-events "MONOLITH-SNAPSHOT"
  :description "Event processors for Cloudlog"
  :monolith/inherit true
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.395"]
                 [axiom-clj/cloudlog "MONOLITH-SNAPSHOT"]]
  :profiles {:dev {:dependencies [[midje "1.8.3"]
                                  [im.chit/lucid.publish "1.2.8"]
                                  [im.chit/hara.string.prose "2.4.8"]]
                   :plugins [[lein-midje "3.2.1"]
                             [permacode/permacode "0.1.0"]]}}
  :publish {:theme  "bolton"
            :template {:site   "cloudlog-event"
                       :author "Boaz Rosenan"
                       :email  "brosenan@gmail.com"
                       :url "https://github.com/brosenan/cloudlog-event"}
            :output "docs"
            :files {"core"
                    {:input "test/cloudlog_events/core_test.clj"
                     :title "core"
                     :subtitle "Event Processing"}}})
