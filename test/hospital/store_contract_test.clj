(ns hospital.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [hospital.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tanaka" (:patient-name (store/admission s "admission-1"))))
      (is (= "JPN" (:jurisdiction (store/admission s "admission-1"))))
      (is (= 6 (:hours-since-procedure (store/admission s "admission-1"))))
      (is (true? (:clinician-license-current? (store/admission s "admission-1"))))
      (is (= 1 (:hours-since-procedure (store/admission s "admission-3"))))
      (is (false? (:clinician-license-current? (store/admission s "admission-4"))))
      (is (false? (:treated? (store/admission s "admission-1"))))
      (is (false? (:discharged? (store/admission s "admission-1"))))
      (is (= ["admission-1" "admission-2" "admission-3" "admission-4"]
             (mapv :id (store/all-admissions s))))
      (is (nil? (store/credential-of s "admission-1")))
      (is (nil? (store/assessment-of s "admission-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/treatment-history s)))
      (is (= [] (store/discharge-history s)))
      (is (zero? (store/next-treatment-sequence s "JPN")))
      (is (zero? (store/next-discharge-sequence s "JPN")))
      (is (false? (store/admission-already-treated? s "admission-1")))
      (is (false? (store/admission-already-discharged? s "admission-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :admission/upsert
                                 :value {:id "admission-1" :patient-name "Sakura Tanaka"}})
        (is (= "Sakura Tanaka" (:patient-name (store/admission s "admission-1"))))
        (is (= 6 (:hours-since-procedure (store/admission s "admission-1"))) "unrelated field preserved"))
      (testing "assessment / credential-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["admission-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "admission-1")))
        (store/commit-record! s {:effect :credential-screening/set :path ["admission-1"]
                                 :payload {:admission-id "admission-1" :verdict :current}})
        (is (= {:admission-id "admission-1" :verdict :current} (store/credential-of s "admission-1"))))
      (testing "treatment administration drafts an administration record and advances the sequence"
        (store/commit-record! s {:effect :admission/mark-treated :path ["admission-1"]})
        (is (= "JPN-TRT-000000" (get (first (store/treatment-history s)) "record_id")))
        (is (= "treatment-administration-draft" (get (first (store/treatment-history s)) "kind")))
        (is (true? (:treated? (store/admission s "admission-1"))))
        (is (= 1 (count (store/treatment-history s))))
        (is (= 1 (store/next-treatment-sequence s "JPN")))
        (is (true? (store/admission-already-treated? s "admission-1")))
        (is (false? (store/admission-already-treated? s "admission-2"))))
      (testing "discharge authorization drafts a discharge record and advances the sequence"
        (store/commit-record! s {:effect :admission/mark-discharged :path ["admission-1"]})
        (is (= "JPN-DIS-000000" (get (first (store/discharge-history s)) "record_id")))
        (is (= "discharge-authorization-draft" (get (first (store/discharge-history s)) "kind")))
        (is (true? (:discharged? (store/admission s "admission-1"))))
        (is (= 1 (count (store/discharge-history s))))
        (is (= 1 (store/next-discharge-sequence s "JPN")))
        (is (true? (store/admission-already-discharged? s "admission-1")))
        (is (false? (store/admission-already-discharged? s "admission-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/admission s "nope")))
    (is (= [] (store/all-admissions s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/treatment-history s)))
    (is (= [] (store/discharge-history s)))
    (is (zero? (store/next-treatment-sequence s "JPN")))
    (is (zero? (store/next-discharge-sequence s "JPN")))
    (store/with-admissions s {"x" {:id "x" :patient-name "n" :hours-since-procedure 6
                                   :clinician-license-current? true :treated? false
                                   :discharged? false :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:patient-name (store/admission s "x"))))))
