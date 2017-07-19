(ns gateway.core
  (:require [di.core :as di]
            [clojure.core.async :as async]
            [cloudlog-events.core :as ev]
            [cloudlog.interset :as interset]
            [ring.middleware.content-type :as ctype]
            [ring.middleware.params :as params]
            [ring.middleware.cookies :as cookies]
            [ring.util.codec :as codec]
            [clojure.edn :as edn]
            [compojure.core :refer :all]
            [chord.http-kit :as chord]
            [org.httpkit.server :as htsrv]
            [clojure.walk :as walk]))


(defn any? [list]
  (some? (some identity list)))

(defn wrap-websocket-handler [handler]
  (fn [req]
    (if (:websocket? req)
      (chord/with-channel req ws-conn
        (let [chan-from-ws (async/chan 10 (map :message))
              chan-to-ws (async/chan 10)]
          (async/pipe ws-conn chan-from-ws)
          (async/pipe chan-to-ws ws-conn)
          (handler (assoc req :ws-channel-pair [chan-from-ws chan-to-ws]))))
      (handler req))))

(defn cookie-version-selector [handler]
  (fn [req]
    (handler (assoc req :app-version (get-in req [:cookies "app-version" :value])))))

(defn uri-partial-event [req]
  (let [{:keys [ns name key]} (:route-params req)]
    {:kind :fact
     :name (str (symbol ns name))
     :key (cond key
                (-> key codec/url-decode edn/read-string))}))

(defn translate-name [n trans]
  (let [sym (symbol n)
        ns (namespace sym)
        ns-sym (symbol ns)
        ns (cond (contains? trans ns-sym)
                 (str (trans ns-sym))
                 :else ns)]
    (str (symbol ns (name sym)))))

(defn translate-set [s trans]
  (let [name-trans
        (into {} (for [g (interset/enum-groups s)
                       :when (vector? g)]
                   [(first g) (keyword (translate-name (-> g first str (subs 1)) trans))]))]
    (walk/postwalk-replace name-trans s)))

(defn translate-names [ev trans]
  (-> ev
      (assoc :name (translate-name (:name ev) trans))
      (assoc :writers (translate-set (:writers ev) trans))
      (assoc :readers (translate-set (:readers ev) trans))))

(defn get-translation-for-version [database-chan ver]
  (let [reply-chan (async/chan 2)]
    (async/>!! database-chan [{:kind :fact
                               :name "axiom/perm-versions"
                               :key ver} reply-chan])
    (let [{:keys [data]} (async/<!! reply-chan)
          [trans] data]
      trans)))

