(ns hospital.hospitalopsllm
  "HospitalOps-LLM client -- the *contained intelligence node* for the
  hospital actor.

  It normalizes inpatient-admission intake, drafts a per-jurisdiction
  hospital-institution evidence checklist, screens admissions for a
  not-current clinician license, drafts the treatment-administration
  action, and drafts the discharge-authorization action. CRITICAL: it
  is a smart-but-untrusted advisor. It returns a *proposal* (with a
  rationale + the fields it cited), never a committed record or a real
  treatment administration/discharge. Every output is censored
  downstream by `hospital.governor` before anything touches the SSoT,
  and `:treatment/administer`/`:discharge/authorize` proposals NEVER
  auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/administer-treatment | :actuation/authorize-discharge | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [hospital.facts :as facts]
            [hospital.registry :as registry]
            [hospital.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the patient, observation-hours figure or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "入院記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :admission/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction hospital-institution evidence checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `hospital.facts` -- the Clinical Oversight Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [a (store/admission db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction a))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "hospital.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-credential
  "Clinician-credential screening draft. `:clinician-license-current?`
  on the admission record injects the failure mode: the Clinical
  Oversight Governor must HOLD, un-overridably, on any not-current
  license."
  [db {:keys [subject]}]
  (let [a (store/admission db subject)]
    (cond
      (nil? a)
      {:summary "対象入院記録が見つかりません" :rationale "no admission record"
       :cites [] :effect :credential-screening/set :value {:admission-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:clinician-license-current? a))
      {:summary    (str (:patient-name a) ": 臨床医免許の期限切れを検出")
       :rationale  "スクリーニングが期限切れの臨床医免許を検出。人手確認とホールドが必須。"
       :cites      [:credential-check]
       :effect     :credential-screening/set
       :value      {:admission-id subject :verdict :not-current}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:patient-name a) ": 臨床医免許は最新")
       :rationale  "臨床医免許スクリーニング完了。"
       :cites      [:credential-check]
       :effect     :credential-screening/set
       :value      {:admission-id subject :verdict :current}
       :stake      nil
       :confidence 0.9})))

(defn- propose-treatment-administration
  "Draft the actual TREATMENT-ADMINISTRATION action -- administering a
  real treatment, procedure or prescription to an inpatient. ALWAYS
  `:stake :actuation/administer-treatment` -- this is a REAL-WORLD
  clinical act, never a draft the actor may auto-run. See README
  `Actuation`: no phase ever adds this op to a phase's `:auto` set
  (`hospital.phase`); the governor also always escalates on
  `:actuation/administer-treatment`. Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [a (store/admission db subject)]
    {:summary    (str subject " 向け治療実施提案"
                      (when a (str " (patient=" (:patient-name a) ")")))
     :rationale  (if a
                   (str "clinician-license-current?=" (:clinician-license-current? a))
                   "入院記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :admission/mark-treated
     :value      {:admission-id subject}
     :stake      :actuation/administer-treatment
     :confidence (if (and a (:clinician-license-current? a)) 0.9 0.3)}))

(defn- propose-discharge-authorization
  "Draft the actual DISCHARGE-AUTHORIZATION action -- authorizing a
  real inpatient's discharge. ALWAYS `:stake :actuation/authorize-
  discharge` -- this is a REAL-WORLD clinical act, never a draft the
  actor may auto-run. See README `Actuation`: no phase ever adds this
  op to a phase's `:auto` set (`hospital.phase`); the governor also
  always escalates on `:actuation/authorize-discharge`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [a (store/admission db subject)
        insufficient? (and a (not (registry/observation-period-elapsed? a)))]
    {:summary    (str subject " 向け退院許可提案"
                      (when a (str " (patient=" (:patient-name a) ")")))
     :rationale  (if a
                   (str "hours-since-procedure=" (:hours-since-procedure a)
                        " minimum-observation-hours=" registry/minimum-observation-hours)
                   "入院記録が見つかりません")
     :cites      (if a [subject] [])
     :effect     :admission/mark-discharged
     :value      {:admission-id subject}
     :stake      :actuation/authorize-discharge
     :confidence (if insufficient? 0.3 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :admission/intake         (normalize-intake db request)
    :jurisdiction/assess          (assess-jurisdiction db request)
    :credential/screen                (screen-credential db request)
    :treatment/administer                 (propose-treatment-administration db request)
    :discharge/authorize                       (propose-discharge-authorization db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは病院の治療実施・退院許可エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:admission/upsert|:assessment/set|:credential-screening/set|"
       ":admission/mark-treated|:admission/mark-discharged) "
       ":stake(:actuation/administer-treatment か :actuation/authorize-discharge か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:admission (store/admission st subject)}
    :credential/screen    {:admission (store/admission st subject)}
    :treatment/administer {:admission (store/admission st subject)}
    :discharge/authorize  {:admission (store/admission st subject)}
    {:admission (store/admission st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Clinical Oversight Governor
  escalates/holds -- an LLM hiccup can never auto-administer a
  treatment or auto-authorize a discharge."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :hospitalopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
