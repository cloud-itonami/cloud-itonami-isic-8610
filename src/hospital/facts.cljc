(ns hospital.facts
  "Per-jurisdiction hospital-licensing/operation regulatory catalog --
  the G2-style spec-basis table the Clinical Oversight Governor checks
  every jurisdiction/assess proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's hospital-institution
  licensing/operation requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Unlike `clinic.facts` (individual practitioner-licensing bodies --
  医師法/the Federation of State Medical Boards/the GMC/GDC/the
  Bundesärztekammer), this catalog cites INSTITUTIONAL hospital-
  operation regulators, since an inpatient hospital's own operating
  license/accreditation is a distinct regulatory concern from an
  individual clinician's practice license -- an honest, domain-
  accurate differentiation even where a jurisdiction overlaps with
  `clinic.facts`'s.

  Seed values are drawn from each jurisdiction's official hospital-
  institution regulator (see `:provenance`); they are a STARTING
  catalog, not a from-scratch survey of all ~194 jurisdictions.
  Extending coverage is additive: add one map to `catalog`, cite a
  real source, done -- never invent a jurisdiction's requirements to
  make coverage look bigger.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  patient-consent/diagnostic-documentation/clinician-license-
  verification/discharge-care-plan evidence set submitted in some
  form; `:legal-basis` / `:owner-authority` / `:provenance` are the G2
  citation the governor requires before any :jurisdiction/assess
  proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare)"
          :legal-basis "医療法 (Medical Care Act)"
          :national-spec "病院の開設許可・人員配置・構造設備基準"
          :provenance "https://www.mhlw.go.jp/"
          :required-evidence ["患者同意/事前指示書 (patient consent/advance directive record)"
                              "診断/処置記録 (diagnostic/procedure documentation)"
                              "臨床医免許確認記録 (clinician license verification)"
                              "退院時療養計画書 (discharge/follow-up care plan)"]}
   "USA" {:name "United States"
          :owner-authority "Centers for Medicare & Medicaid Services (CMS)"
          :legal-basis "Conditions of Participation for Hospitals (42 CFR Part 482)"
          :national-spec "Hospital accreditation and conditions-of-participation requirements"
          :provenance "https://www.cms.gov/regulations-and-guidance/legislation/cfcsandcops/hospitals"
          :required-evidence ["Patient consent/advance directive record"
                              "Diagnostic/procedure documentation"
                              "Clinician license verification"
                              "Discharge/follow-up care plan"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Care Quality Commission (CQC)"
          :legal-basis "Health and Social Care Act 2008 (Regulated Activities) Regulations 2014"
          :national-spec "CQC Fundamental Standards for hospital care"
          :provenance "https://www.cqc.org.uk/"
          :required-evidence ["Patient consent/advance directive record"
                              "Diagnostic/procedure documentation"
                              "Clinician license verification"
                              "Discharge/follow-up care plan"]}
   "DEU" {:name "Germany"
          :owner-authority "Gemeinsamer Bundesausschuss (G-BA, Federal Joint Committee)"
          :legal-basis "Krankenhausfinanzierungsgesetz (KHG) + Landeskrankenhausgesetze"
          :national-spec "Qualitäts- und Sicherheitsanforderungen für Krankenhäuser"
          :provenance "https://www.g-ba.de/"
          :required-evidence ["Patienteneinwilligung/Patientenverfügung (patient consent/advance directive record)"
                              "Diagnose-/Eingriffsdokumentation (diagnostic/procedure documentation)"
                              "Ärztliche Approbationsnachweis (clinician license verification)"
                              "Entlassungs-/Nachsorgeplan (discharge/follow-up care plan)"]}
   ;; New Zealand: verified this session against legislation.govt.nz's
   ;; Health and Disability Services (Safety) Act 2001 ("Version as at 5
   ;; April 2023" consolidation) and Pae Ora (Healthy Futures) Act 2022
   ;; ("as at 25 October 2024" consolidation), plus health.govt.nz's
   ;; "Certification of health care services" + "Health and Disability
   ;; Services (Safety) Act" pages -- all retrieved via the Wayback
   ;; Machine after legislation.govt.nz's live site returned an AWS WAF
   ;; bot-detection challenge (response header `x-amzn-waf-action:
   ;; challenge`) to a direct fetch; per the no-bypass safety rule, the
   ;; archived copies were used instead of attempting to defeat the
   ;; challenge. Section 9 (duty to be certified) and ss 26-27
   ;; (Director-General's power to certify) of the 2001 Act establish ONE
   ;; national certification regime that applies uniformly to both
   ;; public hospitals -- now operated by Health New Zealand, the Crown
   ;; agent established by s 11 of the Pae Ora Act, which came into
   ;; force on 1 July 2022 (s 2) and on that date disestablished "all
   ;; DHBs" (s 9) -- and private hospitals: health.govt.nz's certified-
   ;; provider database lists both under this same Act/standard (e.g.
   ;; Auckland City Hospital and Wellington Hospital under "public
   ;; hospitals"; the Southern Cross Hospital and Mercy Hospital chains
   ;; under "private hospitals"). So the 2022 health-system reform
   ;; changed WHO operates public hospitals, not the hospital-
   ;; certification legal basis they operate under -- no separate
   ;; post-reform entry/legal-basis is needed. Every fact above is
   ;; sourced from a page fetched and read this session, not training-
   ;; data memory -- e.g. the Pae Ora Act sections read here do not
   ;; state a specific former-DHB count, so none is asserted.
   "NZL" {:name "New Zealand"
          :owner-authority "HealthCERT (Ministry of Health -- Manatū Hauora), administering/enforcing the Act under certification power the Act vests in the Director-General of Health"
          :legal-basis "Health and Disability Services (Safety) Act 2001 (2001 No 93)"
          :national-spec "Ngā Paerewa Health and Disability Services Standard (NZS 8134:2021)"
          :provenance "https://www.health.govt.nz/regulation-legislation/certification-of-health-care-services"
          :required-evidence ["Patient consent/advance directive record"
                              "Diagnostic/procedure documentation"
                              "Clinician license verification"
                              "Discharge/follow-up care plan"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to administer a
  treatment or authorize a discharge on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-8610 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `hospital.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
