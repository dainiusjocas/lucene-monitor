(ns lucene.monitor.document
  (:import (org.apache.lucene.document Document Field FieldType)
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
