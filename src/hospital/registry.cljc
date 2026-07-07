(ns hospital.registry
  "Pure-function treatment-administration + discharge-authorization
  record construction -- an append-only hospital book-of-record
  draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a treatment-administration
  or discharge-authorization reference number -- every hospital/
  jurisdiction assigns its own reference format. This namespace does
  NOT invent one; it builds a jurisdiction-scoped sequence number and
  validates the record's required fields, the same honest, non-
  fabricating discipline `hospital.facts` uses.

  `observation-period-elapsed?`/`minimum-observation-hours` reuse
  `veterinary.registry/withdrawal-period-insufficient?`'s/`funeral.
  registry/waiting-period-elapsed?`'s MINIMUM-threshold temporal-
  sufficiency shape for a THIRD domain instance: a minimum time
  interval must fully elapse before an irreversible real-world act may
  proceed. Like `funeral.registry`'s check (and unlike `veterinary.
  registry`'s, which gates on a `:food-producing?` type tag), this
  applies UNCONDITIONALLY to every admission -- every discharge
  requires the SAME minimum post-procedure observation window, so no
  type-tag gate is needed here. `4` hours is a single representative
  figure commonly cited in perioperative/post-procedure recovery-room
  safety guidance (not a procedure-by-procedure/jurisdiction-by-
  jurisdiction survey of every observation-window variant -- see
  `hospital.facts`'s own docstring for the honest scope this makes).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real hospital-information system. It builds the RECORD
  a hospital would keep, not the act of administering the treatment or
  authorizing the discharge itself (that is `hospital.operation`'s
  `:treatment/administer`/`:discharge/authorize`, always human-gated
  -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  hospital's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(def minimum-observation-hours
  "A single representative minimum post-procedure observation window
  before discharge eligibility -- see ns docstring for the honest
  simplification this makes (not a procedure-by-procedure survey of
  every observation-window variant)."
  4)

(defn observation-period-elapsed?
  "Does `admission`'s own `:hours-since-procedure` satisfy `minimum-
  observation-hours`? A pure ground-truth check against the
  admission's own permanent field -- applies UNCONDITIONALLY to every
  admission (every discharge requires the same minimum post-procedure
  observation window, unlike `veterinary.registry/withdrawal-period-
  insufficient?`'s food-producing-only gate)."
  [{:keys [hours-since-procedure]}]
  (and (number? hours-since-procedure) (>= hours-since-procedure minimum-observation-hours)))

(defn register-treatment-administration
  "Validate + construct the TREATMENT-ADMINISTRATION registration
  DRAFT -- the hospital's own legal act of administering a real
  treatment, procedure or prescription to an inpatient. Pure function
  -- does not touch any real hospital-information system; it builds
  the RECORD a hospital would keep. `hospital.governor` independently
  re-verifies the admission's own clinician-credential status, and
  blocks a double-administration of the same admission's treatment,
  before this is ever allowed to commit."
  [admission-id jurisdiction sequence]
  (when-not (and admission-id (not= admission-id ""))
    (throw (ex-info "treatment-administration: admission_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "treatment-administration: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "treatment-administration: sequence must be >= 0" {})))
  (let [administration-number (str (str/upper-case jurisdiction) "-TRT-" (zero-pad sequence 6))
        record {"record_id" administration-number
                "kind" "treatment-administration-draft"
                "admission_id" admission-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "administration_number" administration-number
     "certificate" (unsigned-certificate "TreatmentAdministration" administration-number administration-number)}))

(defn register-discharge-authorization
  "Validate + construct the DISCHARGE-AUTHORIZATION registration DRAFT
  -- the hospital's own legal act of authorizing a real inpatient's
  discharge. Pure function -- does not touch any real hospital-
  information system; it builds the RECORD a hospital would keep.
  `hospital.governor` independently re-verifies the admission's own
  post-procedure observation sufficiency and clinician-credential
  status, and blocks a double-authorization of the same admission's
  discharge, before this is ever allowed to commit."
  [admission-id jurisdiction sequence]
  (when-not (and admission-id (not= admission-id ""))
    (throw (ex-info "discharge-authorization: admission_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "discharge-authorization: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "discharge-authorization: sequence must be >= 0" {})))
  (let [discharge-number (str (str/upper-case jurisdiction) "-DIS-" (zero-pad sequence 6))
        record {"record_id" discharge-number
                "kind" "discharge-authorization-draft"
                "admission_id" admission-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "discharge_number" discharge-number
     "certificate" (unsigned-certificate "DischargeAuthorization" discharge-number discharge-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
