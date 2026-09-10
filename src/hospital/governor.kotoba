(ns hospital.governor
  "Clinical Oversight Governor -- the independent compliance layer
  that earns the HospitalOps-LLM the right to commit. The LLM has no
  notion of jurisdictional hospital-institution licensing law, whether
  an admission's own post-procedure observation window has actually
  elapsed, whether the treating clinician's own license is actually
  current, or when an act stops being a draft and becomes a real-
  world treatment administration or discharge authorization, so this
  MUST be a separate system able to *reject* a proposal and fall back
  to HOLD -- the hospital analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Six checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete clinical evidence, an
  insufficient post-procedure observation period, a not-current
  clinician license, or a double administration/discharge). The
  confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `hospital.phase`: for `:stake :actuation/administer-treatment`/
  `:actuation/authorize-discharge` (a real clinical act) NO phase ever
  allows auto-commit either. Two independent layers agree that
  actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`hospital.
                                       facts`), or invent one? Like
                                       `clinic.governor`'s/`credit.
                                       governor`'s actuation ops,
                                       `:treatment/administer`/
                                       `:discharge/authorize` act
                                       directly on a pre-seeded
                                       admission (see `hospital.
                                       store`'s own docstring) -- there
                                       is no 'admission is missing'
                                       failure mode to guard against
                                       here.
    2. Evidence incomplete         -- for `:treatment/administer`/
                                       `:discharge/authorize`, has the
                                       jurisdiction actually been
                                       assessed with a full clinical-
                                       evidence checklist on file?
    3. Observation period
       insufficient                  -- for `:discharge/authorize`,
                                       INDEPENDENTLY recompute whether
                                       the admission's own `:hours-
                                       since-procedure` satisfies
                                       `hospital.registry/minimum-
                                       observation-hours` (`hospital.
                                       registry/observation-period-
                                       elapsed?`) -- needs no proposal
                                       inspection or stored-verdict
                                       lookup at all. The THIRD
                                       instance of this fleet's
                                       MINIMUM-threshold temporal-
                                       sufficiency shape (`veterinary.
                                       governor/withdrawal-period-
                                       insufficient-violations`
                                       established the first,
                                       `funeral.governor/waiting-
                                       period-not-elapsed-violations`
                                       the second), applied here
                                       UNCONDITIONALLY (every discharge
                                       needs the same minimum
                                       observation window, unlike
                                       veterinary's food-producing-
                                       only gate).
    4. Credential not current      -- reported by THIS proposal itself
                                       (a `:credential/screen` that
                                       just found a lapsed license), or
                                       already on file for the
                                       admission (`:credential/
                                       screen`/either actuation op).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       `marketadmin.governor/
                                       surveillance-flag-unresolved-
                                       violations`/`testlab.governor/
                                       calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations`/`parksafety.
                                       governor/inspection-not-passed-
                                       violations`/`eldercare.governor/
                                       incident-flag-unresolved-
                                       violations`/`museum.governor/
                                       incident-flag-unresolved-
                                       violations`/`conservation.
                                       governor/welfare-flag-
                                       unresolved-violations`/`salon.
                                       governor/allergy-flag-
                                       unresolved-violations`/
                                       `entertainment.governor/rights-
                                       clearance-unresolved-
                                       violations`/`casework.governor/
                                       risk-flag-unresolved-violations`
                                       established -- the SIXTEENTH
                                       distinct application of this
                                       exact discipline, and the THIRD
                                       specifically for the credential-
                                       not-current clinical-licensing
                                       concept (after `clinic`/
                                       `veterinary`). Like the thirteen
                                       most recent siblings' equivalent
                                       checks, this is exercised in
                                       tests/demo via `:credential/
                                       screen` DIRECTLY, not via an
                                       actuation op against an
                                       unscreened admission -- see this
                                       ns's own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:treatment/
                                       administer`/`:discharge/
                                       authorize` (REAL clinical acts)
                                       -> escalate.

  Two more guards, double-administration/double-discharge prevention,
  are enforced but NOT listed as numbered HARD checks above because
  they need no upstream comparison at all -- `already-treated-
  violations`/`already-discharged-violations` refuse to administer a
  treatment/authorize a discharge for the SAME admission twice, off
  dedicated `:treated?`/`:discharged?` facts (never a `:status` value)
  -- the SAME 'check a dedicated boolean, not status' discipline
  `accounting.governor`'s/`marketadmin.governor`'s/`testlab.
  governor`'s/`clinic.governor`'s/`registrar.governor`'s/`wagering.
  governor`'s/`veterinary.governor`'s/`funeral.governor`'s/
  `repairshop.governor`'s/`parksafety.governor`'s/`eldercare.
  governor`'s/`museum.governor`'s/`conservation.governor`'s/
  `casework.governor`'s guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [hospital.facts :as facts]
            [hospital.registry :as registry]
            [hospital.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Administering a real treatment and authorizing a real discharge are
  the two real-world actuation events this actor performs -- a two-
  member set, matching `cloud-itonami-isic-6512`'s/`6622`'s/`6520`'s/
  `6530`'s/`6820`'s/`6920`'s/`6611`'s/`8530`'s/`9200`'s/`9521`'s/
  `8730`'s/`9102`'s/`9103`'s/`8890`'s dual-actuation shape."
  #{:actuation/administer-treatment :actuation/authorize-discharge})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:treatment/administer`/`:discharge/
  authorize`) proposal with no spec-basis citation is a HARD violation
  -- never invent a jurisdiction's hospital-institution licensing
  requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :treatment/administer :discharge/authorize} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:treatment/administer`/`:discharge/authorize`, the
  jurisdiction's required patient-consent/diagnostic/clinician-
  license/discharge-care-plan evidence must actually be satisfied --
  do not trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:treatment/administer :discharge/authorize} op)
    (let [a (store/admission st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction a) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(患者同意記録/診断記録/免許確認記録/退院計画書等)が充足していない状態での提案"}]))))

(defn- observation-period-insufficient-violations
  "For `:discharge/authorize`, INDEPENDENTLY recompute whether the
  admission's own hours-since-procedure satisfies `hospital.registry/
  minimum-observation-hours` via `hospital.registry/observation-
  period-elapsed?` -- needs no proposal inspection or stored-verdict
  lookup at all, since its input is a permanent ground-truth field
  already on the admission."
  [{:keys [op subject]} st]
  (when (= op :discharge/authorize)
    (let [a (store/admission st subject)]
      (when-not (registry/observation-period-elapsed? a)
        [{:rule :observation-period-insufficient
          :detail (str subject " の処置後経過時間(" (:hours-since-procedure a)
                      "時間)が最低観察時間(" registry/minimum-observation-hours
                      "時間)に満たない")}]))))

(defn- credential-not-current-violations
  "A not-current clinician license -- reported by THIS proposal (e.g.
  a `:credential/screen` that itself just found a lapsed license), or
  already on file in the store for the admission (`:credential/
  screen`/either actuation op) -- is a HARD, un-overridable hold.
  Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :not-current (get-in proposal [:value :verdict]))
        admission-id (when (contains? #{:credential/screen :treatment/administer :discharge/authorize} op) subject)
        hit-on-file? (and admission-id (= :not-current (:verdict (store/credential-of st admission-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :credential-not-current
        :detail "臨床医の免許が最新でない状態での治療実施/退院許可提案は進められない"}])))

(defn- already-treated-violations
  "For `:treatment/administer`, refuses to administer a treatment to
  the SAME admission twice, off a dedicated `:treated?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :treatment/administer)
    (when (store/admission-already-treated? st subject)
      [{:rule :already-treated
        :detail (str subject " は既に治療実施済み")}])))

(defn- already-discharged-violations
  "For `:discharge/authorize`, refuses to authorize a discharge for the
  SAME admission twice, off a dedicated `:discharged?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :discharge/authorize)
    (when (store/admission-already-discharged? st subject)
      [{:rule :already-discharged
        :detail (str subject " は既に退院許可済み")}])))

(defn check
  "Censors a HospitalOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (observation-period-insufficient-violations request st)
                           (credential-not-current-violations request proposal st)
                           (already-treated-violations request st)
                           (already-discharged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
