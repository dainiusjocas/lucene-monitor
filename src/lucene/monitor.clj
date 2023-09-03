(ns lucene.monitor
  (:gen-class)
  (:require [lucene.monitor.document :as document]
            [lucene.monitor.queries :as queries]
            [lucene.monitor.matching :as matching]
            [lucene.monitor.setup :as setup])
  (:import (java.io Closeable)
           (java.util List Map)
           (org.apache.lucene.monitor Monitor)))

(set! *warn-on-reflection* true)

(defprotocol LuceneMonitor
  (match [this docs] [this docs options]
    "Accepts vector of maps.
    Returns a vector (per input doc) of vectors (matches per doc)
    If one map, then wrap it into a vector and on return: a vector of matches.
    Depending on options, various details of match can be returned.")
  (match-string [this string] [this string options] "Accepts one string to be matched.")
  (get-query-cache-stats [this])
  (purge-cache [this])
  (register [this queries] [this queries opts]
    "Here we expect queries object that can be transducible, e.g. vector.
    opts: support for microbatching of queries, for scenarios e.g. Kafka topic
    is a source of queries.")
  (delete-by-id [this ^List ids]
    "Probably it should accept also one ID String.
    Deletes queries from the monitor.")
  (get-query [this query-id]
    "Returns one query by ID. The normalized representation.
    The keys in the metadata a returned as keywords.")
  (get-disjunct-count [this])
  (get-query-count [this])
  (get-query-ids [this]
    "Returns the Set of query ids of the queries stored in this Monitor")
  (clear [this]
    "Delete all queries from the monitor.
    Resets the field-name analyzer mappings.")
  ; TODO: implement support for debugging
  #_(debug [this doc] [this doc options]))

(defn monitor
  "Params:
  - options: a map to configure the monitor
    :default-field - default field that queries are querying, default=text
    :default-field-analyzer - how to analyze tet fields: default=standard analyzer
    :default-query-analyzer - when specified is used for queries that do no
      specify analyzer, is used at the queries loading from directory.
    :default-match-mode
    :presearcher
    :index-path
    :default-query-parser
    :query-update-buffer-size
  - queries: initial set of queries.
  Queries should be IReduceInit/Iterable"
  ([] (monitor {}))
  ([options]
   (let [^Map field-name->lucene-analyzer (setup/init-analyzer-mapping! options)
         maintain-mapping-fn (setup/->maintain-mapping-fn field-name->lucene-analyzer)
         default-query-field (setup/get-default-query-field options)
         default-query-analyzer (setup/get-default-query-analyzer options)
         prepare-query-xf (queries/prepare-xf options
                                              default-query-field
                                              default-query-analyzer
                                              maintain-mapping-fn)
         ^Monitor monitor (setup/monitor options
                                         default-query-analyzer
                                         field-name->lucene-analyzer
                                         maintain-mapping-fn)
         default-match-mode (:default-match-mode options)
         default-match-options {:mode default-match-mode}]
     (reify LuceneMonitor
       (match-string [this string] (match-string this string default-match-options))
       (match-string [_ string options]
         (matching/match-single monitor
                                (document/string->doc
                                  string
                                  default-query-field
                                  (.keySet field-name->lucene-analyzer))
                                (if (and default-match-mode (nil? (:mode options)))
                                  (assoc options :mode default-match-mode)
                                  options)))
       (match [this docs] (match this docs default-match-options))
       (match [_ docs options]
         (let [match-options (if (and default-match-mode (nil? (:mode options)))
                               (assoc options :mode default-match-mode)
                               options)]
           (matching/to-batch-and-match docs monitor (.keySet field-name->lucene-analyzer) match-options)))
       (get-query-count [_] (.getQueryCount monitor))
       (get-query [_ query-id]
         (queries/->conf (.getQuery monitor query-id)))
       (get-query-cache-stats [_]
         (let [stats (.getQueryCacheStats monitor)]
           {:queries        (.-queries stats)
            :cached-queries (.-cachedQueries stats)
            :last-purged    (.-lastPurged stats)}))
       (purge-cache [_] (.purgeCache monitor))
       (register [this queries] (register this queries {}))
       (register [_ queries opts]
         (let [queries (cond-> queries (map? queries) (vector))]
           (setup/register-queries! monitor prepare-query-xf queries opts)))
       (delete-by-id [_ ids]
         (let [^List ids (if (string? ids) (List/of ids) ids)]
           ; probably we need to here to remove a mapping?
           ; How can you know is the query with an ID caused the mapping to appear?
           (.deleteById monitor ids)))
       (clear [_]
         (.clear field-name->lucene-analyzer)
         (.clear monitor))
       (get-disjunct-count [_] (.getDisjunctCount monitor))
       (get-query-ids [_] (.getQueryIds monitor))
       Closeable
       (close [_] (.close monitor)))))
  ([options queries]
   (doto (monitor options) (register queries))))

(comment
  (with-open [monitor (monitor)]
    (register monitor [{:id    "12"
                        :query "text"}])
    (match-string monitor "foo text bar")))

(defn filter-xf
  "Creates a transducer that filters docs that match
  queries registered in a Monitor.
  1-arity expects an open monitor, it is your responsibility to close it.
  2-arity expects monitor options and a list of queries and
  the Monitor is closed on calling completion arity."
  ([monitor]
   (filter (fn [doc] (not= 0 (match monitor doc {:mode :count})))))
  ([options queries]
   (fn [rf]
     (let [^Closeable monitor (monitor options queries)]
       (fn
         ([] (rf))
         ([acc] (.close monitor) (rf acc))
         ([acc doc]
          (if (not= 0 (match monitor doc {:mode :count}))
            (rf acc doc)
            acc)))))))

(comment
  (with-open [monitor (monitor {} [{:id "12" :query "text"}])]
    (let [monitor-xf (filter-xf monitor)]
      [(into [] monitor-xf [{:text "foo text bar"} {:text "no match"}])
       (into [] monitor-xf [{:text "foo text bar"} {:text "no match"}])]))

  (let [monitor-xf (filter-xf {} [{:id "12" :query "text"}])]
    [(into [] monitor-xf [{:text "foo text bar"} {:text "no match"}])
     (into [] monitor-xf [{:text "foo text bar"} {:text "no match"}])]))
