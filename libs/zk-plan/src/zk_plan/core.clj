(ns zk-plan.core
  (:use [zookeeper :as zk])
  (:require [clojure.string :as str]
            [di.core :as di]
            [clojure.core.async :as async]))

(defn ^:private create-plan [zk parent]
  (zk/create-all zk (str parent "/plan-") :persistent? true :sequential? true))

(defn to-bytes [str]
  (.getBytes str "UTF-8"))

(defn set-initial-clj-data [zk node data]
  (let [str (pr-str data)
        bytes (to-bytes str)]
    (zk/set-data zk node bytes 0)))

(defn add-dependency [zk from to]
  (let [prov (zk/create zk (str from "/prov-") :persistent? true :sequential? true)
        dep (zk/create zk (str to "/dep-") :persistent? true :sequential? true)]
    (set-initial-clj-data zk prov dep)))

(defn  mark-as-ready-internal [zk task]
  (zk/create zk (str task "/ready") :persistent? true))

(defn ^:private add-task [zk plan fn arg-tasks]
  (let [task (zk/create zk (str plan "/task-") :persistent? true :sequential? true)]
    (set-initial-clj-data zk task fn)
    (doseq [arg arg-tasks]
      (add-dependency zk arg task))
    (mark-as-ready-internal zk task)
    task))

(defn take-ownership [zk task]
  (zk/create zk (str task "/owner") :persistent? false))

(defn get-task [zk plan]
  (let [tasks (->> (zk/children zk plan)
                   (filter #(re-matches #"task-\d+" %))
                   (map #(str plan "/" %)))]
    (loop [tasks tasks]
            (if (empty? tasks)
              nil
              ;; else
              (let [task (first tasks)
                    task-props (zk/children zk task)]
                (if (= task-props nil)
                  (do (zk/delete zk task)
                      (recur (rest tasks)))
                  ;; else
                  (if (and task-props
                           (not (some #(or (re-matches #"dep-\d+" %)
                                           (= "owner" %)) task-props))
                           (contains? (set task-props) "ready")
                           (take-ownership zk task))
                    task
                    ;; else
                    (recur (rest tasks)))))))))


(defn get-clj-data [zk node]
  (let [data (:data (zk/data zk node))]
    (read-string (String. data "UTF-8"))))

(defn execute-function [zk node $]
  (di/do-with! $ [info]
               (let [func (get-clj-data zk node)
                     _ (info {:source "zk-plan"
                              :desc (str "executing function: " func)})
                     func' (eval func)
                     children (zk/children zk node)]
                 (cond children
                       (let [vals (->> children
                                       (filter #(re-matches #"arg-\d+" %))
                                       sort
                                       (map #(str node "/" %))
                                       (map #(get-clj-data zk %)))
                             res (apply func' $ vals)]
                         res)
                       :else
                       :task-does-not-exist))))

(defn propagate-result [zk prov value]
  (let [dep (get-clj-data zk prov)
        arg (str/replace dep #"(.*)/dep-(\d+)" "$1/arg-$2")]
    (zk/create zk arg :persistent? true)
    (set-initial-clj-data zk arg value)
    (zk/delete zk dep)))

(defn prov? [key]
  (re-matches #"prov-\d+" key))

(defmacro retry [times & exprs]
  (cond (> times 1)
        `(try
           ~@exprs
           (catch Throwable e#
             (retry ~(dec times)
                    ~@exprs)))
        :else
        `(do
           ~@exprs)))

(defn perform-task [zk task $]
  (let [children (zk/children zk task)]
    (when children
      (let [result-node (str task "/result")
            res (if (contains? (set children) "result")
                  (get-clj-data zk result-node)
                  ;; else
                  (execute-function zk task $))]
        (when-not (= res :task-does-not-exist)
          (when-not (contains? (set children) "result")
            (zk/create zk result-node :persistent? true)
            (set-initial-clj-data zk result-node res))
          (doseq [prov (filter prov? children)]
            (propagate-result zk (str task "/" prov) res))
          (retry 3 (zk/delete-all zk task)))))))

(defn get-task-from-any-plan [zk parent]
  (let [plans (zk/children zk parent)]
    (when plans
      (loop [plans (map (partial str parent "/") plans)]
        (when-not (empty? plans)
          (let [plan (first plans)]
            (let [task (and (zk/exists zk (str plan "/ready"))
                            (get-task zk plan))]
              (cond task
                    task
                    :else
                    (recur (rest plans))))))))))

(defn calc-sleep-time [attrs count]
  (let [max (:max attrs 10000)]
    (loop [val (:initial attrs 100)
           i 0]
      (if (> val max)
        max
        ;; else
        (if (< i count)
          (recur (* val (:increase attrs 1.5)) (inc i))
          ;; else
          (int val))))))

(defn ^:private worker [zk parent attrs alive $]
  (loop [count 0]
    (let [task (get-task-from-any-plan zk parent)]
      (if task
        (try
          (perform-task zk task $)
          (finally
            (when (zk/exists zk task)
              (zk/delete zk (str task "/owner")))))
        ;; else
        (when @alive
          (Thread/sleep (calc-sleep-time attrs count))
          (recur (inc count)))))))


(defn  ^:private plan-completed? [zk plan]
  (not (some #(re-matches #"task-\d+" %)
             (zk/children zk plan))))

(defn module [$]
  (di/provide $ zookeeper [zookeeper-config]
              (zk/connect (:url zookeeper-config)))

  (di/provide $ zk-plan [zookeeper]
              {:create-plan (partial create-plan zookeeper)
               :add-task (partial add-task zookeeper)
               :mark-as-ready (partial mark-as-ready-internal zookeeper)
               :worker (partial worker zookeeper)
               :plan-completed? (partial plan-completed? zookeeper)})

  (di/do-with $ [zk-plan zk-plan-config info]
              (let [alive (atom true)
                    {:keys [worker]} zk-plan
                    threads (map (fn [_] (Thread. (fn []
                                                    (loop []
                                                      (when @alive
                                                        (try
                                                          (worker (:parent zk-plan-config) {} alive $)
                                                          (catch Exception e
                                                            (.printStackTrace e)))
                                                        (recur)))))) (range (:num-threads zk-plan-config)))]
                (doseq [thread threads]
                  (.start thread))
                {:resource threads
                 :shutdown (fn []
                             (reset! alive false)
                             (info {:source "zk-plan"
                                    :desc "Waiting for threads to shut down..."})
                             (doseq [thread threads]
                               (.join thread)))})))
