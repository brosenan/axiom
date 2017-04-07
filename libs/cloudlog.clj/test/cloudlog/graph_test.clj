(ns cloudlog.graph_test
  (:use midje.sweet)
  (:use [cloudlog.graph]))

[[:chapter {:title "Introduction"}]]
"This is a small graph library intended to provide topological sort."

"In this library, graphs are represented as maps from edges to sets of target edges.
For example, the map `{:a #{:b :c} :b #{c:}}` represents a graph with three nodes: `:a`, `:b` and `:c`,
where `:a` is connected to both `:b` and `:c`, and `:b` is connected to `:c`."

[[:chapter {:title "merge-graph: Merging Two Graphs" :tag "merge-graph"}]]
"`merge-graph` merges two graphs by adding the edges of the one to the other"
(fact
 (merge-graph {} {:a #{:b}}) => {:a #{:b}})

"Disjoint graphs are merged by simply merging the maps."
(fact
 (merge-graph {:a #{:b}} {:c #{:d}}) => {:a #{:b} :c #{:d}})

"When the two graphs share nodes, their destination sets are merged."
(fact
 (merge-graph {:a #{:b :c}} {:a #{:c :d}}) => {:a #{:b :c :d}})

[[:chapter {:title "invert: Invert the Edges of a DGraph" :tag "invert"}]]
"The inverse of an empty graph is an empty graph."
(fact
 (invert {}) => {})

"Each node in the destination set becomes a source edge."
(fact
 (invert {:a #{:b :c}}) => {:b #{:a} :c #{:a}})

"When several edges point at the same node, they are placed in the destination set for that node."
(fact
 (invert {:a #{:c} :b #{:c}}) => {:c #{:a :b}})

[[:chapter {:title "souces"}]]
"`sources` returns the sources in a graph, i.e., the nodes that do not have other nodes pointing to them."
(fact
 (sources {:a #{:b :c} :b #{:c}}) => #{:a})

[[:chapter {:title "toposort: Topological Sort" :tag "toposort"}]]
"`toposort` takes a graph and returns a sequence of its nodes that satisfies the partial order defined by the graph."

(fact
 (toposort {:a #{:b :c} :b #{:c :d} :c #{:e} :d #{:e}}) => [:a :b :c :d :e])

[[:section {:title "Under the Hood"}]]
"Internally, `toposort` takes the following arguments:
1. `graph`: the graph to be sorted
2. `inv`: its (inverse)[#invert]
3. `sources`: a set of its (sources)[#sources]"

"When `sources` is empty, it returns an empty sequence."
(fact
 (toposort {} {} #{}) => empty?)

"When `sources` has one element and the graph is empty, the element is returned in the sequence."
(fact
 (toposort {} {} #{:a}) => [:a])

"When the first source has destinations in the graph, we check if they become sources themselves."
(fact
 (toposort {:a #{:b}} {:b #{:a}} #{:a}) => [:a :b])

"However, when the destinations still have other sources (not yet removed from the graph),
we do not add them to the sources list."
(fact
 (let [graph {:a #{:b :c} :b #{:c}}]
   (toposort graph (invert graph) #{:a})) => [:a :b :c])
