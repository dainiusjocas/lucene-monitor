(ns lucene.monitor.queries
  (:require [clojure.java.io :as io]
            [lucene.custom.query :as q]
            [lucene.monitor.analyzer :as a]
            [lucene.monitor.print :as print]
            [lucene.monitor.serde :as serde])
  (:import (java.util HashMap Map)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.queryparser.classic ParseException)
           (org.apache.lucene.monitor MonitorQuery MonitorQuerySerializer)
           (org.apache.lucene.search Query)
           (clojure.lang PersistentArrayMap)
           (org.apache.lucene.util BytesRef)))

(defn prepare-meta
  "Metadata must be a map String->String"
  ^Map [meta]
  (if meta
    (reduce-kv (fn [m k v] (doto m (.put (name k) v))) (HashMap.) meta)
    (HashMap.)))

(def CONF_KEY "__MQ_CONF")

(defn ->monitor-query
  "We get a prepared query here that has all the params prepared.
  Params:
    monitor-query: a map with keys:
      :id
      :query - Lucene query string
      :meta - query metadata map
      :default-field - (required)
      :query-parser - {:name, :analyzer, and the rest of the configuration}.
      :analyzer - field analyzer, if :query-parser/analyzer is not provided also, query analyzer.
    default-query-analyzer: Analyzer that will be used if none of the query specific
      analyzers are provided
    callback: is called with mq map before creating the monitor query itself."
  (^MonitorQuery [monitor-query] (->monitor-query monitor-query (StandardAnalyzer.)))
  (^MonitorQuery [monitor-query default-query-analyzer]
   (->monitor-query monitor-query default-query-analyzer (fn [_])))
  (^MonitorQuery [{:keys [id query meta default-field query-parser analyzer] :as mq} default-query-analyzer callback]
   (try
     (let [query-parser-name (get query-parser :name)
           ; in case query should be analyzer in different way than field text
           query-analyzer (if-let [analyzer-conf (or (:analyzer query-parser) analyzer)]
                            (a/create analyzer-conf)
                            default-query-analyzer)
           lucene-query (q/parse query query-parser-name query-parser default-field query-analyzer)
           monitor-query (MonitorQuery. ^String id
                                        ^Query lucene-query
                                        ^String query
                                        (doto (prepare-meta meta) (.put CONF_KEY (serde/serialize mq))))]
       (callback mq)
       monitor-query)
     (catch ParseException e
       (when (System/getenv "DEBUG_MODE")
         (print/to-err (format "Failed to parse query: '%s' with exception '%s'" mq e))
         (print/throwable e))
       (throw e))
     (catch Exception e
       (when (System/getenv "DEBUG_MODE")
         (print/to-err (format "Failed create query: '%s' with '%s'" mq e))
         (print/throwable e))
       (throw e)))))

(defn stable-id [m]
  (str (Math/abs ^int (.hashCode ^PersistentArrayMap m))))

(defn id-xf []
  (map (fn ensure-id [query]
         (if (:id query)
           query
           (assoc query :id (stable-id query))))))

(defn default-field-name-xf
  "If query does not specify the default and global default is not provided
  then field name is calculated from the analyzer configuration."
  [default-query-field]
  (map (fn [query]
         ; if the default-field is provided, append analyzer suffix
         ; else add analyzer suffix to the default query field
         ; analyzer suffix for nil conf is nil
         (assoc query
           :default-field
           (let [analyzer-suffix (when-let [ac (:analyzer query)] (hash ac))
                 base (or (:default-field query) default-query-field)]
             (str base (when analyzer-suffix (str "." analyzer-suffix))))))))

(defn query-parser-xf
  "If query parser is not specified then assoc the default query parser conf."
  [default-query-parser-conf]
  (map (fn [query]
         (if (and default-query-parser-conf (empty? (get query :query-parser)))
           (assoc query :query-parser default-query-parser-conf)
           query))))

(defn into-monitor-query-xf
  [default-query-analyzer maintain-field->analyzer-fn]
  (map (fn [query]
         (->monitor-query query default-query-analyzer maintain-field->analyzer-fn))))

(defn prepare-xf
  "Returns a transducer that prepared the query configuration."
  [options default-query-field default-query-analyzer maintain-field->analyzer-fn]
  (comp
    (id-xf)
    (default-field-name-xf default-query-field)
    (query-parser-xf (:default-query-parser options))
    (into-monitor-query-xf default-query-analyzer maintain-field->analyzer-fn)))

(defn ->monitor-query-serializer [default-query-analyzer maintain-mapping-fn]
  (reify MonitorQuerySerializer
    (serialize [_ monitor-query]
      ; Original query record is encoded in the Metadata
      (BytesRef. ^CharSequence (.get (.getMetadata monitor-query) CONF_KEY)))
    (deserialize [_ bytes-ref]
      (let [mq (serde/deserialize (io/reader (.bytes ^BytesRef bytes-ref)))]
        (->monitor-query mq default-query-analyzer maintain-mapping-fn)))))

(defn ->conf [^MonitorQuery monitor-query]
  (-> monitor-query (.getMetadata) (get CONF_KEY) (serde/deserialize)))
