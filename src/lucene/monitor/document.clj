(ns lucene.monitor.document
  (:require [clojure.string :as string])
  (:import (java.util Iterator List Map Map$Entry Set)
           (org.apache.lucene.document Document Field$Store TextField)))

(set! *warn-on-reflection* true)

(defn- ->field
  "Creates Field object. If value is not a String then stringifies it."
  [^String field-name value]
  (cond
    (string? value) (TextField. field-name ^String value Field$Store/NO)
    :else (TextField. field-name (str value) Field$Store/NO)))

(defn- add-field!
  "Mutates the Document by adding field(s) to it."
  [^Document doc ^String the-field-name value ^Set all-field-names]
  (let [^Iterator iterator (.iterator all-field-names)]
    (while (.hasNext iterator)
      (let [^String field-name (.next iterator)]
        (when (.startsWith field-name the-field-name)
          (.add doc (->field field-name value)))))
    (when-not (.contains all-field-names the-field-name)
      (.add doc (->field the-field-name value)))))

(defn- ->field-name [current-path separator]
  (->> current-path (string/join separator)))

(defn- flatten-paths
  [^Document document ^Map m separator path field-names]
  (doseq [^Map$Entry kv (.entrySet m)]
    (let [key (name (.getKey kv))
          value (.getValue kv)
          current-path (conj path key)]
      (cond
        (and (instance? Map value) (not-empty value))
        (flatten-paths document value separator current-path field-names)
        (instance? List value)
        (doseq [list-item value]
          (if (instance? Map list-item)
            (flatten-paths document list-item separator current-path field-names)
            (add-field! document (->field-name current-path separator) list-item field-names)))
        :else
        (add-field! document (->field-name current-path separator) value field-names)))))

(defn ->doc
  "Converts a (nested) Map into a Lucene Document.
  A joined sequence of keys is treated as Lucene Document Field name.
  Values can only be String.
  Multiple field interpretations are supported.
  When the key is a keyword, then only name part is used."
  ^Document [m default-query-field-names]
  (doto (Document.) (flatten-paths m "." [] default-query-field-names)))

(defn string->doc
  "Specialized Lucene Document ctor from String.
  Adds field for the default query field of the monitor, and for all
  default fields collected from queries."
  ^Document [^String string ^String default-query-field ^Set all-field-names]
  (doto (Document.)
    (add-field! default-query-field string all-field-names)))
