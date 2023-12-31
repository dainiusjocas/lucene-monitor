(ns lucene.monitor.setup
  (:require [lucene.monitor.analyzer :as analyzer]
            [lucene.monitor.queries :as queries])
  (:import (java.nio.file Path)
           (java.util Map)
           (java.util.concurrent ConcurrentHashMap)
           (java.util.function Function)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.monitor Monitor MonitorConfiguration MonitorQuerySerializer
                                      MultipassTermFilteredPresearcher Presearcher
                                      TermFilteredPresearcher)
           (org.apache.lucene.store ByteBuffersDirectory)
           (org.apache.lucene.util IOSupplier)))

(set! *warn-on-reflection* true)

(defn init-analyzer-mapping!
  "Creates field analyzers from the schema."
  [options]
  (let [field->analyzer (ConcurrentHashMap.)]
    (when-let [schema (:schema options)]
      (doseq [[field-name {:keys [analyzer]}] schema]
        (.put field->analyzer (name field-name) (analyzer/create analyzer))))
    field->analyzer))

(defn ->maintain-mapping-fn
  "Returns a single arg function that wraps over a mutable Map of field->analyzer mapping.
  When called the fn checks if field is missing, then creates an analyzer, adds it to the
  mapping, and returns the (mutable!) mapping."
  [^Map field->analyzer]
  (fn add-when-missing! [query]
    (doto field->analyzer
      (.computeIfAbsent
        (:default-field query)
        (reify Function (apply [_ _] (analyzer/create (:analyzer query))))))))

(def DEFAULT_ANALYZER (StandardAnalyzer.))

(defn register-queries! [^Monitor monitor prepare-query-xf queries opts]
  (if-let [batch-size (:batch-size opts)]
    (let [prepare-query-xf (if (int? batch-size)
                             (comp prepare-query-xf
                                   (partition-all batch-size))
                             prepare-query-xf)
          monitor-rf (fn register-batches-of-queries
                       ([] monitor)
                       ([monitor] monitor)
                       ([^Monitor monitor ^Iterable queries]
                        (doto monitor (.register queries))))]
      (transduce prepare-query-xf monitor-rf queries))
    (.register monitor ^Iterable (eduction prepare-query-xf queries))))

(defn get-default-query-field ^String [options]
  (name (:default-field options "text")))

(defn or-default-analyzer [analyzer]
  (if (empty? analyzer)
    DEFAULT_ANALYZER
    (analyzer/create analyzer)))

(defn get-default-query-analyzer [options]
  (or-default-analyzer (:default-query-analyzer options)))

(defn presearcher [key]
  (case key
    :no-filtering Presearcher/NO_FILTERING
    :term-filtered (TermFilteredPresearcher.)
    :multipass-term-filtered (MultipassTermFilteredPresearcher. 2)
    Presearcher/NO_FILTERING))

(defn monitor-configuration
  [options default-query-analyzer maintain-field->analyzer-fn]
  (let [^MonitorConfiguration config (MonitorConfiguration.)
        ^MonitorQuerySerializer mqs (queries/->monitor-query-serializer
                                      default-query-analyzer
                                      maintain-field->analyzer-fn)]
    (.setQueryUpdateBufferSize config (int (:query-update-buffer-size options 100000)))
    (if-let [index-path (:index-path options)]
      (.setIndexPath config (Path/of index-path (into-array String [])) mqs)
      (.setDirectoryProvider config (reify IOSupplier (get [_] (ByteBuffersDirectory.))) mqs))
    config))

(defn monitor
  "Creates a Monitor object.
  If :index-path is specified then loads field->Analyzer from the index."
  [options default-query-analyzer field->analyzer maintain-field->analyzer-fn]
  (let [default-field-analyzer (or-default-analyzer (:default-field-analyzer options))]
    (Monitor. (analyzer/->per-field-analyzer-wrapper default-field-analyzer
                                                     field->analyzer)
              (presearcher (:presearcher options))
              (monitor-configuration options
                                     default-query-analyzer
                                     maintain-field->analyzer-fn))))
