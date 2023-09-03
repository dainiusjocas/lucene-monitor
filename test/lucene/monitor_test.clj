(ns lucene.monitor-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [lucene.monitor :as m])
  (:import (java.util HashMap)))

(deftest basics
  (testing "zero arg constructor"
    (with-open [monitor (m/monitor)]
      (m/register monitor {:id "12" :query "text"})
      (is (= [{:id "12"}] (m/match-string monitor "foo text bar")))
      (is (= {:id            "12"
              :query         "text"
              :default-field "text"}
             (m/get-query monitor "12")))
      (is (= 1 (m/get-disjunct-count monitor)))
      (is (= #{"12"} (m/get-query-ids monitor)))))

  (let [options {}
        queries [{:id    "12"
                  :query "text"
                  :meta  {"a" "b"}}]
        txt "foo text bar"]
    (with-open [monitor (m/monitor options queries)]
      (is (= [{:id "12"}]
             (m/match-string monitor txt)))
      (is (= {:id            "12"
              :query         "text"
              :default-field "text"
              :meta          {:a "b"}}
             (m/get-query monitor "12")))
      (is (= 1 (m/get-disjunct-count monitor)))
      (is (= #{"12"} (m/get-query-ids monitor))))

    (testing "adding queries"
      (with-open [monitor (m/monitor options [])]
        (is (= [] (m/match-string monitor txt)))
        (m/register monitor queries)
        (is (= [{:id "12"}]
               (m/match-string monitor txt)))))

    (testing "matching multiple docs"
      (with-open [monitor (m/monitor options queries)]
        (is (= [[{:id "12"}]
                [{:id "12"}]]
               (m/match monitor [{:text "foo text bar"}
                                 {:text "foo text bar"}] {})))))

    (testing "doc as a java HashMap"
      (with-open [monitor (m/monitor {} [{:id    "12"
                                          :query "text"
                                          :meta  {"a" "b"}}])]
        (is (= [[{:id "12"}]]
               (m/match monitor
                        (doto (HashMap.) (.put :text "foo text bar"))
                        {})))))


    (testing "removing queries one by one and in bulk"
      (with-open [monitor (m/monitor options [])]
        (is (= [] (m/match-string monitor txt)))
        (is (= 0 (m/get-query-count monitor)))
        (m/register monitor queries)
        (is (= [{:id "12"}]
               (m/match-string monitor txt)))
        (is (= 1 (m/get-query-count monitor)))
        (m/delete-by-id monitor "12")
        (is (= 0 (m/get-query-count monitor)))
        (is (= [] (m/match-string monitor txt)))
        (m/register monitor queries)
        (is (= 1 (m/get-query-count monitor)))
        (is (= [{:id "12"}]
               (m/match-string monitor txt)))
        (m/delete-by-id monitor ["12"])
        (is (= 0 (m/get-query-count monitor)))
        (is (= [] (m/match-string monitor txt)))))

    (testing "specifying an analyzer for the query"
      (with-open [monitor (m/monitor)]
        (m/register monitor [{:id "1" :query "test"}])
        (is (= [] (m/match-string monitor "MY TEST TEXT")))
        (m/register monitor [{:id       "1"
                              :query    "test"
                              :analyzer {:token-filters [:lowercase]}}])
        (is (= [{:id "1"}] (m/match-string monitor "MY TEST TEXT")))))

    (testing "specifying an analyzer for the query with other default field"
      (let [doc {:foo "MY TEST TEXT"}]
        (with-open [monitor (m/monitor)]
          (m/register monitor [{:id "1" :query "test" :default-field "foo"}])
          (is (= [] (m/match monitor doc)))
          (m/register monitor [{:id            "1"
                                :query         "test"
                                :default-field "foo"
                                :analyzer      {:token-filters [:lowercase]}}])
          (is (= [{:id "1"}] (m/match monitor doc))))))

    (testing "specifying the default field"
      (let [options {:default-field "foo"}
            queries [{:id "1" :query "test"}
                     {:id "2" :query "test" :default-field "bar"}]]
        (with-open [monitor (m/monitor options queries)]
          (is (= [{:id "1"}] (m/match monitor {:foo "prefix test suffix"})))
          (m/register monitor [{:id "2" :query "test"}])
          (is (= [{:id "1"} {:id "2"}] (m/match monitor {:foo "prefix test suffix"}))))))

    (testing "query spanning multiple fields"
      (let [queries [{:id    "1"
                      :query "field-a:foo AND field-b:bar"}]]
        (with-open [monitor (m/monitor options queries)]
          (is (= [{:id "1"}]
                 (m/match monitor {:field-a "prefix foo suffix"
                                   :field-b "prefix bar suffix"})))
          (is (= [] (m/match monitor {:field-a "prefix foo suffix"}))))))

    (testing "details collection for one doc"
      (with-open [monitor (m/monitor options queries)]
        (let [resp (m/match monitor {:text "foo text bar"} {})
              resp-with-details (m/match monitor
                                         {:text "foo text bar"}
                                         {:with-details true})]
          (is (= [{:id "12"}] resp))
          (is (empty? (meta resp)))
          (is (= [{:id "12"}] resp-with-details))
          (is (= #{:batch-size :errors :queries-run :query-build-time-ns :search-time-ms}
                 (set (keys (meta resp-with-details))))))))
    (testing "with details and mode :count should not throw exception"
      (with-open [monitor (m/monitor options queries)]
        (let [resp (m/match monitor {:text "foo text bar"} {})
              resp-with-details (m/match monitor
                                         {:text "foo text bar"}
                                         {:with-details true
                                          :mode :count})]
          (is (= [{:id "12"}] resp))
          (is (empty? (meta resp)))
          (is (= 1 resp-with-details))
          ; int doesn't support metadata
          (is (nil? (meta resp-with-details))))))
    (testing "details collection for batch"
      (with-open [monitor (m/monitor options queries)]
        (let [resp (m/match monitor [{:text "foo text bar"}
                                     {:text "foo text bar"}] {})
              resp-with-details (m/match monitor
                                         [{:text "foo text bar"}
                                          {:text "foo text bar"}]
                                         {:with-details true})]
          (is (= [[{:id "12"}] [{:id "12"}]] resp))
          (is (empty? (meta resp)))
          (is (= [[{:id "12"}] [{:id "12"}]] resp-with-details))
          (is (= #{:batch-size :errors :queries-run :query-build-time-ns :search-time-ms}
                 (set (keys (meta resp-with-details))))))))
    (testing "registering queries in batches"
      (with-open [monitor (m/monitor)]
        (is (empty? (m/match monitor {:text "foo text bar"})))
        (m/register monitor [{:id "1" :query "test"}
                             {:id "2" :query "test"}] {:batch-size 1})))))

(deftest schema-support
  ; Monitor has schema for one field
  ; Schema is only for fields
  ; This field is indexed with tokens reversed
  ; query whose tokens are not reversed should not match
  ; query whose tokens are reversed should match

  (testing "field analyzer from schema must match query analyzer"
    (let [query-string "test"
          reversing-analyzer {:analyzer {:token-filters [:reverseString]}}
          opts {:default-field :my-field-name
                :schema        {:my-field-name reversing-analyzer}}
          queries [{:id    "1"
                    :query query-string}
                   {:id           "2"
                    :query        query-string
                    :query-parser reversing-analyzer}]
          doc {:my-field-name "prefix test suffix"}]
      (with-open [monitor (m/monitor opts queries)]
        (is (= [{:id "2"}] (m/match monitor doc {}))))))

  (testing "multifield query case"
    (let [query-string "field-a:foo AND field-b:bar"
          reversing-analyzer {:analyzer {:token-filters [:reverseString]}}
          opts {:default-field "text"
                :schema        {:field-a reversing-analyzer
                                :field-b reversing-analyzer}}
          queries [{:id           "1"
                    :query        query-string
                    :query-parser {}}
                   {:id           "2"
                    :query        query-string
                    :query-parser reversing-analyzer}]
          doc {:field-a "prefix foo suffix"
               :field-b "prefix bar suffix"}]
      (with-open [monitor (m/monitor opts queries)]
        (is (= [{:id "2"}] (m/match monitor doc {})))))))

(deftest matching-mode
  (with-open [monitor (m/monitor {} [{:id "1" :query "test"}])]
    (is (= [{:id "1"}] (m/match-string monitor "foo test bar")))
    (is (= [{:id "1"}] (m/match-string monitor "foo test bar" {:mode :id})))
    (is (= 1 (m/match-string monitor "foo test bar" {:mode :count})))
    (is (= [[:id :score]] (mapv keys (m/match-string monitor "foo test bar" {:mode :score}))))
    (let [matches (m/match-string monitor "foo test bar" {:mode :highlight})]
      (is (= [[:id :highlights]] (mapv keys matches)))
      (is (= [{:highlights {"text" [{:end-offset     8
                                     :end-position   1
                                     :start-offset   4
                                     :start-position 1}]}
               :id         "1"}] matches))))

  (testing "match-string with query default field being specified"
    (with-open [monitor (m/monitor {} [{:id "1" :query "test" :default-field "foo"}])]
      (is (= [] (m/match-string monitor "prefix test suffix" {})))))

  (testing "default matching mode"
    (with-open [monitor (m/monitor {:default-match-mode :highlight} [{:id "1" :query "test"}])]
      (is (= [{:id         "1"
               :highlights {"text" [{:start-position 1
                                     :end-position   1
                                     :start-offset   7
                                     :end-offset     11}]}}]
             (m/match-string monitor "prefix test suffix")))))


  (testing "phrase with highlights"
    (with-open [monitor (m/monitor {} [{:id "1" :query "\"my test\""}])]
      (is (= [] (m/match-string monitor "prefix test suffix")))
      (is (= [{:highlights {"text" [{:end-offset     14
                                     :end-position   2
                                     :start-offset   7
                                     :start-position 1}]}
               :id         "1"}]
             (m/match-string monitor "prefix my test suffix" {:mode :highlight}))))))

(def dir "target/fs-support-test6")

(defn clean-index-files-fixture [f]
  (fs/delete-tree dir)
  (when-not (fs/exists? "target")
    (fs/create-dir "target"))
  (try
    (f)
    (catch Exception e
      (println e)
      (fs/delete-tree dir))))

(use-fixtures :each clean-index-files-fixture)

(deftest fs-support
  (let [options {:index-path dir}
        queries [{:id    "12"
                  :query "text"
                  :meta  {"a" "b"}}]]
    (testing "new monitor with queries and an index dir"
      (is (false? (fs/exists? dir)))
      (with-open [monitor (m/monitor options queries)]
        (is (= [{:id "12"}]
               (m/match-string monitor "foo text bar")))))

    (testing "old monitor with queries loaded only from the index dir"
      (is (true? (fs/exists? dir)))
      (with-open [monitor (m/monitor options [])]
        (is (= [{:id "12"}]
               (m/match-string monitor "foo text bar")))))))

(deftest fs-custom-mapping-preserving
  (let [doc {:foo "MY TEST TEXT"}
        options {:index-path dir}]
    (with-open [monitor (m/monitor options)]
      (is (= [] (m/match monitor doc)))
      (m/register monitor [{:id            "1"
                            :query         "test"
                            :default-field "foo"
                            :analyzer      {:token-filters [:lowercase]}}])
      (is (= [{:id "1"}] (m/match monitor doc))))

    (testing "with queries loaded from queries index"
      (with-open [monitor (m/monitor options)]
        (is (= [{:id "1"}] (m/match monitor doc)))
        (is (= 1 (m/get-query-count monitor)))))))
