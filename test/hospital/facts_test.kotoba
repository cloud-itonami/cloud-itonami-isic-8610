(ns hospital.facts-test
  (:require [clojure.test :refer [deftest is]]
            [hospital.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))

(deftest nzl-has-a-spec-basis
  (is (some? (facts/spec-basis "NZL")))
  (is (string? (:provenance (facts/spec-basis "NZL")))))

(deftest nzl-spec-basis-matches-catalog-shape
  (let [{:keys [name owner-authority legal-basis national-spec provenance required-evidence]}
        (facts/spec-basis "NZL")]
    (is (= "New Zealand" name))
    (is (string? owner-authority))
    (is (re-find #"Health and Disability Services \(Safety\) Act 2001" legal-basis))
    (is (re-find #"NZS 8134:2021" national-spec))
    (is (string? provenance))
    (is (= 4 (count required-evidence)))))

(deftest coverage-reports-nzl-as-covered
  (let [report (facts/coverage ["NZL" "ATL"])]
    (is (= 1 (:covered report)))
    (is (= ["NZL"] (:covered-jurisdictions report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))))

(deftest nzl-required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "NZL")]
    (is (facts/required-evidence-satisfied? "NZL" all))
    (is (not (facts/required-evidence-satisfied? "NZL" (rest all))))))
