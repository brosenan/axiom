(ns zk-plan.core-test
  (:use midje.sweet)
  (:use [zk-plan.core])
  (:use [zookeeper :as zk])
  (:require [di.core :as di]))

[[:chapter {:title "Introduction"}]]
"`zk-plan` is a tool for orchestrating execution of parallel jobs.
It is based on the notion of a *plan*, which is dependecy graph of Clojure functions to be executed.
After [creating a plan](#create-plan), [populating it](#add-task) and [marking it ready for execution](#mark-as-ready), a cluster of *workers*
start taking tasks from it.
A worker will only take a task for which all dependencies are met.
If a task fails it is automatically retried by another worker.
Eventually, [plan-completed?](#plan-completed) will indicated if all tasks in a given plan have been successfully completed."

[[:chapter {:title "module"}]]
"The content of this library is provided as a [dependency injection](di.html) module.
The `zk-plan` resource containing the external API of this library depends on the `zookeeper` resource
which is a Zookeeper connection object.
`zookeeper` itself is provided by this module, but it depends on `zookeeper-config`, containing the coordinates of the Zookeeper server."
(fact
 (let [$ (di/injector {:zookeeper :zk})] ;; :zk is a mock zookeeper connection object
   (module $)
   (def zk-plan (di/wait-for $ zk-plan))))

"The `zk-plan` resource is a map containing function comprising the external API of this library, already addressing a given connection."
(fact
 (def create-plan (:create-plan zk-plan))
 create-plan => fn?
 (def add-task (:add-task zk-plan))
 add-task => fn?
 (def mark-as-ready (:mark-as-ready zk-plan))
 mark-as-ready => fn?
 (def worker (:worker zk-plan))
 worker => fn?
 (def plan-completed? (:plan-completed? zk-plan))
 plan-completed? => fn?)

[[:chapter {:title "create-plan"}]]
"
**Parameters:**
- **parent:** the parent node for the new plan

**Returns:** the path to the plan
It calls zk/createn to create a new zookeeper node"
(fact
 (create-plan ..parent..) => ..node..
 (provided
  (zk/create :zk ..prefix.. :persistent? true :sequential? true) => ..node..
  (str ..parent.. "/plan-") => ..prefix..))


[[:chapter {:title "add-task"}]]
"
**Parameters:**
- **plan:** the path to the plan
- **fn:** the function to be executed
- **arg-tasks:** a sequence of task paths, which return values are to become arguments for fn

**Returns:** path to the new task"

"It creates a sequential node under the plan"
(fact 
      (add-task ..plan.. ..fn.. []) => ..task..
      (provided
       (zk/create :zk ..prefix.. :persistent? true :sequential? true) => ..task..
       (str ..plan.. "/task-") => ..prefix..
       (set-initial-clj-data irrelevant irrelevant irrelevant) => irrelevant
       (mark-as-ready-internal irrelevant irrelevant) => irrelevant))
"It sets the task node's data to contain a serialization of fn"
(fact
      (add-task ..plan.. ..fn.. []) => ..task..
      (provided
       (zk/create irrelevant irrelevant :persistent? true :sequential? true) => ..task..
       (set-initial-clj-data :zk ..task.. ..fn..) => irrelevant
       (mark-as-ready-internal irrelevant irrelevant) => irrelevant))
"It calls add-dependency for each arg-task"
(fact
      (add-task ..plan.. ..fn.. [..arg1.. ..arg2.. ..arg3..]) => irrelevant
      (provided
       (zk/create irrelevant irrelevant :persistent? true :sequential? true) => ..task..
       (set-initial-clj-data irrelevant irrelevant irrelevant) => irrelevant
       (add-dependency :zk ..arg1.. ..task..) => irrelevant
       (add-dependency :zk ..arg2.. ..task..) => irrelevant
       (add-dependency :zk ..arg3.. ..task..) => irrelevant
       (mark-as-ready-internal irrelevant irrelevant) => irrelevant))
"It adds a 'ready' node once definition is complete"
(fact
      (add-task ..plan.. ..fn.. []) => irrelevant
      (provided
       (zk/create irrelevant irrelevant :persistent? true :sequential? true) => ..task..
       (set-initial-clj-data irrelevant irrelevant irrelevant) => irrelevant
       (mark-as-ready-internal :zk ..task..) => irrelevant))

[[:chapter {:title "mark-as-ready"}]]
"
**Parameters:**
- **task:** the task to be marked as ready

**Returns:** nothing in particular"
"It creates a child node named 'ready'"
(fact
      (mark-as-ready "/foo/bar") => irrelevant
      (provided
       (zk/create :zk "/foo/bar/ready" :persistent? true) => true))

[[:chapter {:title "worker"}]]
"
**Parameters:**
- **parent:** the parent node of all plans
- **attributes:** a map with attributes for the behavior of the worker

**Returns:** nothing in particular"
"It does the following:
- calls `get-task-from-any-plan` to get a task to work on
- if a task is returned (we have something to do), it calls `perform-task` to run it"
(fact
 (worker ..parent.. ..attrs..) => irrelevant
 (provided
  (get-task-from-any-plan :zk ..parent..) => "/foo/bar"
  (perform-task :zk "/foo/bar") => irrelevant
  (zk/exists irrelevant irrelevant) => nil))

"If `get-task-from-any-plan` returns `nil`, we call `calc-sleep-time` to calculate for how long
we need to sleep before the next retry.
We retry until we get a task."
(fact
 (worker ..parent.. ..attrs..) => irrelevant
 (provided
  (get-task-from-any-plan :zk ..parent..) =streams=> [nil nil "/foo/bar"]
  (calc-sleep-time ..attrs.. 0) => 1
  (calc-sleep-time ..attrs.. 1) => 2
  (perform-task irrelevant irrelevant) => irrelevant
  (zk/exists irrelevant irrelevant) => nil))

"If `perform-task` exists (abnomally) before clearing the task node, 
we remove the `owner` node from it to allow another task to complete the job"
(fact
 (worker ..parent.. ..attrs..) => (throws Exception)
 (provided
  (get-task-from-any-plan :zk ..parent..) => "/foo/bar"
  (perform-task :zk "/foo/bar") =throws=> (Exception.)
  (zk/exists :zk "/foo/bar") => {:some "thing"}
  (zk/delete :zk "/foo/bar/owner") => irrelevant))

[[:chapter {:title "plan-completed?"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **plan:** the path to the plan

**Returns:** whether the plan is completed"
"It returns true if the plan has no tasks in it"
(fact
 (plan-completed? ..plan..) => true
 (provided
  (zk/children :zk ..plan..) => '("foo" "bar")))

"It returns false if there is at least one `task-*` child"
(fact
 (plan-completed? ..plan..) => false
 (provided
  (zk/children :zk ..plan..) => '("foo" "task-39893" "bar")))

[[:chapter {:title "Usage Example"}]]
"The idea of this test is to stress zk_plan by launching `N` parallel worker threads to execute a randomized
plan with `M` tasks, each depending on `K` preceding tasks (if such exist)."
(def N 10) ; the number of workers
(def M 100) ; the number of tasks
(def K 10) ; the number of dependencies per task

"The tasks work against a map of `M` atoms, one atom per each task.  These atoms count the workers working on this task."
(def worker-counters (into {} (map (fn [i] [i (atom 0)]) (range M))))

"Each task will also report it completed its work by adding its ordinal number to this set."
(def workers-completed (atom #{}))

"Each task begins by incrementing the atom, then sleeps for a while, then decrements the atom.
After incrementing, it checks that the value is 1, that is, no other worker is working on the same task.
The function compares its arguments against the `expected` vector, and returns its number.
The function below creates a task function (s-expression) for task `i`"
(defn stress-task-func [i expected]
  (fn [& args]
    (println i)
    (let [my-atom (worker-counters i)]
      (try
        (swap! my-atom inc)
        (if (not= @my-atom 1)
          (throw (Exception. (str "Bad counter value: " @my-atom))))
        (if (not= (vec args) (vec expected))
          (throw (Exception. (str "Bad arguments.  Expected: " (vec expected) "  Actual: " args))))
        (Thread/sleep 100)
        (swap! workers-completed #(conj % i))
        (finally 
          (swap! my-atom dec)))
      i)))

"We build the plan.  The first `K` tasks are built without arguments.
The other `M-K` tasks are built with `K` arguments each, which are randomly selected from the range `[0,i)`"
(defn build-stress-plan [{:keys [create-plan
                                 add-task
                                 mark-as-ready]} parent]
  (let [plan (create-plan parent)]
    (loop [tasks {}
           i 0]
      (if (< i M)
        (let [next-task (if (< i K)
                          (add-task plan `(stress-task-func ~i nil) [])
                          ;; else
                          (let [selected (take K (shuffle (range i)))]
                            (add-task plan `(stress-task-func ~i ~(vec selected)) (map tasks selected))))]
          (recur (assoc tasks i next-task) (inc i)))))
    (mark-as-ready plan)
    plan))

"We now deploy `N` workers to execute the plan.
Each thread runs the `worker` function repeatedly.
In case of an exception thrown from the worker, we report it, but move on to call `worker` again."
(defn start-stress-workers [{:keys [worker]} parent]
  (let [threads (map (fn [_] (Thread. (fn []
                                        (loop []
                                          (try
                                            (worker parent {})
                                            (catch Exception e
                                              (.printStackTrace e)))
                                          (recur))))) (range N))]
    (doseq [thread threads]
      (.start thread))
    threads))

"To stop all threads we simply `.join` them"
(defn join-stress-workers [threads]
  (doseq [thread threads]
    (.stop thread)))

"Puttint this all together:
- Connect to an actual Zookeeper
- Clear the parent: `/stress` if exists
- (Re) Create the parent
- Start the workers
- Create the plan
- Wait until the plan is complete
- Stop the workers"
(fact
 :integ ; This is an integration test
 (let [$ (di/injector {:zookeeper-config {:url "127.0.0.1:2181"}})]
   (module $)
   (let [zk (di/wait-for $ zookeeper)
         zk-plan (di/wait-for $ zk-plan)
         {:keys [plan-completed?]} zk-plan
         parent "/stress"]
     (zk/delete-all zk "/stress")
     (zk/create zk parent :persistent? true)
     (let [threads (start-stress-workers zk-plan parent)
           plan (build-stress-plan zk-plan parent)]
       (loop []
         (when-not (plan-completed? plan)
           (Thread/sleep 100)
           (recur)))
       (doseq [m (range M)]
         (when-not (contains? @workers-completed m)
           (println "Task " m " was not completed")))
       (join-stress-workers threads)))))
         

[[:chapter {:title "Under the Hood"}]]
[[:section {:title "get-task"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **plan:** the path to the plan

**Returns:** path to the task"
"It returns nil if the plan is empty"
(fact
 (get-task ..zk.. ..plan..) => nil
 (provided
  (zk/children ..zk.. ..plan..) => nil))
"It returns a task if it does not have `dep-*` or owner as children"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-1234"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/children ..zk.. "/foo/task-1234") => '("task-2345" "ready" "quux")
  (take-ownership ..zk.. "/foo/task-1234") => true))
"It does not return tasks that have `dep-*` children"
(fact
 (get-task ..zk.. "/foo") => nil
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/children ..zk.. "/foo/task-1234") => '("task-2345" "ready" "quux" "dep-0001")))
"It does not return tasks that have owner nodes"
(fact
 (get-task ..zk.. "/foo") => nil
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/children ..zk.. "/foo/task-1234") => '("task-2345" "quux" "ready" "owner")))
"It does not take tasks that are not marked ready"
(fact
 (get-task ..zk.. "/foo") => nil
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/children ..zk.. "/foo/task-1234") => '("task-2345" "quux")))
"It takes ownership over the task by adding an 'owner' node"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-1234"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/children ..zk.. "/foo/task-1234") => '("ready")
  (take-ownership ..zk.. "/foo/task-1234") => true))
"It moves to the next one if it is unable to take ownership"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-2345"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345")
  (zk/children ..zk.. "/foo/task-1234") => '("ready")
  (take-ownership ..zk.. "/foo/task-1234") => false
  (zk/children ..zk.. "/foo/task-2345") => '("ready")
  (take-ownership ..zk.. "/foo/task-2345") => true))

"It looks up children lazily"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-1234"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345" "bat")
  (zk/children ..zk.. irrelevant) => '("ready") :times 1
  (take-ownership ..zk.. "/foo/task-1234") => true))

"In case a task is removed before we got the chance to examine it, we move to the next task"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-2345"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345")
  (zk/children ..zk.. "/foo/task-1234") => false
  (zk/children ..zk.. "/foo/task-2345") => '("ready")
  (take-ownership ..zk.. "/foo/task-2345") => true))

"If it comes across an empty task, it removes it and moves on"
(fact
 (get-task ..zk.. "/foo") => "/foo/task-2345"
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345")
  (zk/children ..zk.. "/foo/task-1234") => nil
  (zk/delete ..zk.. "/foo/task-1234") => irrelevant
  (zk/children ..zk.. "/foo/task-2345") => '("ready")
  (take-ownership ..zk.. "/foo/task-2345") => true))

