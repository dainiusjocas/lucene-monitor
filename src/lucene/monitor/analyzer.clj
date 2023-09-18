(ns lucene.monitor.analyzer
  (:require [lucene.custom.analyzer :as a])
  (:import (java.util HashMap Map Map$Entry)
           (org.apache.lucene.analysis Analyzer)
           (org.apache.lucene.analysis.miscellaneous PerFieldAnalyzerWrapper)))

(def create
  "Memoized version of the analyzer creation."
  (memoize a/create))

(defn ->per-field-analyzer-wrapper
  "Creates a PerFieldAnalyzerWrapper.
  TIP: if field->analyzer is mutable, mutations can be done after the creation
  are going to be visible for analysis.
  Params:
  * default: default Analyzer object,
  * field->analyzer: Map<String, Analyzer>"
  ^Analyzer [^Analyzer default ^Map field->analyzer]
  (PerFieldAnalyzerWrapper. default field->analyzer))

(defn prepare->per-field-analyzer-wrapper
  "Constructs a PerFieldAnalyzerWrapper.
  Tip: in case you use
  Params:
  * default: a default analyzer configuration
  * field->analyzer: a map from field name to the analyzer configuration"
  [default ^Map field->analyzer]
  (let [default-analyzer (create default)
        f->a (reduce (fn add-to-mapping! [^Map mapping ^Map$Entry kv]
                       (doto mapping (.put (name (.getKey kv)) (create (.getValue kv)))))
                     (HashMap.)
                     (.entrySet field->analyzer))]
    (->per-field-analyzer-wrapper default-analyzer f->a)))
