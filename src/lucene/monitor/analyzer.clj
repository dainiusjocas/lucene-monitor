(ns lucene.monitor.analyzer
  (:require [lucene.custom.analyzer :as a]))

(def create
  "Memoized version of the analyzer creation."
  (memoize a/create))
