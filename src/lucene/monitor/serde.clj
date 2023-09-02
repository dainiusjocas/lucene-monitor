(ns lucene.monitor.serde
  (:require [charred.api :as charred]))

(defn serialize ^String [o]
  (charred/write-json-str o))

(defn deserialize [o]
  (charred/read-json o :key-fn keyword))
