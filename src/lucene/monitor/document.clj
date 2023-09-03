(ns lucene.monitor.document
  (:import (java.util Iterator Set)
           (org.apache.lucene.document Document Field FieldType)
           (org.apache.lucene.index IndexOptions)))

(def ^FieldType field-type
  (doto (FieldType.)
    (.setTokenized true)
    (.setIndexOptions IndexOptions/DOCS)))

(defn ->doc
  "For now only ths flat docs are supported.
  Keys are treated as Lucene Document Field.
  Values can only be String.
  Multiple field interpretations are supported.
  When the key is a keyword, then only name part is used."
  ^Document [m default-query-field-names]
  (let [doc (Document.)]
    (doseq [field-name (keys m)]
      (.add doc (Field. ^String (name field-name)
                        ^String (get m field-name)
                        field-type))
      (doseq [final-field-name (filterv #(and (not= field-name %)
                                              (.startsWith % (name field-name)))
                                        default-query-field-names)]
        (.add doc (Field. ^String (name final-field-name)
                          ^String (get m field-name)
                          field-type))))
    doc))

(defn string->doc
  "Specialized Lucene Document ctor from String.
  Adds field for the default query field of the monitor, and for all
  default fields collected from queries."
  ^Document [^String string ^String default-query-field ^Set all-field-names]
  (let [doc (Document.)
        ^Iterator iterator (.iterator all-field-names)]
    (while (.hasNext iterator)
      (let [^String field-name (.next iterator)]
        (when (.startsWith field-name default-query-field)
          (.add doc (Field. field-name string field-type)))))
    (when-not (.contains all-field-names default-query-field)
      (.add doc (Field. default-query-field string field-type)))
    doc))
