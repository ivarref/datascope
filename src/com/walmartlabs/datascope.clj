(ns com.walmartlabs.datascope
  (:require [rhizome.viz :as viz]
            [clojure.string :as str])
  (:import [clojure.lang ISeq IPersistentVector IPersistentMap IDeref Symbol Keyword]
           [java.util Date]))

(defprotocol Classifier
  "Used to classify arbirary values into categories: :scalar, :map, :vector, :sequence, :ref."

  (classify [v]
    "Classification method extending on to many classes.")
  )

;; It's looking like this is not all that necessary; just
;; need a method to classify as scalar or not scalar.

(extend-protocol Classifier

  nil
  (classify [_] :scalar)

  Number
  (classify [_] :scalar)

  String
  (classify [_] :scalar)

  Date
  (classify [_] :scalar)

  Boolean
  (classify [_] :scalar)

  Symbol
  (classify [_] :scalar)

  Keyword
  (classify [_] :scalar)

  ISeq
  (classify [_] :sequence)

  IPersistentVector
  (classify [_] :vector)

  IPersistentMap
  (classify [_] :map)

  IDeref
  (classify [_] :ref))

(defprotocol Scalar
  "Scalars appear as keys and values and this assists with rendering of them as such."

  (as-label [v]
    "Return the text representation of a value such that it can be included as a label for a key or value."
    )
  )

(defn ^:private is-scalar?
  [v]
  (= :scalar (classify v)))

(extend-protocol Scalar

  Object
  (as-label [v] (pr-str v)))


(defprotocol Composite
  "Other types wrap one or more values (a map, sequence, vector, etc.)"


  (render-composite [v state]
    "Renders the value as a new node (this is often quite recursive).

    Modifies and returns a new state.

    State has a number of keys:

    :values
    : map from a non-scalar value to its unique id; this is used to create edges, and track
      which values have already been rendered.

    :nodes
    : map from value id to rendered text for the node; rendering adds a value to this map.

    :edges
    : map from node id to node id; the source node is may be extended with a port (e.g., \"map_1:v3\")\"")

  )

(defn ^:private gen-value-id
  [state type]
  (str (name type) "_" (-> state :values count)))

(defn ^:private maybe-render
  [state v]
  (if (is-scalar? v)
    [state nil]
    (if-let [value-id (get-in state [:values v])]
      [state value-id]
      (let [state' (render-composite v state)]
        [state' (get-in state' [:values v])]))))

(defn ^:private html-safe
  [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn ^:private cell
  [state id prefix v i]
  (let [[state' value-id] (maybe-render state v)
        port (when value-id (str (name prefix) i))
        new-state (if port
                    (assoc-in state' [:edges (str id ":" port)] value-id)
                    state')]
    [new-state (str (if port
                      (str "<td port=" \" port \" "> ")
                      "<td>")
                    (when-not port
                      (-> v as-label html-safe))
                    "</td>")]))

(defn ^:private add-empty
  [state type v]
  (let [id (gen-value-id state type)
        empty-label (pr-str v)]
    (-> state
        (assoc-in [:values v] id)
        (assoc-in [:nodes :empty id] (str "[label=" \" empty-label \" \])))))

(defn ^:private render-map
  [state m]
  (if (empty? m)
    (add-empty state :map m)
    (let [map-id (gen-value-id state :map)
          reduction-step (fn [state k v i]
                           (let [[k-state k-chunk] (cell state map-id :k k i)
                                 [v-state v-chunk] (cell k-state map-id :v v i)]
                             [v-state (str "<tr>"
                                           k-chunk
                                           v-chunk
                                           "</tr>")]))
          ikvs (map-indexed (fn [i [k v]]
                              [i k v])
                            m)
          reducer (fn [[state label-chunk] [i k v]]
                    (let [[state' row-chunk] (reduction-step state k v i)]
                      [state' (str label-chunk row-chunk)]))
          [state' label-chunk] (reduce reducer
                                       [(assoc-in state [:values m] map-id) ""]
                                       ikvs)]
      (assoc-in state' [:nodes :maps map-id] (str "[label=<<table border=\"0\" cellborder=\"1\">\""
                                                  label-chunk
                                                  "</table>>]")))))

(defn ^:private render-vector
  [state v]
  (if (empty? v)
    (add-empty state :vec v)
    (let [vec-id (gen-value-id state :vec)
          reducer (fn [[state label-chunk] i v]
                    (let [[state' cell-chunk] (cell state vec-id :i v i)]
                      [state' (str label-chunk "<tr>" cell-chunk "</tr>")]))
          [state' label-chunk] (reduce-kv reducer
                                          [(assoc-in state [:values v] vec-id) ""]
                                          v)]
      (assoc-in state' [:nodes :vecs vec-id] (str "[label=<<table border=\"0\" cellborder=\"1\">"
                                                  label-chunk
                                                  "</table>>]")))))

(defn ^:private render-seq
  [state coll]
  (if (empty? coll)
    (add-empty state :seq coll)
    (let [seq-id (gen-value-id state :seq)
          max-seq (:max-seq state 10)
          reducer (fn [[state label-chunk] [i v]]
                    (if (= i max-seq)
                      [state (str label-chunk "<tr><td>...</td></tr>")]
                      (let [[state' cell-chunk] (cell state seq-id :i v i)]
                        [state' (str label-chunk "<tr>" cell-chunk "</tr>")])))
          ivs (->> (map vector (iterate inc 0) coll)
                   (take (inc max-seq)))
          [state' label-chunk] (reduce reducer
                                       [(assoc-in state [:values coll] seq-id) ""]
                                       ivs)]
      (assoc-in state' [:nodes :seqs seq-id] (str "[label=<<table border=\"0\" cellborder=\"1\">\""
                                                  label-chunk
                                                  "</table>>]")))))
(extend-protocol Composite

  IPersistentMap
  (render-composite [m state]
    (render-map state m))

  IPersistentVector
  (render-composite [v state]
    (render-vector state v))

  ISeq
  (render-composite [coll state]
    (render-seq state coll)))

(defn ^:private render-nodes
  [nodes key defaults]
  (when-not (str/blank? defaults)
    (println (str "\n  node [" defaults "];")))
  (doseq [[id text] (get nodes key)]
    (println (str "  "  id " " text ";"))))

(defn render
  [root-value]
  (let [{:keys [nodes edges]} (render-composite root-value {})
        dot (with-out-str
              (println "digraph G {\n  rankdir=LR;")
              (println "  node [shape=box, style=\"rounded,filled\", fillcolor=\"#FAF0E6\"];")
              (render-nodes nodes :maps "")
              (render-nodes nodes :seqs "")
              (render-nodes nodes :vecs "style=filled")
              (render-nodes nodes :empty "shape=none, style=\"\", fontsize=32")
              (println)
              (doseq [[from to] edges]
                (println (str "  " from " -> " to ";")))
              (println "}"))]
    (println dot)
    (-> dot viz/dot->image viz/view-image)))