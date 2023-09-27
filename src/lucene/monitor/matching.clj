(ns lucene.monitor.matching
  (:require [lucene.monitor.document :as document])
  (:import (java.util Map Map$Entry)
           (org.apache.lucene.monitor ExplainingMatch Monitor PresearcherMatches QueryMatch ScoringMatch
                                      HighlightsMatch HighlightsMatch$Hit
                                      MatchingQueries MultiMatchingQueries MatcherFactory)
           (org.apache.lucene.document Document)
           (org.apache.lucene.search Explanation)))

(set! *warn-on-reflection* true)

(defn id-fn [^QueryMatch query-match]
  {:id (.getQueryId query-match)})

(defn score-fn [^ScoringMatch query-match]
  {:id    (.getQueryId query-match)
   :score (.getScore query-match)})

(defn highlights-fn [^HighlightsMatch query-match]
  {:id         (.getQueryId query-match)
   :highlights (reduce
                 (fn [acc ^Map$Entry field-hits]
                   (assoc acc
                     (.getKey field-hits)
                     (mapv (fn [^HighlightsMatch$Hit hit]
                             {:start-position (.-startPosition hit)
                              :end-position   (.-endPosition hit)
                              :start-offset   (.-startOffset hit)
                              :end-offset     (.-endOffset hit)})
                           (.getValue field-hits))))
                 {} (.getHits query-match))})

(defn- ->explanation [^Explanation explanation]
  {:value       (.getValue explanation)
   :description (.getDescription explanation)
   :details     (mapv ->explanation (.getDetails explanation))})

(defn explain-fn [^ExplainingMatch query-match]
  (let [explanation (.getExplanation query-match)]
    {:id          (.getQueryId query-match)
     :matched     (.isMatch explanation)
     :explanation (->explanation explanation)}))

(defn get-fn [options]
  (case (keyword (:mode options))
    :id id-fn
    :score score-fn
    :highlight highlights-fn
    :explain explain-fn
    id-fn))

(defn matcher ^MatcherFactory [options]
  (case (keyword (:mode options))
    :id (QueryMatch/SIMPLE_MATCHER)
    :score (ScoringMatch/DEFAULT_MATCHER)
    :highlight (HighlightsMatch/MATCHER)
    :explain (ExplainingMatch/MATCHER)
    (QueryMatch/SIMPLE_MATCHER)))

(defn match-single [^Monitor monitor ^Document document opts]
  (let [match-mode (:mode opts)
        ^MatchingQueries mmqs (.match monitor document (matcher opts))
        from-query-match-fn (get-fn opts)
        matches (if (= :count match-mode)
                  (.getMatchCount mmqs)
                  (mapv from-query-match-fn (.getMatches mmqs)))]
    (cond-> matches
            (and (true? (:with-details opts))
                 (not (= :count match-mode)))
            (with-meta
              {:queries-run         (.getQueriesRun mmqs)
               :search-time-ms      (.getSearchTime mmqs)
               :query-build-time-ns (.getQueryBuildTime mmqs)
               :errors              (.getErrors mmqs)}))))

(defn match-batch [^Monitor monitor #^"[Lorg.apache.lucene.document.Document;" docs opts]
  (let [ndocs (alength docs)
        match-mode (:mode opts)
        ^MultiMatchingQueries mmqs (.match monitor docs (matcher opts))
        from-query-match-fn (get-fn opts)
        matches (loop [i 0 acc (transient [])]
                  (if (< i ndocs)
                    (recur (inc i)
                           (conj! acc (if (= :count match-mode)
                                        (.getMatchCount mmqs i)
                                        (mapv from-query-match-fn (.getMatches mmqs i)))))
                    (persistent! acc)))]
    (cond-> matches
            (and (true? (:with-details opts))
                 (not (= :count match-mode)))
            (with-meta
              {:batch-size          (.getBatchSize mmqs)
               :queries-run         (.getQueriesRun mmqs)
               :search-time-ms      (.getSearchTime mmqs)
               :query-build-time-ns (.getQueryBuildTime mmqs)
               :errors              (.getErrors mmqs)}))))

(defn take-first-and-meta [matches]
  (let [first-match (first matches)]
    (if (number? first-match)
      first-match
      (with-meta first-match (meta matches)))))

(defn ->batch [docs field-names]
  (if (instance? Map docs)
    (doto #^"[Lorg.apache.lucene.document.Document;" (make-array Document 1)
      (aset 0 (document/->doc docs field-names)))
    (let [#^"[Lorg.apache.lucene.document.Document;" arr
          (make-array Document (count docs))]
      (amap arr idx _ ^Document (document/->doc (nth docs idx) field-names)))))

(defn to-batch-and-match
  "We can also get the detailed match data, with timings and stuff.
  Control this via flag in opts."
  [my-docs monitor field-names opts]
  (cond-> (match-batch monitor (->batch my-docs field-names) opts)
          (map? my-docs) (take-first-and-meta)))

(defn debug [doc ^Monitor monitor field-names options]
  (let [^MatcherFactory matcher (matcher options)
        from-query-match-fn (get-fn options)
        #^"[Lorg.apache.lucene.document.Document;" batch (->batch doc field-names)
        ndocs (alength batch)
        ^PresearcherMatches presearcher-matches (.debug monitor batch matcher)
        ^MultiMatchingQueries matching-queries (.-matcher presearcher-matches)
        matches (loop [i 0 acc (transient [])]
                  (if (< i ndocs)
                    (recur
                      (inc i)
                      (conj! acc (mapv (fn [^QueryMatch query-match]
                                         (assoc (from-query-match-fn query-match)
                                           :presearcher-matches
                                           (.-presearcherMatches
                                             (.match presearcher-matches
                                                     (.getQueryId query-match) i))))
                                       (.getMatches matching-queries i))))
                    (persistent! acc)))]
    (with-meta matches
               {:batch-size          (.getBatchSize matching-queries)
                :queries-run         (.getQueriesRun matching-queries)
                :search-time-ms      (.getSearchTime matching-queries)
                :query-build-time-ns (.getQueryBuildTime matching-queries)
                :errors              (.getErrors matching-queries)})))
