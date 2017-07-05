(ns gateway.core
  (:require [di.core :as di]
            [clojure.core.async :as async]
            [cloudlog-events.core :as ev]
            [cloudlog.interset :as interset]
            [ring.middleware.content-type :as ctype]
            [ring.util.codec :as codec]
            [clojure.edn :as edn]))


(defn any? [list]
  (some? (some identity list)))

(defn cookie-version-selector [handler]
  (fn [req resp raise]
    (handler (assoc req :app-version (get-in req [:cookies "app-version"])) resp raise)))


(defn uri-partial-event [req]
  (let [{:keys [ns name key]} (:route-params req)]
    {:kind :fact
     :name (str (symbol ns name))
     :key (cond key
                (-> key codec/url-decode edn/read-string))}))

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
                      (let [rule-list (or (get-in req [:headers "Axiom-Id-Rules"])
                                          [])]
                        (async/go
                          (let [id-set (async/<! (identity-set (:identity req) rule-list))]
                            (handler (assoc req :identity-set id-set) resp raise)))))
                    authenticator)))
  (di/provide $ get-fact-handler [wrap-authorization
                                  database-chan]
              (-> (fn [req resp raise]
                    (async/go
                      (let [reply-chan (async/chan 2 (comp
                                                      (filter (fn [ev] (interset/subset? (:identity-set req) (:readers ev))))
                                                      (map #(-> %
                                                                (dissoc :kind)
                                                                (dissoc :name)
                                                                (dissoc :key)))))
                            database-chan (ev/accumulate-db-chan database-chan)]
                        (async/>! database-chan [(uri-partial-event req) reply-chan])
                        (let [events (->> reply-chan
                                          (async/reduce conj nil)
                                          async/<!)]
                          (resp {:status 200
                                 :headers {"Content-Type" "application/edn"}
                                 :body (pr-str events)})))))
                  wrap-authorization))
  (di/provide $ patch-fact-handler [publish
                                    wrap-authorization]
              (->
               (fn [req resp raise]
                 (let [events (-> req :body slurp edn/read-string)]
                   (doseq [ev events]
                     (let [ev (-> ev
                                  (merge (uri-partial-event req))
                                  (assoc :writers (:identity-set req)))]
                       (publish ev)))
                   (resp {:status 204 :body "No Content"})))
               wrap-authorization))

  (di/provide $ post-query-handler [publish
                                    declare-volatile-service
                                    authenticator
                                    time
                                    uuid]
              (-> (fn [req resp raise]
                    (let [key (uuid)
                          reg (-> (uri-partial-event req)
                                  (assoc :key key))]
                      (declare-volatile-service key reg)
                      (publish (merge reg
                                      {:data (-> req :body slurp edn/read-string)
                                       :ts (time)
                                       :change 1
                                       :readers #{(:identity req)}
                                       :writers #{(:identity req)}}))
                      (resp {:status 303
                             :headers {"Location" (str "/.poll/" key)}})))
                  authenticator))

  (di/provide $ poll-handler [wrap-authorization
                              poll-events
                              version-selector
                              rule-version-verifier]
              (-> (fn [req resp raise]
                    (let [body (->> (poll-events (-> req :route-params :queue))
                                    (filter #(rule-version-verifier (:app-version req) (:writers %)))
                                    (filter #(interset/subset? (:identity-set req) (:readers %)))
                                    (map (comp #(dissoc % :kind)
                                               #(dissoc % :name)
                                               #(dissoc % :key))))]
                      (resp {:status 200
                             :headers {"Content-Type" "application/edn"}
                             :body (-> body
                                       pr-str
                                       .getBytes
                                       clojure.java.io/input-stream)})))
                  version-selector
                  wrap-authorization))
  
  (di/provide $ rule-version-verifier [database-chan]
              (let [cache (atom {})
                    get-perms-for-version
                    (fn [app-ver]
                      (cond (contains? @cache app-ver)
                            (@cache app-ver)
                            :else
                            (let [reply-chan (async/chan 10)]
                              (async/>!! database-chan [{:kind :fact
                                                         :name "axiom/perm-versions"
                                                         :key app-ver}
                                                        reply-chan])
                              (let [{:keys [data]} (async/<!! reply-chan)
                                    [perms static] data]
                                (swap! cache assoc app-ver perms)
                                perms))))]
                (fn [app-ver writers]
                  (any? (for [perm (get-perms-for-version app-ver)]
                          (interset/subset? writers #{(str perm)}))))))

  (di/provide $ event-gateway [rule-version-verifier]
              (fn [[c-c2s c-s2c] identity-set app-version]
                (let [s-c2s (async/chan 10 (filter #(interset/subset? identity-set (:writers %))))
                      s-s2c (async/chan 10 (filter #(and
                                                     (or
                                                      (interset/subset? identity-set (:writers %))
                                                      (rule-version-verifier app-version (:writers %))
                                                      (interset/subset? (:writers %) #{(-> % :name symbol namespace)}))
                                                     (interset/subset? identity-set (:readers %)))))]
                  (async/pipe c-c2s s-c2s)
                  (async/pipe s-s2c c-s2c)
                  [s-c2s s-s2c]))))
