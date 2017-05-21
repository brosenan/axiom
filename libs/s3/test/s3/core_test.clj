(ns s3.core-test
  (:require [midje.sweet :refer :all]
            [s3.core :refer :all]
            [di.core :as di]
            [aws.sdk.s3 :as s3]
            [permacode.hasher :as hasher]
            [taoensso.nippy :as nippy]
            [multihash.digest :as digest]
            [multihash.core :as multihash]))

[[:chapter {:title "Introduction"}]]
"This library provides a [hasher](permacode.hasher.html) that stores its content in [Amazon's S3](https://aws.amazon.com/s3/)."

"The library requires configuration providing credentials for accessing S3, as well as a bucket name, that is assumed to pre-exist.
It provides the [resources](di.html) [storage](#storage) and [hasher](#hasher) in return."

[[:chapter {:title "storage"}]]
"As its definition in [permacode](permacode.hasher.html#introduction),
storage is a pair of functions (see [file-store](permacode.hasher.html#file-store) as an example),
which store content under a key, and fetch stored content given a key, respectively."

"It depends on `s3-config`, a map of properties containing the `:bucket-name` in which values are to be stored,
as well as credentials for accessing S3.  See [clj-aws-s3 documentation](https://github.com/nside/clj-aws-s3) for more details."
(fact
 (let [config {:bucket-name "foo"
               :access-key "bar"
               :secret-key "baz"}
       $ (di/injector {:s3-config config})]
   (module $)
   (di/startup $)
   (di/do-with! $ [storage]
                (let [[store retr] storage
                      content (.getBytes "foo bar" "UTF-8")]
                  (store "abcd" content) => true
                  (provided
                   (clojure.java.io/input-stream content) => ..inp..
                   (s3/put-object config "foo" "abcd" ..inp..) => irrelevant)
                  (retr "abcd") => content
                  (provided
                   (s3/get-object config "foo" "abcd") => {:content ..inp2..}
                   (hasher/slurp-bytes ..inp2..) => content)))))


[[:chapter {:title "hasher"}]]
"The `hasher` resource is a [nippy-multi-hasher](permacode.hasher.html#nippy-multi-hasher) based on the `storage` resource as its storage."
(fact
 (let [calls (transient [])
       $ (di/injector {:storage [(fn [k v]
                                   (conj! calls [:store k v]))
                                 (fn [k])]})]
   (module $)
   (di/startup $)
   (di/do-with! $ [hasher]
                (let [[hash unhash] hasher]
                  (hash ..expr..) => ..hashcode..
                  (provided
                   (nippy/freeze ..expr.. nil) => ..bin..
                   (digest/sha2-256 ..bin..) => ..mhash..
                   (multihash/base58 ..mhash..) => ..hashcode..)
                  (persistent! calls) => [[:store ..hashcode.. ..bin..]]))))

[[:chapter {:title "Usage Example"}]]
"To actually work against S3 we need to provide real credentials.
We provide them using environment variables:"
(fact
 :integ
 (def config {:bucket-name "brosenan-test"
              :access-key (System/getenv "AWS_ACCESS_KEY")
              :secret-key (System/getenv "AWS_SECRET_KEY")}))

"We provide this to the injector, and start it."
(fact
 :integ
 (def $ (di/injector {:s3-config config}))
 (module $)
 (di/startup $))

"We will randomize a value to be sure we actually store and retrieve a new value."
(fact
 :integ
 (def value (rand-int 100000)))

"Now we can store a value."
(fact
 :integ
 (di/do-with! $ [hasher]
              (let [[hash unhash] hasher]
                (def hash-code (hash {:value value})))))

"...and retrieve it..."
(fact
 :integ
 (di/do-with! $ [hasher]
              (let [[hash unhash] hasher]
                (unhash hash-code) => {:value value})))
