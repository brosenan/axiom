(ns s3.core
  (:require [di.core :as di]
            [aws.sdk.s3 :as s3]
            [clojure.java.io :as io]
            [permacode.hasher :as hasher]))

(defn module [$]
  (di/provide $ storage [s3-config]
              [(fn [key val]
                 (s3/put-object s3-config (:bucket-name s3-config) key (io/input-stream val))
                 true)
               (fn [key]
                 (-> (s3/get-object s3-config (:bucket-name s3-config) key)
                     :content
                     hasher/slurp-bytes))])
  (di/provide $ hasher [storage]
              (hasher/nippy-multi-hasher storage)))
