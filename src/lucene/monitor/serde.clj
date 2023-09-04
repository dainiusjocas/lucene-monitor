(ns lucene.monitor.serde
  (:require [charred.api :as charred]))

(defn serialize ^String [o]
  (charred/write-json-str o))

(def ^:private parse-json-fn
  (charred/parse-json-fn
    {:key-fn keyword
     :profile :mutable}))

(defn deserialize [o]
  (parse-json-fn o))