(defn module [$]
  (di/provide $ authenticator [use-dummy-authenticator]
              (fn [handler]
                (fn[req]
                  (let [id (or (get-in req [:params "_identity"])
                               (get-in req [:cookies "user_identity" :value]))]
                    (cond id
                          (-> (handler (-> req
                                           (assoc :identity id)))
                              (update-in [:cookies] #(assoc % "user_identity" {:value id})))
                          :else
                          (handler req))))))

  (di/provide $ version-selector [dummy-version]
              (fn [handler]
                (let [handler (cookie-version-selector handler)]
                  (fn [req]
                    (let [req (cond (contains? (:cookies req) "app-version")
                                    req
                                    :else
                                    (assoc-in req [:cookies "app-version" :value] dummy-version))]
                      (handler req))))))
  
  (di/provide $ static-handler [version-selector
                                database-chan
                                hasher]
              (-> (fn [req]
                    (let [resp-chan (async/chan)
                          [_ unhash] hasher]
                      (async/>!! database-chan [{:kind :fact
                                                 :name "axiom/perm-versions"
                                                 :key (:app-version req)} resp-chan])
                      (let [[ans] (->> resp-chan
                                       (async/reduce conj [])
                                       (async/<!!))
                            [perms static] (:data ans)
                            hashcode (static (:uri req))]
                        (cond (nil? hashcode)
                              {:status 404
                               :body "Not Found!"}
                              :else
                              (let [content (unhash hashcode)]
                                {:status 200
                                 :body (clojure.java.io/input-stream content)})))))
                  ctype/wrap-content-type
                  version-selector))

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
                                    [perm-map static] data
                                    perms (-> perm-map vals set)]
                                (swap! cache assoc app-ver perms)
                                perms))))]
                (fn [app-ver writers]
                  (any? (for [perm (get-perms-for-version app-ver)]
                          (interset/subset? writers #{(str perm)}))))))

  (di/provide $ event-gateway [rule-version-verifier
                               identity-pred]
              (fn [[c-c2s c-s2c] identity app-version]
                (let [user-in-set? (identity-pred identity)
                      s-c2s (async/chan 10 (filter #(or
                                                     (and
                                                      (= (:kind %) :fact)
                                                      (user-in-set? (:writers %)))
                                                     (and
                                                      (= (:kind %) :reg)
                                                      (contains? % :name)
                                                      (contains? % :key)))))
                      s-s2c (async/chan 10 (filter #(and
                                                     (or
                                                      (user-in-set? (:writers %))
                                                      (rule-version-verifier app-version (:writers %))
                                                      (interset/subset? (:writers %) #{(-> % :name symbol namespace)}))
                                                     (user-in-set? (:readers %)))))]
                  (async/pipe c-c2s s-c2s)
                  (async/pipe s-s2c c-s2c)
                  [s-c2s s-s2c])))

  (di/provide $ websocket-handler [event-gateway
                                   event-bridge
                                   wrap-websocket-handler
                                   version-selector]
              (-> (fn [{:keys [ws-channel-pair
                               identity-set
                               app-version]}]
                    (-> ws-channel-pair
                        (event-gateway identity-set app-version)
                        event-bridge))
                  wrap-websocket-handler
                  version-selector))

  (di/provide $ ring-handler [static-handler
                              websocket-handler]
              (routes
               (GET "/ws" [] websocket-handler)
               static-handler))

  (di/provide $ http-server [http-config
                             ring-handler]
              (let [srv
                    (htsrv/run-server (-> ring-handler
                                          params/wrap-params
                                          cookies/wrap-cookies) http-config)]
                {:resource (meta srv)
                 :shutdown srv}))

  (di/provide $ identity-pred [database-chan]
              (let [database-chan (ev/accumulate-db-chan database-chan)]
                (fn [id]
                  (let [id-set (atom #{id})
                        rules (atom #{})
                        xform (comp
                               (filter (fn [ev] (interset/subset? (:writers ev) #{(-> ev
                                                                                      :name
                                                                                      keyword
                                                                                      namespace)})))
                               (filter (fn [ev] (interset/subset? #{id} (:readers ev)))))]
                    (fn [s]
                      (cond (nil? id)
                            false
                            :else
                            (do
                              (doseq [g (interset/enum-groups s)]
                                (when (and (vector? g)
                                           (not (contains? @rules (first g))))
                                  (let [reply-chan (async/chan 2 xform)]
                                    (async/>!! database-chan [{:kind :fact
                                                               :name (-> g first str (subs 1))
                                                               :key id} reply-chan])
                                    (loop []
                                      (let [ev (async/<!! reply-chan)]
                                        (when ev
                                          (swap! id-set conj (vec (cons (first g) (:data ev))))
                                          (recur)))))
                                  (swap! rules conj (first g))))
                              (interset/subset? @id-set s))))))))

  (di/provide $ name-translator [database-chan]
              (fn [[c-c2s c-s2c] ver]
                (let [trans (get-translation-for-version database-chan ver)
                      inv-trans (into {} (for [[k v] trans]
                                           [v k]))
                      s-c2s (async/chan 2 (map #(translate-names % trans)))
                      s-s2c (async/chan 2 (map #(translate-names % inv-trans)))]
                  (async/pipe c-c2s s-c2s)
                  (async/pipe s-s2c c-s2c)
                  [s-c2s s-s2c]))))
