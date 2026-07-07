(ns hospital.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:treatment/administer`/`:discharge/authorize` must
  NEVER be a member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [hospital.phase :as phase]))

(deftest treatment-administer-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real treatment administration"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :treatment/administer))
          (str "phase " n " must not auto-commit :treatment/administer")))))

(deftest discharge-authorize-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-commits a real discharge authorization"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :discharge/authorize))
          (str "phase " n " must not auto-commit :discharge/authorize")))))

(deftest credential-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety-test/inspection/incident-flag/welfare-flag/allergy-flag/rights-clearance/risk-flag screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :credential/screen))
          (str "phase " n " must not auto-commit :credential/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":admission/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:admission/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :admission/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :treatment/administer} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :discharge/authorize} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :admission/intake} :commit)))))
