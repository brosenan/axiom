(ns s3.core
  (:require [di.core :as di]
            [aws.sdk.s3 :as s3]
            [clojure.java.io :as io]
            [permacode.hasher :as hasher]))

(defn module [$]
  (di/provide $ storage [s3-config info]
              (let [bucket (:bucket-name s3-config)]
                [(fn [key val]
                   (info {:source "s3"
                          :desc (str "storing " key " to bucket " bucket)})
                   (s3/put-object s3-config bucket key (io/input-stream val))
                   true)
                 (fn [key]
                   (info {:source "s3"
                          :desc (str "retreiving " key " from bucket " bucket)})
                   (-> (s3/get-object s3-config bucket key)
                       :content
                       hasher/slurp-bytes))]))
  (di/provide $ hasher [storage]
              (hasher/nippy-multi-hasher storage)))
