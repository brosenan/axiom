(ns axiom-cljs.core
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [cljs.core.async :as async]
            [clojure.string :as str]
            [chord.client :refer [ws-ch]]
            [cemerick.url :refer (url url-encode)]))

(defn pubsub [f]
  (let [listeners (atom {})]
    {:pub (fn [val]
            (doseq [listener (@listeners (f val))]
              (listener val)))
     :sub (fn [disp f]
            (swap! listeners update disp conj f))}))

(defn connection [url & {:keys [ws-ch atom]
                         :or {ws-ch ws-ch
                              atom atom}}]
  (let [to-host (async/chan 2)
        ps (pubsub :name)
        identity (atom nil)
        status (atom :ok)]
    (go
      (let [ch (-> (ws-ch url)
                   async/<!
                   :ws-channel)]
        (async/pipe to-host ch)
        (go-loop []
          (let [ev (:message (async/<! ch))]
            (cond (nil? ev)
                  (reset! status :err)
                  :else
                  (do
                    (prn [:rcv ev])
                    (when (= (:kind ev) :init)
                      (reset! identity (:identity ev)))
                    ((:pub ps) ev)
                    (recur)))))))
    {:pub (fn [ev] (go
                     (prn [:snd ev])
                     (async/>! to-host ev)))
     :sub (:sub ps)
     :time (fn [] (.getTime (js/Date.)))
     :uuid #(str (random-uuid))
     :identity identity
     :status status}))

(defn merge-meta [obj m]
  (with-meta obj (merge (meta obj) m)))

(defn ws-url [loc]
  (let [host (.-host loc)
        host (cond (= host "localhost:3449") "localhost:8080"
                   :else host)
        query (cond (nil? (.-hash loc))
                    ""
                    (= (count (str/split (.-hash loc) #"[?]")) 2)
                    (str "?" (second (str/split (.-hash loc) #"[?]")))
                    :else "")]
    (str "ws://" host "/ws" query)))

(defn update-on-dev-ver [host]
  ((:sub host) "axiom/perm-versions"
   (fn [ev]
     (when (and (> (:change ev) 0)
                (str/starts-with? (:key ev) "dev-"))
       (.set goog.net.cookies "app-version" (:key ev)))))
  ((:pub host) {:kind :reg
                :name "axiom/perm-versions"})
  host)

(defn wrap-feed-forward [host]
  (let [{:keys [pub sub]} host
        ps (pubsub :name)
        project #(dissoc % :readers)]
    (-> host
        (assoc :pub (fn [ev]
                      ((:pub ps) ev)
                      (pub ev)))
        (assoc :sub (fn [disp f]
                      ;; Limbo stores events that were feed-forwarded internally
                      ;; but were not yet received from the server.
                      ;; It is local to a subscriber
                      (let [limbo (atom #{})]
                        ;; Called upon publication by the client
                        (let [f' (fn [ev]
                                   (swap! limbo conj (project ev))
                                   (f ev))]
                          ((:sub ps) disp f'))
                        ;; Called upon receiving event from the server
                        (let [f' (fn [ev]
                                  (let [pev (project ev)]
                                    (cond (contains? @limbo pev)
                                          (swap! limbo disj pev)
                                          :else
                                          (f ev))))]
                          (sub disp f'))))))))

(defn wrap-atomic-updates [host]
  (let [sub (:sub host)]
    (-> host
        (assoc :sub
               (fn [disp f]
                 (sub disp (fn [ev]
                             (cond (contains? ev :removed)
                                   (do
                                     (f (-> ev
                                            (assoc :data (:removed ev))
                                            (assoc :change (- (:change ev)))
                                            (dissoc :removed)))
                                     (f (-> ev
                                            (dissoc :removed))))
                                   :else
                                   (f ev)))))))))

(defn wrap-reg [host]
  (let [{:keys [pub]} host
        regs (atom #{})]
    (-> host
        (assoc :pub
               (fn [ev]
                 (cond (= (:kind ev) :reg)
                       (when-not (contains? @regs ev)
                         (pub ev)
                         (swap! regs conj ev))
                       :else
                       (pub ev)))))))

(defn wrap-late-subs [host]
  (let [{:keys [sub]} host
        events (atom {})]
    (-> host
        (assoc :sub (fn [disp f]
                          (sub disp (fn [ev]
                                      (f ev)
                                      (swap! events update (:name ev) (fnil conj []) ev)))
                          (doseq [ev (@events disp)]
                            (f ev)))))))

(defn default-connection [atom]
  (-> js/document.location
      ws-url
      (connection :atom atom)
      update-on-dev-ver
      wrap-reg
      wrap-late-subs
      wrap-feed-forward
      wrap-atomic-updates))
