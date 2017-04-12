# zk-plan: A Framework for Creating Distributed Workflows
[![Build Status](https://travis-ci.org/brosenan/zk-plan.svg?branch=master)](https://travis-ci.org/brosenan/zk-plan)

## What it Is
**zk-plan** is a Clojure framework for creating distributed applications, based on [Zookeeper](https://zookeeper.apache.org/).
It is based on the notion of a *plan*, which is a directed acyclic graph (DAG) of *tasks*.  Tasks are made of Clojure functions, which may depend on other tasks.  The return-value of each function is provided as argument to the functions running in tasks that depend on them.  **zk-plan** guarantees order of execution and completion by providing tasks to *workers*.  Workers repeatedly search Zookeeper for tasks that are ready to be performed and have all their dependencies resolved, and perform them. Workers mark tasks they work on using [ephemeral nodes](https://zookeeper.apache.org/doc/r3.2.1/zookeeperProgrammers.html#Ephemeral+Nodes). If a worker crashes before it could complete the task, the ephemeral node marking its ownership over the task is removed by Zookeeper, and another worker can pick up that task. As long as there are workers working, the work will be done.

## Status
**zk-plan** is in its very early stages of development.

## Documentation
Please refer to [our documentation website](https://brosenan.github.io/zk-plan/).

## How to run the tests

`lein midje` will run all tests.

`lein midje namespace.*` will run only tests beginning with "namespace.".

`lein midje :autotest` will run all the tests indefinitely. It sets up a
watcher on the code files. If they change, only the relevant tests will be
run again.
