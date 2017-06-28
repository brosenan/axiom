(ns gateway.core
  (:require [di.core :as di]
            [clojure.core.async :as async]
            [cloudlog-events.core :as ev]
            [cloudlog.interset :as interset]
            [ring.middleware.content-type :as ctype]))


(defn cookie-version-selector [handler]
  (fn [req resp raise]
    (handler (assoc req :app-version (get-in req [:cookies "app-version"])) resp raise)))

(defn module [$]
  (di/provide $ identity-set [database-chan]
              (let [database-chan (ev/accumulate-db-chan database-chan)]
                (fn [user relations]
                  (async/go
                    (cond (nil? user)
                        interset/universe
                        :else
                        (let [chanmap (into {} (for [rel relations]
                                                 [rel (async/chan 2)]))]
                          (doseq [[rel chan] chanmap]
                            (async/>! database-chan [{:kind :fact
                                                      :name rel
                                                      :key user} chan]))
                          (let [xform (comp
                                       (filter (fn [ev] (contains? (:writers ev) (-> ev
                                                                                     :name
                                                                                     keyword
                                                                                     namespace))))
                                       (filter (fn [ev] (interset/subset? #{user} (:readers ev))))
                                       (map (fn [ev] (vec (cons (keyword (:name ev)) (:data ev))))))
                                trans-chan (async/chan 1 xform)]
                            (async/pipe (async/merge (vals chanmap)) trans-chan)
                            (async/<! (async/reduce conj #{user} trans-chan)))))))))
  (di/provide $ authenticator [use-dummy-authenticator]
              (fn [handler]
                (fn [req res raise]
                  (let [id (or (get-in req [:params "_identity"])
                               (get-in req [:cookies "user_identity"]))]
                    (cond id
                          (handler (-> req
                                       (assoc :identity id))
                                   (fn [resp]
                                     (-> resp
                                         (update-in [:cookies] #(assoc % "user_identity" id))
                                         res)) raise)
                          :else
                          (handler req res raise))))))
  (di/provide $ version-selector [dummy-version]
              (fn [handler]
                (let [handler (cookie-version-selector handler)]
                  (fn [req resp raise]
                    (let [req (cond (contains? (:cookies req) "app-version")
                                    req
                                    :else
                                    (assoc-in req [:cookies "app-version"] dummy-version))]
                      (handler req resp raise))))))
  (di/provide $ static-handler [version-selector
                                database-chan
                                hasher]
              (-> (fn [req resp raise]
                    (async/go
                      (let [resp-chan (async/chan)
                            [_ unhash] hasher]
                        (async/>! database-chan [{:kind :fact
                                                  :name "axiom/perm-versions"
                                                  :key (:app-version req)} resp-chan])
                        (let [[ans] (->> resp-chan
                                          (async/reduce conj [])
                                          (async/<!))
                              [perms static] (:data ans)
                              hashcode (static (:uri req))]
                          (cond (nil? hashcode)
                                (resp {:status 404
                                       :body "Not Found!"})
                                :else
                                (let [content (unhash hashcode)]
                                  (resp {:status 200
                                         :body (clojure.java.io/input-stream content)})))))))
                  ctype/wrap-content-type
                  version-selector))
  (di/provide $ wrap-authorization [identity-set
                                    authenticator]
              (fn [handler]
                (-> (fn [req resp raise]
                      (async/go
                        (let [id-set (async/<! (identity-set (:identity req)))]
                          (handler (assoc req :identity-set id-set) resp raise))))
                    authenticator))))
