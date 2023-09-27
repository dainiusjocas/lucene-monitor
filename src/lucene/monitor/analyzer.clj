(ns lucene.monitor.analyzer
  (:require [lucene.custom.analyzer :as a]
            [lucene.custom.analyzer-wrappers :as wrappers])
  (:import (org.apache.lucene.analysis Analyzer)))

(def create
  "Memoized version of the analyzer creation."
  (memoize a/create))

(defn ->per-field-analyzer-wrapper
  ^Analyzer [default-analyzer field->analyzer]
  (wrappers/per-field-analyzer-wrapper default-analyzer field->analyzer))
