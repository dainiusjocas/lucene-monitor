(ns lucene.monitor.document
  (:require [charred.api :as charred]
            [clojure.string :as string])
  (:import (charred JSONReader$ObjReader)
           (java.util Iterator List Map Set)
           (org.apache.lucene.document Document Field FieldType)
           (org.apache.lucene.index IndexOptions)))

(set! *warn-on-reflection* true)

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

(defn map->doc! [^Document doc ^Map m ^Set default-query-field-names]
  (let [iterator (.iterator (.keySet m))]
    (while (.hasNext iterator)
      (let [field-name (.next iterator)]
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

(defn json->doc-fn [field-names]
  (charred/parse-json-fn
    {:obj-iface
     (reify JSONReader$ObjReader
       (newObj [_] (Document.))
       (onKV [_ document k v]
         (when (string? v)
           (doto document (add-field! k v field-names))))
       (finalizeObj [_ document] document))}))

; This should be used as a transducer
; The task is:
; Read NDJSON file (or stdin)
; transjuxt:
;  {:identity (comp) ; here is a json string
;   :monitor (comp (json->doc) (filter-xf))
; (filter (< 0 (:monitor 0))
; (map :identity)
; what
(defn- json->doc
  "Given a string which is JSON, efficiently parses it to the Document"
  ^Document [^String json default-field-names]
  ((json->doc-fn default-field-names) json))

(comment
  (json->doc (charred/write-json-str {"text" "data"}) #{})
  (json->doc (charred/write-json-str {"text" "a" "q" {"data" "foo"}}) #{}))

(defn string->doc
  "Specialized Lucene Document ctor from String.
  Adds field for the default query field of the monitor, and for all
  default fields collected from queries."
  ^Document [^String string ^String default-query-field ^Set all-field-names]
  (doto (Document.)
    (add-field! default-query-field string all-field-names)))

(defn ->field [^String field-name value]
  (cond
    (string? value) (Field. field-name ^String value field-type)
    :else (Field. field-name (str value) field-type)))

; https://andersmurphy.com/2019/11/30/clojure-flattening-key-paths.html


(defn flatten-paths
  ([m separator]
   (flatten-paths m separator [] (Document.)))
  ([m separator path ^Document document]
   (doseq [kv m]
     (let [key (name (first kv))
           value (second kv)]
       (if (and (map? value) (not-empty value))
         ; go deeper
         (flatten-paths value separator (conj path key) document)
         ; make one flattened path
         (let [current-path (conj path key)
               ^String field-name (->> current-path (string/join separator))]
           (if (instance? List value)
             (doseq [list-item value]
               (if (map? list-item)
                 (flatten-paths list-item separator current-path document)
                 (.add document (->field (->> current-path (string/join separator)) list-item))))
             (.add document (->field field-name value)))))))
   document))

(defn nested->doc [m]
  (flatten-paths m "." [] (Document.)))
