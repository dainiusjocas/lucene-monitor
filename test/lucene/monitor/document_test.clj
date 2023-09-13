(ns lucene.monitor.document-test
  (:require [clojure.test :refer [deftest is testing]]
            [lucene.monitor.document :as doc])
  (:import (org.apache.lucene.document Document Field)))


(defn get-field-names [^Document d]
  (mapv #(.name ^Field %) (.getFields d)))


(deftest map->doc
  (let [m {:a {:b {:c "d"}}}
        d (doc/nested->doc m)]
    (is (= ["a.b.c"] (get-field-names d))))

  (testing "vector with a string and a map"
    (let [m {:a     {:b {:string "d"}}
             :long  1234
             :float 12.23
             :array ["a" {"b" "qq"}]}
          d (doc/nested->doc m)]
      (is (= ["a.b.string"
              "long"
              "float"
              "array"
              "array.b"]
             (get-field-names d)))))

  (testing "vector with two maps"
    (let [m {:a     {:b {:string "d"}}
             :long  1234
             :float 12.23
             :array [{"a" "aa"} {"a" "aaa"}]}
          d (doc/nested->doc m)]
      (is (= ["a.b.string"
              "long"
              "float"
              "array.a"
              "array.a"]
             (get-field-names d)))
      (is (= 2 (count (.getFields d "array.a"))))
      (is (= ["aa" "aaa"] (mapv #(.stringValue %) (.getFields d "array.a")))))))
