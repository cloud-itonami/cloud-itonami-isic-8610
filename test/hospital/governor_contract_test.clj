(ns hospital.governor-contract-test
  "The governor contract as executable tests -- the hospital analog
  of `cloud-itonami-isic-6512`'s `casualty.governor-contract-test`.
  The single invariant under test:

    HospitalOps-LLM never administers a treatment or authorizes a
    discharge the Clinical Oversight Governor would reject,
    `:treatment/administer`/`:discharge/authorize` NEVER auto-commit
    at any phase, `:admission/intake` (no direct capital risk) MAY
    auto-commit when clean, and every decision (commit OR hold) leaves
    exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [hospital.store :as store]
            [hospital.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :clinician :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through credential screening -> approve, leaving a
  screening on file. Only safe to call for an admission whose license
  is already current -- a not-current license HARD-holds the screen
  itself (see `credential-not-current-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :credential/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :admission/intake :subject "admission-1"
                   :patch {:id "admission-1" :patient-name "Sakura Tanaka"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Tanaka" (:patient-name (store/admission db "admission-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "admission-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "admission-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "admission-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "admission-1")) "no assessment written"))))

(deftest treatment-administer-without-assessment-is-held
  (testing "treatment/administer before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :treatment/administer :subject "admission-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest observation-period-insufficient-is-held
  (testing "an admission whose hours-since-procedure falls below the minimum-observation-hours ceiling -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "admission-3")
          res (exec-op actor "t5" {:op :discharge/authorize :subject "admission-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:observation-period-insufficient} (-> (store/ledger db) last :basis)))
      (is (empty? (store/discharge-history db))))))

(deftest credential-not-current-is-held-and-unoverridable
  (testing "a not-current clinician license on an admission -> HOLD, and never reaches request-approval -- exercised via :credential/screen DIRECTLY, not via an actuation op against an unscreened admission (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's, salon's, entertainment's and casework's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :credential/screen :subject "admission-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:credential-not-current} (-> (store/ledger db) first :basis)))
      (is (nil? (store/credential-of db "admission-4")) "no clearance written"))))

(deftest treatment-administer-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, license-current admission still ALWAYS interrupts for human approval -- actuation/administer-treatment is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "admission-1")
          _ (screen! actor "t7pre2" "admission-1")
          r1 (exec-op actor "t7" {:op :treatment/administer :subject "admission-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, administration record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:treated? (store/admission db "admission-1"))))
          (is (= 1 (count (store/treatment-history db))) "one draft administration record"))))))

(deftest discharge-authorize-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, observation-sufficient, license-current admission still ALWAYS interrupts for human approval -- actuation/authorize-discharge is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "admission-1")
          _ (screen! actor "t8pre2" "admission-1")
          r1 (exec-op actor "t8" {:op :discharge/authorize :subject "admission-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, discharge record drafted"
        (let [r2 (approve! actor "t8")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:discharged? (store/admission db "admission-1"))))
          (is (= 1 (count (store/discharge-history db))) "one draft discharge record"))))))

(deftest treatment-administer-double-administration-is-held
  (testing "administering a treatment to the same admission twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "admission-1")
          _ (screen! actor "t9pre2" "admission-1")
          _ (exec-op actor "t9a" {:op :treatment/administer :subject "admission-1"} operator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :treatment/administer :subject "admission-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-treated} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/treatment-history db))) "still only the one earlier administration"))))

(deftest discharge-authorize-double-discharge-is-held
  (testing "authorizing a discharge for the same admission twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "admission-1")
          _ (screen! actor "t10pre2" "admission-1")
          _ (exec-op actor "t10a" {:op :discharge/authorize :subject "admission-1"} operator)
          _ (approve! actor "t10a")
          res (exec-op actor "t10" {:op :discharge/authorize :subject "admission-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-discharged} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/discharge-history db))) "still only the one earlier discharge"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :admission/intake :subject "admission-1"
                          :patch {:id "admission-1" :patient-name "Sakura Tanaka"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "admission-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
