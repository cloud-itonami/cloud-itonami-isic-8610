(ns hospital.registry-test
  (:require [clojure.test :refer [deftest is]]
            [hospital.registry :as r]))

;; ----------------------------- observation-period-elapsed? -----------------------------

(deftest observation-period-elapsed-when-at-or-above-minimum
  (is (r/observation-period-elapsed? {:hours-since-procedure r/minimum-observation-hours}))
  (is (r/observation-period-elapsed? {:hours-since-procedure (+ r/minimum-observation-hours 1)}))
  (is (not (r/observation-period-elapsed? {:hours-since-procedure (- r/minimum-observation-hours 1)}))))

(deftest observation-period-not-elapsed-when-missing
  (is (not (r/observation-period-elapsed? {}))))

;; ----------------------------- register-treatment-administration -----------------------------

(deftest treatment-administration-is-a-draft-not-a-real-administration
  (let [result (r/register-treatment-administration "admission-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest treatment-administration-assigns-administration-number
  (let [result (r/register-treatment-administration "admission-1" "JPN" 7)]
    (is (= (get result "administration_number") "JPN-TRT-000007"))
    (is (= (get-in result ["record" "admission_id"]) "admission-1"))
    (is (= (get-in result ["record" "kind"]) "treatment-administration-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest treatment-administration-validation-rules
  (is (thrown? Exception (r/register-treatment-administration "" "JPN" 0)))
  (is (thrown? Exception (r/register-treatment-administration "admission-1" "" 0)))
  (is (thrown? Exception (r/register-treatment-administration "admission-1" "JPN" -1))))

(deftest treatment-history-is-append-only
  (let [c1 (r/register-treatment-administration "admission-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-treatment-administration "admission-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TRT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TRT-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-discharge-authorization -----------------------------

(deftest discharge-authorization-is-a-draft-not-a-real-discharge
  (let [result (r/register-discharge-authorization "admission-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest discharge-authorization-assigns-discharge-number
  (let [result (r/register-discharge-authorization "admission-1" "JPN" 7)]
    (is (= (get result "discharge_number") "JPN-DIS-000007"))
    (is (= (get-in result ["record" "admission_id"]) "admission-1"))
    (is (= (get-in result ["record" "kind"]) "discharge-authorization-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest discharge-authorization-validation-rules
  (is (thrown? Exception (r/register-discharge-authorization "" "JPN" 0)))
  (is (thrown? Exception (r/register-discharge-authorization "admission-1" "" 0)))
  (is (thrown? Exception (r/register-discharge-authorization "admission-1" "JPN" -1))))

(deftest discharge-history-is-append-only
  (let [d1 (r/register-discharge-authorization "admission-1" "JPN" 0)
        hist (r/append [] d1)
        d2 (r/register-discharge-authorization "admission-2" "JPN" 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-DIS-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-DIS-000001" (get-in hist2 [1 "record_id"])))))
