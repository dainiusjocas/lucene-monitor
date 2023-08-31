(ns lucene.monitor.serde
  (:require [jsonista.core :as json]))

(def ^:private object-mapper
  (json/object-mapper {:encode-key-fn true
                       :decode-key-fn true
                       :strip-nils    true}))
(defn serialize ^String [o]
  (json/write-value-as-string o object-mapper))

(defn deserialize [o]
  (json/read-value o object-mapper))
