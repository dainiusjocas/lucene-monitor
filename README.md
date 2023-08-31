# lucene-monitor

Clojure wrapper for the [Lucene Monitor](https://lucene.apache.org/core/9_7_0/monitor/index.html) framework.

Features:
- Monitoring if a stream documents matches any registered Lucene queries
  - either one-by-one or in batches
- Each query can define its own:
  - text analyzers for both: index and query time
  - query parser
- Multiple matching modes
- Pre-filtering of queries with [`Presearcher`s](https://lucene.apache.org/core/9_7_0/monitor/org/apache/lucene/monitor/Presearcher.html)
- Persistent query sets
- [Transducer API](https://clojure.org/reference/transducers)

## Quickstart

Add the library as a git dependency to your project in the `deps.edn` file:

```clojure
{:deps
 {io.github.dainiusjocas/lucene-monitor {:git/sha "FIND_LATEST_COMMIT"}}}
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

## What is next?

- [ ] Deploy to Clojars.
- [ ] Support other than string data types in documents.
- [ ] Transducer that annotates documents.
- [ ] Implement the [debug API](https://lucene.apache.org/core/9_7_0/monitor/org/apache/lucene/monitor/Monitor.html#debug(org.apache.lucene.document.Document%5B%5D,org.apache.lucene.monitor.MatcherFactory))
- [ ] Make an HTTP server that does monitoring with a distributed mode.
- [ ] Scoring mode that both scores and highlights.
- [ ] Throughput benchmark.

## License

Copyright &copy; 2023 [Dainius Jocas](https://www.jocas.lt).

Distributed under The Apache License, Version 2.0.
