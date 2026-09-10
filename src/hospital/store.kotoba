(ns hospital.store
  "SSoT for the hospital actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/hospital/store_contract_test.clj), which is the whole point:
  the actor, the Clinical Oversight Governor and the audit ledger
  never know which SSoT they run on.

  Like `marketadmin.store`'s dual admission/halt-lift history,
  `registrar.store`'s dual grade/degree history, `wagering.store`'s
  dual acceptance/settlement history, `repairshop.store`'s dual
  completion/return history, `eldercare.store`'s dual care-plan/
  incident-response-finalization history, `museum.store`'s dual loan/
  deaccession history, `conservation.store`'s dual transfer/release
  history and `casework.store`'s dual eligibility/referral history,
  this actor has TWO actuation events (administering a treatment,
  authorizing a discharge) acting on the SAME entity (an inpatient
  admission), each with its OWN history collection, sequence counter
  and dedicated double-actuation-guard boolean (`:treated?`/
  `:discharged?`, never a `:status` value) -- the same discipline
  `accounting.governor`'s/`marketadmin.governor`'s/`testlab.
  governor`'s/`clinic.governor`'s/`registrar.governor`'s/`wagering.
  governor`'s/`veterinary.governor`'s/`funeral.governor`'s/
  `repairshop.governor`'s/`parksafety.governor`'s/`eldercare.
  governor`'s/`museum.governor`'s/`conservation.governor`'s/
  `casework.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which admission was
  screened for a current clinician license, which treatment was
  administered, which discharge was authorized, on what jurisdictional
  basis, approved by whom' is always a query over an immutable log --
  the audit trail a patient trusting a hospital needs, and the
  evidence an operator needs if a treatment or discharge is later
  disputed."
  (:require [hospital.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (admission [s id])
  (all-admissions [s])
  (credential-of [s admission-id] "committed credential screening verdict for an admission, or nil")
  (assessment-of [s admission-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (treatment-history [s] "the append-only treatment-administration history (hospital.registry drafts)")
  (discharge-history [s] "the append-only discharge-authorization history (hospital.registry drafts)")
  (next-treatment-sequence [s jurisdiction] "next treatment-administration-number sequence for a jurisdiction")
  (next-discharge-sequence [s jurisdiction] "next discharge-authorization-number sequence for a jurisdiction")
  (admission-already-treated? [s admission-id] "has this admission's treatment already been administered?")
  (admission-already-discharged? [s admission-id] "has this admission already been discharged?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-admissions [s admissions] "replace/seed the admission directory (map id->admission)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained admission set covering both actuation
  lifecycles (administering a treatment, authorizing a discharge) so
  the actor + tests run offline."
  []
  {:admissions
   {"admission-1" {:id "admission-1" :patient-name "Sakura Tanaka"
                   :hours-since-procedure 6 :clinician-license-current? true
                   :treated? false :discharged? false
                   :jurisdiction "JPN" :status :intake}
    "admission-2" {:id "admission-2" :patient-name "Atlantis Doe"
                   :hours-since-procedure 6 :clinician-license-current? true
                   :treated? false :discharged? false
                   :jurisdiction "ATL" :status :intake}
    "admission-3" {:id "admission-3" :patient-name "鈴木一郎"
                   :hours-since-procedure 1 :clinician-license-current? true
                   :treated? false :discharged? false
                   :jurisdiction "JPN" :status :intake}
    "admission-4" {:id "admission-4" :patient-name "田中花子"
                   :hours-since-procedure 6 :clinician-license-current? false
                   :treated? false :discharged? false
                   :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-treatment!
  "Backend-agnostic `:admission/mark-treated` -- looks up the
  admission via the protocol and drafts the treatment-administration
  record, and returns {:result .. :admission-patch ..} for the caller
  to persist."
  [s admission-id]
  (let [a (admission s admission-id)
        seq-n (next-treatment-sequence s (:jurisdiction a))
        result (registry/register-treatment-administration admission-id (:jurisdiction a) seq-n)]
    {:result result
     :admission-patch {:treated? true
                       :administration-number (get result "administration_number")}}))

(defn- finalize-discharge!
  "Backend-agnostic `:admission/mark-discharged` -- looks up the
  admission via the protocol and drafts the discharge-authorization
  record, and returns {:result .. :admission-patch ..} for the caller
  to persist."
  [s admission-id]
  (let [a (admission s admission-id)
        seq-n (next-discharge-sequence s (:jurisdiction a))
        result (registry/register-discharge-authorization admission-id (:jurisdiction a) seq-n)]
    {:result result
     :admission-patch {:discharged? true
                       :discharge-number (get result "discharge_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (admission [_ id] (get-in @a [:admissions id]))
  (all-admissions [_] (sort-by :id (vals (:admissions @a))))
  (credential-of [_ id] (get-in @a [:credentials id]))
  (assessment-of [_ admission-id] (get-in @a [:assessments admission-id]))
  (ledger [_] (:ledger @a))
  (treatment-history [_] (:treatments @a))
  (discharge-history [_] (:discharges @a))
  (next-treatment-sequence [_ jurisdiction] (get-in @a [:treatment-sequences jurisdiction] 0))
  (next-discharge-sequence [_ jurisdiction] (get-in @a [:discharge-sequences jurisdiction] 0))
  (admission-already-treated? [_ admission-id] (boolean (get-in @a [:admissions admission-id :treated?])))
  (admission-already-discharged? [_ admission-id] (boolean (get-in @a [:admissions admission-id :discharged?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :admission/upsert
      (swap! a update-in [:admissions (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :credential-screening/set
      (swap! a assoc-in [:credentials (first path)] payload)

      :admission/mark-treated
      (let [admission-id (first path)
            {:keys [result admission-patch]} (finalize-treatment! s admission-id)
            jurisdiction (:jurisdiction (admission s admission-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:treatment-sequences jurisdiction] (fnil inc 0))
                       (update-in [:admissions admission-id] merge admission-patch)
                       (update :treatments registry/append result))))
        result)

      :admission/mark-discharged
      (let [admission-id (first path)
            {:keys [result admission-patch]} (finalize-discharge! s admission-id)
            jurisdiction (:jurisdiction (admission s admission-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:discharge-sequences jurisdiction] (fnil inc 0))
                       (update-in [:admissions admission-id] merge admission-patch)
                       (update :discharges registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-admissions [s admissions] (when (seq admissions) (swap! a assoc :admissions admissions)) s))

(defn seed-db
  "A MemStore seeded with the demo admission set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :credentials {} :ledger [] :treatment-sequences {}
                           :treatments [] :discharge-sequences {} :discharges []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/credential payloads, ledger facts,
  treatment/discharge records) are stored as EDN strings so
  `langchain.db` doesn't expand them into sub-entities -- the same
  convention every sibling actor's store uses. The identity-schema
  builder, EDN-blob codec and seq-keyed event-log read/append are the
  shared kotoba-lang/langchain-store machinery (ADR-2607141600) -- the
  seam ~190 actors hand-roll; this store keeps only its domain wiring."
  (ls/identity-schema
   [:admission/id :assessment/admission-id :credential-screening/admission-id
    :ledger/seq :treatment/seq :discharge/seq
    :treatment-sequence/jurisdiction :discharge-sequence/jurisdiction]))

(defn- admission->tx [{:keys [id patient-name hours-since-procedure clinician-license-current?
                              treated? discharged?
                              jurisdiction status administration-number discharge-number]}]
  (cond-> {:admission/id id}
    patient-name                        (assoc :admission/patient-name patient-name)
    hours-since-procedure               (assoc :admission/hours-since-procedure hours-since-procedure)
    (some? clinician-license-current?)  (assoc :admission/clinician-license-current? clinician-license-current?)
    (some? treated?)                    (assoc :admission/treated? treated?)
    (some? discharged?)                 (assoc :admission/discharged? discharged?)
    jurisdiction                        (assoc :admission/jurisdiction jurisdiction)
    status                              (assoc :admission/status status)
    administration-number               (assoc :admission/administration-number administration-number)
    discharge-number                    (assoc :admission/discharge-number discharge-number)))

(def ^:private admission-pull
  [:admission/id :admission/patient-name :admission/hours-since-procedure
   :admission/clinician-license-current? :admission/treated? :admission/discharged?
   :admission/jurisdiction :admission/status :admission/administration-number :admission/discharge-number])

(defn- pull->admission [m]
  (when (:admission/id m)
    {:id (:admission/id m) :patient-name (:admission/patient-name m)
     :hours-since-procedure (:admission/hours-since-procedure m)
     :clinician-license-current? (boolean (:admission/clinician-license-current? m))
     :treated? (boolean (:admission/treated? m))
     :discharged? (boolean (:admission/discharged? m))
     :jurisdiction (:admission/jurisdiction m) :status (:admission/status m)
     :administration-number (:admission/administration-number m) :discharge-number (:admission/discharge-number m)}))

(defrecord DatomicStore [conn]
  Store
  (admission [_ id]
    (pull->admission (d/pull (d/db conn) admission-pull [:admission/id id])))
  (all-admissions [_]
    (->> (d/q '[:find [?id ...] :where [?e :admission/id ?id]] (d/db conn))
         (map #(pull->admission (d/pull (d/db conn) admission-pull [:admission/id %])))
         (sort-by :id)))
  (credential-of [_ id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?k :credential-screening/admission-id ?aid] [?k :credential-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ admission-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?aid
                :where [?a :assessment/admission-id ?aid] [?a :assessment/payload ?p]]
              (d/db conn) admission-id)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (treatment-history [_] (ls/read-stream conn :treatment/seq :treatment/record))
  (discharge-history [_] (ls/read-stream conn :discharge/seq :discharge/record))
  (next-treatment-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :treatment-sequence/jurisdiction ?j] [?e :treatment-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-discharge-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :discharge-sequence/jurisdiction ?j] [?e :discharge-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (admission-already-treated? [s admission-id]
    (boolean (:treated? (admission s admission-id))))
  (admission-already-discharged? [s admission-id]
    (boolean (:discharged? (admission s admission-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :admission/upsert
      (d/transact! conn [(admission->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/admission-id (first path) :assessment/payload (ls/enc payload)}])

      :credential-screening/set
      (d/transact! conn [{:credential-screening/admission-id (first path) :credential-screening/payload (ls/enc payload)}])

      :admission/mark-treated
      (let [admission-id (first path)
            {:keys [result admission-patch]} (finalize-treatment! s admission-id)
            jurisdiction (:jurisdiction (admission s admission-id))
            next-n (inc (next-treatment-sequence s jurisdiction))]
        (d/transact! conn
                     [(admission->tx (assoc admission-patch :id admission-id))
                      {:treatment-sequence/jurisdiction jurisdiction :treatment-sequence/next next-n}
                      {:treatment/seq (count (treatment-history s)) :treatment/record (ls/enc (get result "record"))}])
        result)

      :admission/mark-discharged
      (let [admission-id (first path)
            {:keys [result admission-patch]} (finalize-discharge! s admission-id)
            jurisdiction (:jurisdiction (admission s admission-id))
            next-n (inc (next-discharge-sequence s jurisdiction))]
        (d/transact! conn
                     [(admission->tx (assoc admission-patch :id admission-id))
                      {:discharge-sequence/jurisdiction jurisdiction :discharge-sequence/next next-n}
                      {:discharge/seq (count (discharge-history s)) :discharge/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (count (ledger s)) fact)
    fact)
  (with-admissions [s admissions]
    (when (seq admissions) (d/transact! conn (mapv admission->tx (vals admissions)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:admissions ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [admissions]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-admissions s admissions))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo admission set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
