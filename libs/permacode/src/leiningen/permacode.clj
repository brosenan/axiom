(ns leiningen.permacode
  (:require [clojure.java.io :as io]
            [permacode.hasher :as hasher]
            [permacode.publish :as publish]
            [clojure.string :as string]))

(defn get-hasher [project]
  (let [repo (or (:permacode-repo project)
                 (str (System/getProperty "user.home") "/.permacode"))]
    (.mkdirs (io/file repo))
    (hasher/nippy-multi-hasher (hasher/file-store repo))))

(defn publish
  "Publish the current version of the project as permacode"
  [project & namespaces-to-show]
  (let [hashes (apply merge (for [dir (:source-paths project)]
                              (publish/hash-all (get-hasher project) (io/file dir))))
        hashes (if (empty? namespaces-to-show)
                 hashes
                 ; else
                 (into {} (for [ns namespaces-to-show]
                            [ns (hashes (symbol ns))])))]
    (doseq [[key value] hashes]
      (println value "\t" key))))

(defn deps 
  "Retrieve all permacode dependencies for this project"
  [project & args]
  (doseq [dir (:source-paths project)
          file (->> (io/file dir) file-seq (filter (fn [f] (->> f str (re-matches #".*\.clj")))))]
    (let [[hash unhash] (get-hasher project)
          perm-dir (io/file dir "perm")
          [ns' name & clauses] (publish/get-ns file)]
      (doseq [[req' & specs] clauses
              [dep & args] specs]
        (when (string/starts-with? (str dep) "perm.")
          (.mkdirs perm-dir)
          (let [hash-code (string/replace-first (str dep) "perm." "")
                content (unhash hash-code)
                [[ns' name & clauses] & exprs] content
                content (concat [(concat [ns' dep] clauses)] exprs)
                file (io/file perm-dir (str hash-code ".clj"))]
            (when-not (.exists file)
              (println (str file) "(" name ")")
              (with-open [f (io/writer file)]
                (doseq [expr content]
                  (.write f (pr-str expr)))))))))))

(defn permacode
  "Share and use pure functional code"
  {:subtasks [#'publish #'deps]}
  [project & [sub-task & args]]
  (case sub-task
    "publish" (apply publish project args)
    "deps" (apply deps project args)
    (println "A valid task name must be specified.  See lein help permacode")))
