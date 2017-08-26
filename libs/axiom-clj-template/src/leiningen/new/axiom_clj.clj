(ns leiningen.new.axiom-clj
  (:require [leiningen.new.templates :refer [renderer name-to-path ->files]]
            [leiningen.core.main :as main]))

(def render (renderer "axiom-clj"))

(defn axiom-clj
  "FIXME: write documentation"
  [name]
  (let [data {:name name
              :sanitized (name-to-path name)
              :axiom-ver "0.3.0"}]
    (main/info "Generating fresh 'lein new' axiom-clj project.")
    (->files data
             ["./.gitignore" (render ".gitignore" data)]
             ["./axiom.log" (render "axiom.log" data)]
             ["./CHANGELOG.md" (render "CHANGELOG.md" data)]
             ["./doc/intro.md" (render "intro.md" data)]
             ["./docker-compose.yml" (render "docker-compose.yml" data)]
             ["./LICENSE" (render "LICENSE" data)]
             ["./project.clj" (render "project.clj" data)]
             ["./README.md" (render "README.md" data)]
             ["./resources/public/index.html" (render "index.html" data)]
             ["./resources/public/tests.html" (render "tests.html" data)]
             ["./src/{{sanitized}}/core.clj" (render "core.clj" data)]
             ["./src/{{sanitized}}/core.cljs" (render "core.cljs" data)]
             ["./test/{{sanitized}}/core_test.clj" (render "core_test.clj" data)]
             ["./test/{{sanitized}}/core_test.cljs" (render "core_test.cljs" data)]
             ["./test/runners/browser.cljs" (render "browser.cljs" data)]
             ["./test/runners/doo.cljs" (render "doo.cljs" data)]
             ["./test/runners/tests.cljs" (render "tests.cljs" data)])))
