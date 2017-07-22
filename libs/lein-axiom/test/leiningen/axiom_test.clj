(ns leiningen.axiom-test
  (:require [midje.sweet :refer :all]
            [leiningen.axiom :refer :all]
            [org.httpkit.client :as http]))

[[:chapter {:title "Introduction"}]]
"`lein-axiom` is a [leiningen](https://leiningen.org) plugin for automating Axiom-related tasks.
It provides the sub-tasks [deploy](#deploy) and [run](#run)."
(fact
 (-> #'axiom meta :doc) => "Automating common Axiom tasks"
 (-> #'axiom meta :subtasks) => [#'deploy #'run]
 (-> #'deploy meta :doc) => "Deploy the contents of the project to the configured Axiom instance"
 (-> #'run meta :doc) => "Run an Axiom instance")

"The `lein-axiom` plugin requires an `:axiom-config` key to be present in the project.
This configuration is used to initialize a [DI](di.html) [injector](di.html#injector) so that Axiom's components are available to the plugin."

[[:chapter {:title "deploy"}]]
"`lein axiom deploy` stores the contents of the project using a [hasher](permacode.hasher.html#introduction),
publishes an `axiom/perm-versions` event.
This event is then handled by Axiom's [migrator](migrator.html) to deploy the code, and by Axiom's [gateway](gateway.html) to [serve static files](gateway.html#static-handler)."
(fact
 (let [published (atom [])
       output (atom "")
       project {:axiom-config {:publish (partial swap! published conj)
                               :deploy-dir (fn [ver dir publish]
                                             (publish {:ver ver
                                                       :dir dir}))
                               :uuid (constantly "ABCDEFG")
                               :println (partial swap! output str)}}]
   (axiom project "deploy") => nil
   (provided
    (rand-int 10000000) => 5555)
   @published => [{:ver "dev-5555"
                   :dir "."}]))

[[:chapter {:title "run"}]]
"`lein axiom run` starts an instance of Axiom based on the `:axiom-config`.
It will remain running until interrupted (Ctrl+C) by the user."
(fact
 (let [project {:axiom-config {:ring-handler (fn [req] {:status 200
                                                        :body "Hello"})
                               :http-config {:port 33333}}}
       fut (future
             (axiom project "run"))]
   (Thread/sleep 100)
   (let [res @(http/get "http://localhost:33333")]
     (:status res) => 200
     (-> res :body slurp) => "Hello")))