"It ignores children of the plan which are not of the form `task-*`"
(fact
 (get-task ..zk.. "/foo") => nil
 (provided
  (zk/children ..zk.. "/foo") => '("bar" "baz" "ready")))

[[:section {:title "perform-task"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **task:** path to the task to perform

**Returns:** Nothing in particular"

"If the task has a 'result' child and no 'prov-*' children, this means the task completed
successfully, and the result has been distributed to all dependent tasks (if any).
In such a case we remove the task."
(fact
 (perform-task ..zk.. "/foo/task-1234") => irrelevant
 (provided
  (zk/children ..zk.. "/foo/task-1234") => '("result")
  (get-clj-data irrelevant irrelevant) => 123
  (zk/delete-all ..zk.. "/foo/task-1234") => irrelevant))

"If `prov-*` children exist, it reads the result and distributes it across the tasks
depending on this task (the corresponding dep-* nodes)"
(fact
 (perform-task ..zk.. "/foo/task-1234") => irrelevant
 (provided
  (zk/children ..zk.. "/foo/task-1234") => '("result" "prov-00000" "prov-0001")
  (get-clj-data ..zk.. "/foo/task-1234/result") => 3.1415
  (propagate-result ..zk.. "/foo/task-1234/prov-00000" 3.1415) => irrelevant
  (propagate-result ..zk.. "/foo/task-1234/prov-0001" 3.1415) => irrelevant
  (zk/delete-all irrelevant irrelevant) => irrelevant))

"If the task does not have a result, we need to calculate the result ourselves.
We call execute-function to get the result, and store it as the 'result' child."
(fact
 (perform-task ..zk.. "/foo/task-1234") => irrelevant
 (provided
  (zk/children ..zk.. "/foo/task-1234") => '()
  (execute-function ..zk.. "/foo/task-1234") => 1234.5
                                        ; It should create a result child node and store the result to it
  (zk/create ..zk.. "/foo/task-1234/result" :persistent? true) => true
  (set-initial-clj-data ..zk.. "/foo/task-1234/result" 1234.5) => irrelevant
  (zk/delete-all irrelevant irrelevant) => irrelevant))

[[:section {:title "set-initial-clj-data"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **node:** the node 

**Returns:** Nothing in particular"

"It calls zk/set-data to update the data"
(fact
      (set-initial-clj-data ..zk.. ..node.. ..data..) => irrelevant
      (provided
       (pr-str ..data..) => ..str..
       (to-bytes ..str..) => ..bytes..
       (zk/set-data ..zk.. ..node.. ..bytes.. irrelevant) => irrelevant))
"It derives the version number from the existing version"
(fact
      (set-initial-clj-data ..zk.. ..node.. ..data..) => irrelevant
      (provided
       (zk/set-data irrelevant irrelevant irrelevant 0) => irrelevant))


[[:section {:title "add-dependency"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **from:** path of the task that provides the dependency
- **to:** path of the task that depends on 'from' 

**Returns:** nothing in particular"

"It adds sequential children to both the 'from' and the 'to' tasks"
(fact
      (add-dependency ..zk.. "/path/from" "/path/to") => irrelevant
      (provided
       (zk/create ..zk.. "/path/from/prov-" :persistent? true :sequential? true) => irrelevant
       (zk/create ..zk.. "/path/to/dep-" :persistent? true :sequential? true) => irrelevant
       (set-initial-clj-data irrelevant irrelevant irrelevant) => irrelevant))
"It sets the data of the prov child to be the path to the corresponding dep child"
(fact 
      (add-dependency ..zk.. "/path/from" "/path/to") => irrelevant
      (provided
       (zk/create ..zk.. "/path/from/prov-" :persistent? true :sequential? true) => ..from-link..
       (zk/create ..zk.. "/path/to/dep-" :persistent? true :sequential? true) => ..to-link..
       (set-initial-clj-data ..zk.. ..from-link.. ..to-link..) => irrelevant))

[[:section {:title "take-ownership"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **task:** the task to take ownership over

**Returns:** whether or not we managed to take ownership"

"It tries to add an ephemeral 'owner' node to the task, and return whether it was successful"
(fact
      (take-ownership ..zk.. "/foo/task-1234") => ..result..
      (provided
       (zk/create ..zk.. "/foo/task-1234/owner" :persistent? false) => ..result..))



[[:section {:title "execute-function"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **task:** path to the task node containing arguments for the function  

**Returns:** the return value from the task's function"
"It reads the function definition from the content of the task node.
If no parameters exist in the task it executes the function without parameters."
(fact
 (defn returns-3 [] 3)
 (execute-function ..zk.. ..task..) => 3
 (provided
  (get-clj-data ..zk.. ..task..) => 'returns-3
  (zk/children ..zk.. ..task..) => '("foo" "task-1234")))
"It passes the task arguments to the function"
(fact
 (execute-function ..zk.. "/foo/task-1234") => [1 2 3]
 (provided
  (get-clj-data ..zk.. "/foo/task-1234") => '(fn [& args] args)
  (zk/children ..zk.. "/foo/task-1234") => '("arg-00001" "arg-00002" "arg-00000")
  (get-clj-data ..zk.. "/foo/task-1234/arg-00000") => 1
  (get-clj-data ..zk.. "/foo/task-1234/arg-00001") => 2
  (get-clj-data ..zk.. "/foo/task-1234/arg-00002") => 3))

[[:section {:title "propagate-result"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **prov:** path to the `prov-*` node to propagate
- **value:** the value to be propagated

**Returns:** nothing in particular"
"It does the following:
- reads the path of the `dep-*` node from the `prov-*` node
- create an `arg-*` node at the same task and with the same serial number as the `dep-*` node
- set the value of the `arg-*` node to be `value`
- remove the `dep-*` node"
(fact
 (propagate-result ..zk.. ..prov.. ..value..) => irrelevant
 (provided
  (get-clj-data ..zk.. ..prov..) => "/foo/task-1234/dep-01472"
  (zk/create ..zk.. "/foo/task-1234/arg-01472" :persistent? true) => true
  (set-initial-clj-data ..zk.. "/foo/task-1234/arg-01472" ..value..) => irrelevant
  (zk/delete ..zk.. "/foo/task-1234/dep-01472") => irrelevant))

[[:section {:title "get-task-from-any-plan"}]]
"
**Parameters:**
- **zk:** the Zookeeper connection object
- **parent:** path to the parent of all plans

**Returns:** path to a task, if one is found, or nil if not"
"It starts by getting the list of children (plans).  If this list is empty, it returns nil"
(fact
 (get-task-from-any-plan ..zk.. ..parent..) => nil
 (provided
  (zk/children ..zk.. ..parent..) => nil))

"If a plan exists, we check that it is ready and then call `get-task` on it"
(fact
 (get-task-from-any-plan ..zk.. "/foo") => ..task..
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234")
  (zk/exists ..zk.. "/foo/task-1234/ready") => {:some-key "value"}
  (get-task ..zk.. "/foo/task-1234") => ..task..))

"If a plan is not ready, it should be skipped"
(fact
 (get-task-from-any-plan ..zk.. "/foo") => ..task..
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345")
  (zk/exists ..zk.. "/foo/task-1234/ready") => nil
  (zk/exists ..zk.. "/foo/task-2345/ready") => {:some-key "value"}
  (get-task ..zk.. "/foo/task-2345") => ..task..))

"If a `get-task` does not return a task (e.g., no ready tasks), we move on to the next plan.
This should be done lazily, so that additional plans must not be queried."
(fact
 (get-task-from-any-plan ..zk.. "/foo") => ..task..
 (provided
  (zk/children ..zk.. "/foo") => '("task-1234" "task-2345" "quux")
  (zk/exists ..zk.. "/foo/task-1234/ready") => {:some-key "value"}
  (get-task ..zk.. "/foo/task-1234") => nil
  (zk/exists ..zk.. "/foo/task-2345/ready") => {:some-key "value"}
  (get-task ..zk.. "/foo/task-2345") => ..task..))

[[:section {:title "calc-sleep-time"}]]
"
**Parameters:**
- **attrs:** a map of attributes based on which we calculate the sleep time, including:
  - `:initial` - the value to be returned for `count` 0 (default: 100ms)
  - `:increase` - the increase factor, by which the value gets multiplied each time (default: 1.5)
  - `:max` - the maximum sleep time (default: 10 seconds)
- **count:** the number of times we already had to wait before getting the last task

**Returns:** the number of milliseconds to sleep"
"For `count` = 0, returns the `:initial`"
(fact
 (calc-sleep-time {:initial 1234} 0) => 1234)

"`:initial` defaults to 100"
(fact
 (calc-sleep-time {} 0) => 100)

"For `count` > 0, the `:initial` value is multiplied by `:increase` to the power of `count`"
(fact
 (calc-sleep-time {:increase 2 :initial 1} 8) => 256)

"`:increase` defaults to 1.5"
(fact
 (calc-sleep-time {} 1) => 150)

"The value is capped by `:max`"
(fact
 (calc-sleep-time {:max 300} 20) => 300)

"`:max` defaults to 10000"
(fact
 (calc-sleep-time {} 20) => 10000)

"`:max` is applied at any step, such that the function does not overflow even with high `count` values"
(fact
 (calc-sleep-time {:max 1000} 100) => 1000)
