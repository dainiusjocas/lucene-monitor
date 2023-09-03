(ns lucene.monitor.document
  (:import (clojure.lang MapEntry)
           (java.util Iterator Set)
           (org.apache.lucene.document Document Field FieldType)
           (org.apache.lucene.index IndexOptions)))

(def ^FieldType field-type
  (doto (FieldType.)
    (.setTokenized true)
    (.setIndexOptions IndexOptions/DOCS)))

(defn add-field!
  "Mutates the Document by adding field(s) to it."
  [^Document doc ^String the-field-name ^String value ^Set all-field-names]
  (let [^Iterator iterator (.iterator all-field-names)]
    (while (.hasNext iterator)
      (let [^String field-name (.next iterator)]
        (when (.startsWith field-name the-field-name)
          (.add doc (Field. field-name value field-type)))))
    (when-not (.contains all-field-names the-field-name)
      (.add doc (Field. the-field-name value field-type)))))

(defn map->doc! [^Document doc m ^Set default-query-field-names]
  (let [iterator (.iterator m)]
    (while (.hasNext iterator)
      (let [field-name (.key ^MapEntry (.next iterator))]
        (add-field! doc (name field-name) (get m field-name) default-query-field-names)))))

(defn ->doc
  "For now only ths flat docs are supported.
  Keys are treated as Lucene Document Field.
  Values can only be String.
  Multiple field interpretations are supported.
  When the key is a keyword, then only name part is used."
  ^Document [m default-query-field-names]
  (doto (Document.)
    (map->doc! m default-query-field-names)))

(defn string->doc
  "Specialized Lucene Document ctor from String.
  Adds field for the default query field of the monitor, and for all
  default fields collected from queries."
  ^Document [^String string ^String default-query-field ^Set all-field-names]
  (doto (Document.)
    (add-field! default-query-field string all-field-names)))
