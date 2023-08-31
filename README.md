# lucene-monitor

Clojure wrapper for the [Lucene Monitor](https://lucene.apache.org/core/9_7_0/monitor/index.html) framework.

Features:
- Monitoring if a stream documents matches any registered Lucene queries
- Each query can define its own text analyzers for both: index and query time
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
```

NOTES:
- Documents as raw strings are not supported. (You can implement it for yourself if needed)

## What is next?

- [ ] Deploy to Clojars.
- [ ] Support other than string data types.
- [ ] Make an HTTP server that does monitoring with distributed mode.
- [ ] Throughput benchmark.
- [ ] Transducer constructor for annotating documents.

## License

Copyright &copy; 2023 [Dainius Jocas](https://www.jocas.lt).

Distributed under The Apache License, Version 2.0.
