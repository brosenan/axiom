(ns di.core
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [clojure.set :as set]))

(defn log-with-sev [res sev]
  (fn [event]
    (let [{:keys [log]} res]
      (log (assoc event :severity sev)))))

(def default-rules
  [(with-meta (fn [res]
                (fn [] (.getTime (java.util.Date.))))
     {:resource :time
      :deps []})
   (with-meta (fn [res] println)
     {:resource :println
      :deps []})
   (with-meta (fn [res]
                (fn [time]
                  (let [fmt (java.text.SimpleDateFormat. "MMM dd YYYY HH:mm:ss:SSS zzz")]
                    (.format fmt time))))
     {:resource :format-time
      :deps []})
   (with-meta (fn [res] (fn [event]
                          (let [{:keys [time format-time println]} res
                                [fmt args] (:format event ["[%s] [%s] %s" [:severity :source :desc]])]
                            (println (format-time (time)) (apply format fmt (map event args))))))
     {:resource :log
      :deps [:time :format-time :println]})
   (with-meta (fn [res] (log-with-sev res "EE"))
     {:resource :err
      :deps [:log]})
   (with-meta (fn [res] (log-with-sev res "WW"))
     {:resource :warn
      :deps [:log]})
   (with-meta (fn [res] (log-with-sev res "II"))
     {:resource :info
      :deps [:log]})])

(defn injector
  ([initial]
   (atom
    {:resources initial
     :rules default-rules
     :shutdown []}))
  ([]
   (injector {})))


(defmacro provide [$ resource deps & exprs]
  `(swap! ~$ (fn [~'$inj] (update-in ~'$inj [:rules] (fn [~'$rules]
                                                       (conj ~'$rules
                                                              (with-meta
                                                                (fn [{:keys ~deps}] ~@exprs)
                                                                {:resource ~(keyword resource)
                                                                 :deps ~(vec (map keyword deps))})))))))

(defmacro do-with [$ deps & exprs]
  `(swap! ~$ (fn [~'$inj] (update-in ~'$inj [:rules] (fn [~'$rules]
                                                       (conj ~'$rules
                                                             (with-meta
                                                               (fn [{:keys ~deps}] ~@exprs)
                                                               {:deps ~(vec (map keyword deps))})))))))

(defn rule-edges [func]
  (let [deps (-> func meta :deps)
        deps-edges (for [dep deps]
                     [dep func])
        resource (-> func meta :resource)]
    (cond resource
          (conj deps-edges [func (keyword resource)])
          :else
          deps-edges)))

(defn startup [$]
  (let [edges (mapcat rule-edges (:rules @$))
        g (apply graph/digraph edges)
        ordered (alg/topsort g)
        funcs (filter fn? ordered)
        keys-to-skip (set (keys (:resources @$)))]
    (loop [funcs funcs
           resources (:resources @$)
           shutdown-seq []]
      (cond (empty? funcs)
            (swap! $ #(-> %
                          (assoc :resources resources)
                          (assoc :shutdown shutdown-seq)))
            :else
            (let [func (first funcs)
                  res (-> func meta :resource)
                  deps (-> func meta :deps)]
              (cond (and (every? (partial contains? resources) deps)
                         (not (contains? keys-to-skip res)))
                    (let [val (func resources)
                          [val shutdown-seq] (cond (and (map? val)
                                                         (:resource val)
                                                         (:shutdown val)
                                                         (= (count val) 2))
                                                    [(:resource val) (cons (:shutdown val) shutdown-seq)]
                                                    :else
                                                    [val shutdown-seq])]
                      (recur (rest funcs) (assoc resources res val) shutdown-seq))
                    :else
                    (recur (rest funcs) resources shutdown-seq)))))
    nil))

(defn shutdown [$]
  (doseq [func (:shutdown @$)]
    (func)))

(defmacro do-with! [$ deps & exprs]
  `(let [~'$existing (set (keys (:resources @~$)))
         ~'$missing (set/difference ~(set (map keyword deps))
                                    ~'$existing)]
     (cond (empty? ~'$missing)
           (let [{:keys ~deps} (:resources @~$)]
             ~@exprs)
           :else
           (throw (Exception. (str "Resource(s) " ~'$missing " are not available, but " ~'$existing " are"))))))
