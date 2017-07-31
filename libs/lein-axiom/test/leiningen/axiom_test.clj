(ns leiningen.axiom-test
  (:require [midje.sweet :refer :all]
            [leiningen.axiom :refer :all]
            [org.httpkit.client :as http]
            [clojure.java.io :as io]
            [permacode.publish :as publish]
            [clojure.pprint :as ppr]))

[[:chapter {:title "Introduction"}]]
"`lein-axiom` is a [leiningen](https://leiningen.org) plugin for automating Axiom-related tasks.
It provides the sub-tasks [deploy](#deploy) and [run](#run)."
(fact
 (-> #'axiom meta :doc) => "Automating common Axiom tasks"
 (-> #'axiom meta :subtasks) => [#'deploy #'run #'deps #'pprint]
 (-> #'deploy meta :doc) => "Deploy the contents of the project to the configured Axiom instance"
 (-> #'run meta :doc) => "Run an Axiom instance"
 (-> #'deps meta :doc) => "Recursively add permacode dependencies to this project"
 (-> #'pprint meta :doc) => "Prints the contents of the given permacode module")

"The `lein-axiom` plugin requires the keys `:axiom-deploy-config` and `:axiom-run-config` to be present and contain configuration maps.
These configuration maps are used to initialize [DI](di.html) [injectors](di.html#injector) so that Axiom's components are available to the plugin.
`:axiom-run-config` is used by [lein axiom run](#run), while `:axiom-deploy-config` is used by all other tasks."

[[:chapter {:title "deploy"}]]
"`lein axiom deploy` stores the contents of the project using a [hasher](permacode.hasher.html#introduction),
publishes an `axiom/perm-versions` event.
This event is then handled by Axiom's [migrator](migrator.html) to deploy the code, and by Axiom's [gateway](gateway.html) to [serve static files](gateway.html#static-handler)."
(fact
 (let [published (atom [])
       output (atom "")
       project {:axiom-deploy-config
                {:publish (partial swap! published conj)
                 :deploy-dir (fn [ver dir publish]
                               (publish {:ver ver
                                         :dir dir}))
                 :uuid (constantly "ABCDEFG")
                 :println (partial swap! output str)}}]
   (axiom project "deploy") => nil
   (provided
    (rand-int 10000000) => 5555)
   @published => [{:ver "dev-5555"
                   :dir "."}]
   @output => "Deployed a new version: dev-5555"))

[[:chapter {:title "run"}]]
"`lein axiom run` starts an instance of Axiom based on *a merge of `:axiom-deploy-config` and `:axiom-run-config`*, with pereference to the latter.
It will remain running until interrupted (Ctrl+C) by the user."
(fact
 (let [project {:axiom-run-config {:http-config {:port 33333}}
                :axiom-deploy-config
                {:ring-handler (fn [req] {:status 200
                                          :body "Hello"})
                 :http-config {:port 44444} ;; This will be overridden by :axiom-run-config
                 }}
       fut (future
             (axiom project "run"))]
   (Thread/sleep 100)
   (let [res @(http/get "http://localhost:33333")]
     (:status res) => 200
     (-> res :body slurp) => "Hello")))

[[:chapter {:title "deps"}]]
"`.clj` files in Axiom apps are requied to be [valid permacode source files](permacode.validate.html#introduction).
This means, among other things, that they [cannot `:require` any namespaces](permacode.validate.html#validate-ns), except for a small white-list of pure ones.
The way to use external libraries (such as [cloudlog.core](cloudlog.html), which defines the `defrule` and `defclause` macros),
one needs to `:require` them in the form `perm.*`, where `*` is replaced with a hash-code representing the dependency's content.
Once deployed on Axiom, Axiom will look up these dependencies by their hash-code.
However, for the purpose of running this code in the development environment we need a way to also add the dependencies to the project."

"Running `lein axiom deps` will [look-up all `.clj` files in the project's `:source-paths`](#all-source-files),
and for each such file will [extract the dependent `perm.*` namespaces](#required-perms).
Then, it will use the hasher configured in `:axiom-deploy-config` to extract these dependencies and [write them to `.clj` files](#create-perm-file)
in the project's *first source-path*, if they don't already exist."
(fact
 (let [project {:axiom-deploy-config {:hasher [..hash.. ..unhash..]}
                :source-paths ["/path/to/proj/src1" "/path/to/proj/src2"]}]
   (axiom project "deps") => nil
   (provided
    (all-source-files project) => [..src1.. ..src2..]
    (required-perms ..src1..) => ["FOO" "BAR"]
    (required-perms ..src2..) => ["BAZ"]
    (io/file "/path/to/proj/src1") => ..src-dir..
    (create-perm-file "FOO" [..hash.. ..unhash..] ..src-dir..) => false
    (create-perm-file "BAR" [..hash.. ..unhash..] ..src-dir..) => false
    (create-perm-file "BAZ" [..hash.. ..unhash..] ..src-dir..) => false)))

"If new `perm.*` namespaces are introduced, `lein axiom deps` works iteratively to fetch their dependencies."
(fact
 (let [project {:axiom-deploy-config {:hasher [..hash.. ..unhash..]}
                :source-paths ["/path/to/proj/src1"]}]
   (axiom project "deps") => nil
   (provided
    (all-source-files project) => [..src1.. ..src2..]
    (required-perms ..src1..) =streams=> [["FOO" "BAR"]
                                          ["FOO" "BAR" "QUUX"]]
    (required-perms ..src2..) => ["BAZ"]
    (io/file "/path/to/proj/src1") => ..src-dir..
    (create-perm-file "FOO" [..hash.. ..unhash..] ..src-dir..) => false
    (create-perm-file "BAR" [..hash.. ..unhash..] ..src-dir..) =streams=> [true false]
    (create-perm-file "BAZ" [..hash.. ..unhash..] ..src-dir..) => false
    (create-perm-file "QUUX" [..hash.. ..unhash..] ..src-dir..) => false)))

[[:chapter {:title "pprint"}]]
"`lein axiom pprint` pretty-prints the content of a permacode module, associated with the given hashcode.
It uses the `hasher` specified in the project's `:axiom-deploy-config` to fetch the module."
(fact
 (let [project {:axiom-deploy-config {:hasher [(fn [])
                                               (fn [hashcode]
                                                 (when-not (= hashcode "THEHASHCODE")
                                                   (throw (Exception. "Bad hashcode")))
                                                 {:some :content})]}}]
   (axiom project "pprint" "THEHASHCODE") => nil
   (provided
    (ppr/pprint {:some :content}) => irrelevant)))

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "all-source-files"}]]
"Given a `project`, `all-source-files` returns all the `*.clj` files located under all `:source-paths` in the `project`."
(fact
 (all-source-files {:source-paths ["/path/to/proj/src1"
                                   "/path/to/proj/src2"]}) => [(io/file "/path/to/proj/src1/foo.clj")
                                                               (io/file "/path/to/proj/src2/bar.clj")
                                                               (io/file "/path/to/proj/src2/baz.clj")]
 (provided
  (file-seq (io/file "/path/to/proj/src1")) => [(io/file "/path/to/proj/src1/foo.clj")
                                                (io/file "/path/to/proj/src1/x.cljs")
                                                (io/file "/path/to/proj/src1/index.html")]
  (file-seq (io/file "/path/to/proj/src2")) => [(io/file "/path/to/proj/src2/bar.clj")
                                                (io/file "/path/to/proj/src2/baz.clj")]))

[[:section {:title "required-perms"}]]
"Given a file, `required-perms` returns all the permacode hash-codes required by that source file."
(fact
 (required-perms ..file..) => ["FOO" "BAR" "BAZ"]
 (provided
  (publish/get-ns ..file..) => '(ns some.ns
                                  (:require [clojure.string :as str]
                                            [perm.FOO :as foo])
                                  (:require [perm.BAR]
                                            [perm.BAZ :as baz]))))

[[:section {:title "create-perm-file"}]]
"Given a hash-code, a [hasher](permacode.hasher.html#introduction) and a source path,
`create-perm-file` creates a `.clj` source file with the underlying content.
The file will be placed under the `perm` directory because the namespace is named `perm.*` (where `*` is the given hash-code).
Its `ns` header will be updated to include the `perm.*` name.
It returns whether a new file needed to be created."
(fact
 (let [hasher [(fn [code content]
                 (throw (Exception. "This should not be called")))
               (fn [hashcode]
                 '[(ns original.name
                     (:require [something]))
                   (foo 123)
                   (bar 234)])]
       source-dir (io/file "/tmp" (str "testsrc-" (rand-int 100000)))]
   (create-perm-file "FOO" hasher source-dir) => true
   (-> (io/file source-dir "perm") .exists) => true
   (-> (io/file source-dir "perm" "FOO.clj") slurp) => "(ns perm.FOO (:require [something]))(foo 123)(bar 234)"
   (create-perm-file "FOO" hasher source-dir) => false ;; This file already exists
   ))
