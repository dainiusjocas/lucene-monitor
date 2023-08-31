# lucene-monitor

Clojure wrapper for the [Lucene Monitor](https://lucene.apache.org/core/9_7_0/monitor/index.html) framework.

Features:
- Monitoring a stream documents to match registered Lucene queries
  - either one-by-one or in batches
- Each query can define its own:
  - [text analyzers](https://github.com/dainiusjocas/lucene-custom-analyzer) for both: index and query time
  - [Lucene query parser](https://github.com/dainiusjocas/lucene-query-parsing)
- Multiple matching modes
- Pre-filtering of queries with [`Presearcher`s](https://lucene.apache.org/core/9_7_0/monitor/org/apache/lucene/monitor/Presearcher.html)
- Persistent query sets
- [Transducer API](https://clojure.org/reference/transducers)

## Quickstart

Add the library to dependencies in the `deps.edn` file:

```clojure
{:deps {lt.jocas/lucene-monitor {:mvn/version "1.0.2"}}}
```

In your REPL:

```clojure
(require '[lucene.monitor :as m])

(with-open [monitor (m/monitor)]
  (m/register monitor {:id    "query-id"
                       :query "query"})
  (m/match-string monitor "foo query bar"))
; => [{:id "query-id"}]
```

## Queries across fields

Queries can span multiple fields:
```clojure
(with-open [monitor (m/monitor {} [{:id    "1"
                                    :query "field-a:foo AND field-b:bar"}])]
  (m/match monitor {:field-a "prefix foo suffix"})
  ; => [] i.e. no matches
  (m/match monitor {:field-a "prefix foo suffix"
                    :field-b "prefix bar suffix"}))
; => [{:id "1"}]
```

## Matching modes

Several matching modes are supported:
- count: returns the count of matched queries per input document;
- id (the default): for each input document returns a list of query IDs;
- score: also returns the matching score next to the query ID;
- highlight: returns spans of text that matched the query.

Usage:
```clojure
(with-open [monitor (m/monitor {} [{:id "1" :query "test"}])]
  ; The default matching mode is :id
  (m/match-string monitor "foo test bar")
  ; => [{:id "1"}]
  (m/match-string monitor "foo test bar" {:mode :count})
  ; => 1
  (m/match-string monitor "foo test bar" {:mode :id})
  ; => [{:id "1"}]
  (prn (m/match-string monitor "foo test bar" {:mode :score}))
  ; => [{:id "1", :score 0.13076457}]
  (m/match-string monitor "foo test bar" {:mode :highlight})
  ; => [{:id         "1"
  ;      :highlights {"text" [{:end-offset     8
  ;                            :end-position   1
  ;                            :start-offset   4
  ;                            :start-position 1}]}
  ,)
```

A default match mode can be specified:
```clojure
(with-open [monitor (m/monitor {:default-match-mode :highlight} [{:id "1" :query "test"}])]
  (m/match-string monitor "prefix test suffix"))
; [{:id "1", :highlights {"text" [{:start-position 1, :end-position 1, :start-offset 7, :end-offset 11}]}}]
```

## Persistent query sets

You can create a monitor that creates an index backed by the directory in the filesystem.
It allows you to load queries from a previously created index.

Usage:
```clojure
(let [options {:index-path "target/monitor-index"}
      queries [{:id "12" :query "text"}]
      txt "foo text bar"]
  (with-open [monitor (m/monitor options queries)]
    (prn (m/match-string monitor txt)))

  ; Notice that this time we do not supply queries
  ; They are loader from the disk
  (with-open [monitor (m/monitor options)]
    (prn (m/match-string monitor txt))))
; => [{:id "12"}]
; => [{:id "12"}]
```

## Transducer API

Monitor can be used to **filter** documents in a transducing context.

Two ways to use it:
1. You create a Monitor and supply it to the transducer
2. You construct a transducer by supplying monitor configuration and a static set of queries.

The key differences:
- in (1) you are responsible for closing the Monitor while (2) manages it for you.
- in (2) the query list is static

Usage:
```clojure
; (1) 
(with-open [monitor (m/monitor {} [{:id "12" :query "text"}])]
  (let [monitor-xf (m/filter-xf monitor)]
    (into [] monitor-xf [{:text "foo text bar"} {:text "no match"}])))
; => [{:text "foo text bar"}]

; (2)
(let [monitor-xf (m/filter-xf {} [{:id "12" :query "text"}])]
  (into [] monitor-xf [{:text "foo text bar"} {:text "no match"}]))
; => [{:text "foo text bar"}]

; Docs can be matched in batches for efficiency
(let [monitor-xf (m/filter-xf {} [{:id "12" :query "text"}])]
  (into []
        (comp monitor-xf (partition-all 2) cat)
        [{:text "foo text bar"}
         {:text "no match"}
         {:text "another text doc"}
         {:text "no match"}]))
; => [{:text "foo text bar"} {:text "another text doc"}]
```

NOTES:
- Documents as raw strings are not supported. (You can implement it for yourself if needed)

## Flexible text analysis

A fine-grained text analysis configuration is supported.
The text analysis order in priority (high to low):
- each individual query can specify its default field and query string analyzers;
  - i.e. asymmetric tokenization is supported
  - Query strings can be expanded with synonyms
  - a language specific analysis can be done for each query
  - query string analysis depends on the configured query parser
- Schema with analyzers per field;
- Default analyzers for both field and query.

There are too many possible combinations to demonstrate, so, I encourage you to experiment.
However, by default you don't need to configure anything: start simple and fine-tune as much as you need. 

Analyzers are created with the [lucene-custom-analyzer](https://github.com/dainiusjocas/lucene-custom-analyzer) library. 

A really convoluted example:
```clojure
(with-open [monitor (m/monitor
                      {:schema        {:my-field-name {:analyzer {:token-filters [:reverseString]}}}
                       :default-field          "my-text-field"
                       :default-field-analyzer {:token-filters [:lowercase]}
                       :default-query-analyzer {:token-filters [:uppercase]}
                       :default-query-parser   {:name     :classic
                                                :analyzer {:token-filters [:uppercase]}}})]
  (m/register monitor {:id            "12"
                       :query         "test"
                       :default-field "my-another-field"
                       :analyzer      {:token-filters [:reverseString :uppercase]}
                       :query-parser  {:name     :complex-phrase
                                       :analyzer {:token-filters [:reverseString :uppercase]}}}))
```

## Query IDs

Query IDs are optional.
Specifying query ID allows to duplicate queries in the monitor.

When IDs are not provided, a query map hash is used as a query ID.
Hashing query effectively deduplicate identical queries.
You might consider leveraging hashing in combination with getting the queries out of the monitor, e.g.:
```clojure
(with-open [monitor (m/monitor {} [{:query "text" :default-field "my-field"}])]
  (mapv #(m/get-query monitor (:id %))
        (m/match monitor {:my-field "foo text bar"})))
; =>  [{:default-field "my-field", :id "1067106267", :query "text"}]
```

## Queries support metadata

In case you want to store some details (e.g. sources) about the query in the monitor,
each query can specify its `:meta`, e.g.:
```clojure
(with-open [monitor (m/monitor {} [{:query "text"
                                    :meta {:my-type "example"}}])]
  (mapv #(m/get-query monitor (:id %))
        (m/match-string monitor "foo text bar")))
; => [{:meta {:my-type "example"}, :default-field "text", :id "1772475640", :query "text"}]
```

## What is next?

- [ ] Support other than string data types in documents.
- [ ] Support nested documents.
- [ ] Transducer that annotates documents.
- [ ] Implement the [debug API](https://lucene.apache.org/core/9_7_0/monitor/org/apache/lucene/monitor/Monitor.html#debug(org.apache.lucene.document.Document%5B%5D,org.apache.lucene.monitor.MatcherFactory))
- [ ] Demo an [Elasticsearch-Percolator-like](https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-percolate-query.html) interface that does monitoring with a distributed mode.
- [ ] Scoring mode that both scores and highlights.
- [ ] Provide Malli schemas or Specs.
- [ ] Throughput benchmark.

## License

Copyright &copy; 2023 [Dainius Jocas](https://www.jocas.lt).

Distributed under The Apache License, Version 2.0.
