(ns lucene.monitor.queries-test
  (:require [clojure.test :refer [deftest is testing]]
            [lucene.monitor.queries :as q]
            [lucene.custom.analyzer :as a])
  (:import (org.apache.lucene.monitor MonitorQuery)))

(deftest monitor-query-construction
  (let [mq {:id            "12"
            :query         "test"
            :meta          {"a" "b"}
            :default-field "text"}
        MQ (q/->monitor-query mq)]

    (is (instance? MonitorQuery MQ))
    (is (= "12" (.getId MQ)))
    (is (= "b" (get (.getMetadata MQ) "a")))
    (is (= ["__MQ_CONF" "a"] (keys (.getMetadata MQ))))
    (is (= "test" (.getQueryString MQ)))
    (is (= "text:test" (.toString (.getQuery MQ)))))

  (testing "specifying analyzer changes what query is constructed"
    (let [mq {:id            "1"
              :query         "test"
              :default-field "text"
              :analyzer      {:token-filters [:reverseString]}}
          MQ (q/->monitor-query mq)]
      (is (= "test" (.getQueryString MQ)))
      (is (= "text:tset" (.toString (.getQuery MQ))))))

  (testing "priority of analyzers"
    (let [mq {:query         "test"
              :default-field "text"
              :query-parser  {:analyzer {:token-filters [:uppercase]}}
              :analyzer      {:token-filters [:reverseString]}}
          MQ (q/->monitor-query mq)]
      (is (= "text:TEST" (.toString (.getQuery MQ))))))

  (testing "only analyzer for query parsing"
    (let [mq {:query         "test"
              :default-field "text"
              :query-parser  {:analyzer {:token-filters [:uppercase]}}}
          MQ (q/->monitor-query mq)]
      (is (= "text:TEST" (.toString (.getQuery MQ))))))

  (testing "default query analyzer handling"
    (let [mq {:query         "test"
              :default-field "text"}
          default-query-analyzer (a/create {:token-filters [:reverseString]})
          MQ (q/->monitor-query mq  default-query-analyzer)]
      (is (= "text:tset" (.toString (.getQuery MQ))))))

  (testing "default query analyzer is of lower priority than query parser analyzer"
    (let [mq {:query         "test"
              :default-field "text"
              :query-parser  {:analyzer {:token-filters [:uppercase]}}}
          default-query-analyzer (a/create {:token-filters [:reverseString]})
          MQ (q/->monitor-query mq default-query-analyzer)]
      (is (= "text:TEST" (.toString (.getQuery MQ))))))

  (testing "default query analyzer is of higher priority than query analyzer"
    (let [mq {:query         "test"
              :default-field "text"
              :analyzer      {:token-filters [:uppercase]}}
          default-query-analyzer (a/create {:token-filters [:reverseString]})
          MQ (q/->monitor-query mq default-query-analyzer)]
      (is (= "text:TEST" (.toString (.getQuery MQ))))))

  (testing "query id can be"
    (let [mq {:query "test"}]
      (is (= nil (.getId (q/->monitor-query mq))))))
  (testing "query cant be nil?"
    (let [mq {:query         nil
              :default-field "text"}]
      (is (thrown? Exception (q/->monitor-query mq)))))
  (testing "query cant be empty string"
    (let [mq {:query         ""
              :default-field "text"}]
      (is (thrown? Exception (q/->monitor-query mq)))))
  (testing "can query by nil?"
    (let [mq {:query "test"}]
      (is (thrown? Exception (.toString (.getQuery (q/->monitor-query mq))))))))
