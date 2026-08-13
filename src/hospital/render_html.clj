(ns hospital.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300):
  this repo previously had NO operator console and no generator at all.

  This namespace drives the REAL actor stack (`hospital.operation` ->
  `hospital.governor` -> `hospital.store`, through `langgraph.graph/
  run*` with real `interrupt-before` approval resumes) against the real
  seeded admission directory (`hospital.store/demo-data`: `admission-1`
  .. `admission-4`) and renders whatever actually came out. There is no
  hand-typed HTML table body anywhere in this file: every admission id,
  patient name, jurisdiction, hour count, sequence number, record id,
  hold rule and hold detail string on the page is read back out of the
  store, the governor verdicts or the graph audit channel after the run.

  Determinism: no timestamps, no randomness, no map-iteration order --
  every collection is either an append-only vector (the ledger, the
  registry histories, the audit channel) or explicitly sorted. Two
  consecutive runs are byte-identical.

  Build-time invariant: `-main` THROWS if the scenario produced zero
  `:governor-hold` facts, or if any ledger fact names a subject that is
  not in the seeded admission directory. A console that quietly stopped
  exercising the Clinical Oversight Governor, or that grew a fabricated
  entity id, fails the build rather than shipping.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [hospital.facts :as facts]
            [hospital.governor :as governor]
            [hospital.operation :as op]
            [hospital.phase :as phase]
            [hospital.registry :as registry]
            [hospital.store :as store]
            [langgraph.graph :as g]))

;; ----------------------------- operator identities -----------------------------
;;
;; These two identities are supplied by THIS build-time driver (exactly
;; as `hospital.sim` supplies `op-1`); they are operator/approver
;; identities, not seeded clinical data. They are deliberately
;; DIFFERENT from each other so the console can show the distinction
;; the audit trail actually makes: `:actor` on a committed fact is the
;; actor that ran the op, which is NOT the human who approved it.

(def ^:private operator
  {:actor-id "op-1" :actor-role :clinician :phase 3})

(def ^:private approver-id "clinician-2")

;; ----------------------------- scenario -----------------------------

(def ^:private scenario
  "One entry per graph run. `:decision` is what the human operator does
  IF the actor interrupts for approval; when the governor HARD-holds,
  the actor never interrupts and the decision is never consulted --
  that is the point of a HARD hold, and the run below proves it rather
  than asserting it.

  Requests use the seeded admissions' OWN jurisdictions: `admission-2`
  is genuinely admitted under `ATL`, which is genuinely absent from
  `hospital.facts/catalog`, so no `:no-spec?` injection flag is needed
  to reach the no-spec-basis hold."
  [;; --- admission-1: a complete clean lifecycle, every write human-approved
   {:thread "a1-intake"    :request {:op :admission/intake :subject "admission-1"
                                     :patch {:id "admission-1" :patient-name "Sakura Tanaka"}}
    :decision :approve
    :note "clean intake -- the ONLY op phase 3 may auto-commit"}
   {:thread "a1-assess"    :request {:op :jurisdiction/assess :subject "admission-1"}
    :decision :approve
    :note "jurisdiction evidence checklist -- phase-gated to a human"}
   {:thread "a1-screen"    :request {:op :credential/screen :subject "admission-1"}
    :decision :approve
    :note "clinician licence screening -- current"}
   {:thread "a1-treat"     :request {:op :treatment/administer :subject "admission-1"}
    :decision :approve
    :note "REAL clinical act -- never auto at any phase"}
   {:thread "a1-discharge" :request {:op :discharge/authorize :subject "admission-1"}
    :decision :approve
    :note "REAL clinical act -- 6h observed >= 4h minimum"}

   ;; --- admission-2: unregistered jurisdiction, then an actuation on it
   {:thread "a2-assess"    :request {:op :jurisdiction/assess :subject "admission-2"}
    :decision :approve
    :note "ATL is absent from hospital.facts/catalog"}
   {:thread "a2-screen"    :request {:op :credential/screen :subject "admission-2"}
    :decision :reject
    :note "human operator REJECTS a governor-clean proposal"}
   {:thread "a2-treat"     :request {:op :treatment/administer :subject "admission-2"}
    :decision :approve
    :note "no evidence checklist on file for ATL"}

   ;; --- admission-3: post-procedure observation window not yet elapsed
   {:thread "a3-discharge-early" :request {:op :discharge/authorize :subject "admission-3"}
    :decision :approve
    :note "before any assessment -- TWO independent HARD violations at once"}
   {:thread "a3-assess"    :request {:op :jurisdiction/assess :subject "admission-3"}
    :decision :approve
    :note "JPN checklist committed"}
   {:thread "a3-screen"    :request {:op :credential/screen :subject "admission-3"}
    :decision :approve
    :note "clinician licence screening -- current"}
   {:thread "a3-treat"     :request {:op :treatment/administer :subject "admission-3"}
    :decision :approve
    :note "second JPN treatment -- jurisdiction sequence advances"}
   {:thread "a3-discharge" :request {:op :discharge/authorize :subject "admission-3"}
    :decision :approve
    :note "evidence now complete, so the observation window stands alone"}

   ;; --- admission-4: lapsed clinician licence
   {:thread "a4-assess"    :request {:op :jurisdiction/assess :subject "admission-4"}
    :decision :approve
    :note "JPN checklist committed"}
   {:thread "a4-screen"    :request {:op :credential/screen :subject "admission-4"}
    :decision :approve
    :note "the screening's OWN finding HARD-holds it -- no human is asked"}

   ;; --- double-actuation guards on the already-completed admission-1
   {:thread "a1-treat-again"     :request {:op :treatment/administer :subject "admission-1"}
    :decision :approve
    :note "same admission, second treatment administration"}
   {:thread "a1-discharge-again" :request {:op :discharge/authorize :subject "admission-1"}
    :decision :approve
    :note "same admission, second discharge authorization"}])

(defn run-demo!
  "Runs `scenario` against a freshly seeded store and returns
  `{:db .. :steps [..]}`.

  A step is resumed with the human decision ONLY when the actor
  actually interrupted (`:status :interrupted`); a HARD governor hold
  finishes the graph without ever reaching `:request-approval`, and the
  recorded `:human` is then `:not-consulted` -- measured from the run,
  not asserted."
  []
  (let [db    (store/seed-db)
        actor (op/build db)
        steps (reduce
               (fn [acc {:keys [thread request decision note]}]
                 (let [r1 (g/run* actor {:request request :context operator}
                                  {:thread-id thread})
                       interrupted? (= :interrupted (:status r1))
                       r2 (when interrupted?
                            (g/run* actor
                                    {:approval (if (= :reject decision)
                                                 {:status :rejected :by approver-id}
                                                 {:status :approved :by approver-id})}
                                    {:thread-id thread :resume? true}))
                       final (:state (or r2 r1))]
                   (conj acc {:thread   thread
                              :request  request
                              :note     note
                              :first    (:state r1)
                              :status   (:status (or r2 r1))
                              :human    (if interrupted? decision :not-consulted)
                              :state    final})))
               []
               scenario)]
    {:db db :steps steps}))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- td [& cells] (str "        <tr>" (str/join "" (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- yes-no [b yes no]
  (if b (str "<span class=\"ok\">" yes "</span>") (str "<span class=\"critical\">" no "</span>")))

(defn- section [title lead headers rows]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" lead "</p>\n"
       "    <table>\n"
       "      <thead><tr>" (str/join "" (map #(str "<th>" % "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"
       "  </section>\n"))

;; ----------------------------- derived views -----------------------------

(defn- audit-of [steps] (mapcat #(:audit (:state %) []) steps))

(defn- proposals-by-op
  "op -> {:effect .. :stake .. :confidences #{..}} read off the graph's
  own `:proposal` channel for every run -- the actor's real op contract
  as exercised, not a hand-maintained table."
  [steps]
  (reduce (fn [m {:keys [request state]}]
            (let [p (:proposal state)
                  o (:op request)]
              (-> m
                  (assoc-in [o :effect] (:effect p))
                  (assoc-in [o :stake] (:stake p))
                  (update-in [o :confidences] (fnil conj (sorted-set)) (:confidence p)))))
          {} steps))

(def ^:private approver-keys
  "Every spelling of 'who approved this' worth probing for. Probing a
  SET rather than one key is deliberate: this table must report what
  the store actually retained, in whatever shape, not what this file
  expects it to retain."
  [:approved-by :approver :approved_by "approved_by" "approved-by" "approver"])

(defn- approver-in
  "MEASURED, never assumed: the approver actually present in a
  persisted artifact, or nil."
  [m]
  (when (map? m) (some #(get m %) approver-keys)))

(defn- hold-facts [ledger] (filter #(= :governor-hold (:t %)) ledger))

(defn- last-fact-for [ledger id] (last (filter #(= (:subject %) id) ledger)))

;; ----------------------------- sections -----------------------------

(defn- summary-section [db steps]
  (let [ledger (vec (store/ledger db))
        audit  (audit-of steps)
        holds  (hold-facts ledger)
        rules  (into (sorted-set) (mapcat #(map :rule (:violations %)) holds))]
    (section
     "Run summary"
     (str "Every figure below is counted from the run this build performed -- "
          (count scenario) " graph runs against " (count (store/all-admissions db))
          " seeded admissions.")
     ["Measure" "Count" "Source"]
     [(td "Seeded admissions" (count (store/all-admissions db)) (code "hospital.store/demo-data"))
      (td "Graph runs in this scenario" (count steps) (code "langgraph.graph/run*"))
      (td "Append-only ledger facts" (count ledger) (code "hospital.store/ledger"))
      (td (str "<strong>HARD governor holds</strong>") (str "<strong>" (count holds) "</strong>")
          (code ":t :governor-hold"))
      (td "Distinct HARD hold rules exercised" (count rules) (code "hospital.governor"))
      (td "Approvals requested (escalations)"
          (count (filter #(= :approval-requested (:t %)) audit)) (code ":t :approval-requested"))
      (td "Approvals granted" (count (filter #(= :approval-granted (:t %)) audit))
          (code ":t :approval-granted"))
      (td "Approvals rejected by the human"
          (count (filter #(= :approval-rejected (:t %)) ledger)) (code ":t :approval-rejected"))
      (td "Runs that never reached a human"
          (count (filter #(= :not-consulted (:human %)) steps)) "interrupt never fired")
      (td "Treatment-administration drafts" (count (store/treatment-history db))
          (code "hospital.registry"))
      (td "Discharge-authorization drafts" (count (store/discharge-history db))
          (code "hospital.registry"))])))

(defn- admissions-section [db]
  (let [ledger (vec (store/ledger db))]
    (section
     "Inpatient admissions (SSoT)"
     (str "The live admission directory after the run. Post-procedure hours are compared against "
          (code (str "hospital.registry/minimum-observation-hours = " registry/minimum-observation-hours))
          " -- the governor recomputes this itself and never trusts the proposal.")
     ["Admission" "Patient" "Jurisdiction" "Spec-basis" "Hours since procedure"
      "Clinician licence" "Treatment" "Discharge" "Last decision"]
     (for [{:keys [id patient-name jurisdiction hours-since-procedure
                   clinician-license-current? treated? discharged?
                   administration-number discharge-number] :as a}
           (store/all-admissions db)]
       (let [f (last-fact-for ledger id)]
         (td (code id)
             (esc patient-name)
             (esc jurisdiction)
             (if (facts/spec-basis jurisdiction)
               "<span class=\"ok\">registered</span>"
               "<span class=\"critical\">NOT in catalog</span>")
             (str "<span class=\"num\">" hours-since-procedure "</span> h "
                  (if (registry/observation-period-elapsed? a)
                    "<span class=\"ok\">&ge; minimum</span>"
                    "<span class=\"critical\">&lt; minimum</span>"))
             (yes-no clinician-license-current? "current" "NOT current")
             (if treated?
               (str "<span class=\"ok\">administered</span> " (code administration-number))
               "<span class=\"muted\">not administered</span>")
             (if discharged?
               (str "<span class=\"ok\">authorized</span> " (code discharge-number))
               "<span class=\"muted\">not authorized</span>")
             (cond
               (nil? f) "<span class=\"muted\">no activity</span>"
               (= :governor-hold (:t f))
               (str "<span class=\"critical\">HARD hold</span> "
                    (code (str/join ", " (map name (:basis f)))))
               (= :approval-rejected (:t f)) "<span class=\"warn\">approval rejected</span>"
               (= :committed (:t f)) (str "<span class=\"ok\">committed</span> " (code (:op f)))
               :else "<span class=\"muted\">in progress</span>")))))))

(defn- steps-section [db steps]
  (section
   "Scenario walk (every graph run, in order)"
   (str "One row per <code>langgraph.graph/run*</code>. <em>Human</em> reports whether the "
        "actor actually interrupted for approval -- <code>not consulted</code> means the "
        "Clinical Oversight Governor HARD-held and the graph finished without ever offering "
        "the decision to a person.")
   ["#" "Thread" "Op" "Admission" "Disposition" "Human" "Outcome" "What it exercises"]
   (map-indexed
    (fn [i {:keys [thread request state human note]}]
      (let [d (:disposition state)]
        (td (inc i)
            (code thread)
            (code (:op request))
            (code (:subject request))
            (case d
              :commit "<span class=\"ok\">commit</span>"
              :hold   "<span class=\"critical\">hold</span>"
              (esc d))
            (case human
              :not-consulted "<span class=\"critical\">not consulted</span>"
              :approve       (str "<span class=\"ok\">approved</span> " (code approver-id))
              :reject        (str "<span class=\"warn\">rejected</span> " (code approver-id))
              (esc human))
            (let [last-audit (last (:audit state))]
              (case (:t last-audit)
                :committed          (str "<span class=\"ok\">SSoT written</span> " (code (:op request)))
                :governor-hold      "<span class=\"critical\">no SSoT mutation</span>"
                :approval-rejected  "<span class=\"warn\">no SSoT mutation</span>"
                (esc (:t last-audit))))
            (esc note))))
    steps)))

(defn- holds-section [db]
  (let [ledger (vec (store/ledger db))]
    (section
     "HARD governor holds (this run)"
     (str "One row per violation. These are <strong>not overridable</strong>: the graph routes "
          "straight from <code>:decide</code> to <code>:hold</code>, so "
          "<code>:request-approval</code> is never entered and no human is ever offered the "
          "decision. Every rule and detail string below is the governor's own output.")
     ["Rule" "Op" "Admission" "LLM confidence" "Governor detail"]
     (for [f (hold-facts ledger)
           v (:violations f)]
       (td (str "<span class=\"critical\">" (esc (name (:rule v))) "</span>")
           (code (:op f))
           (code (:subject f))
           (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
           (esc (:detail v)))))))

(defn- rule-coverage-section [db]
  (let [holds (hold-facts (vec (store/ledger db)))
        by-rule (reduce (fn [m f]
                          (reduce (fn [m v]
                                    (update m (:rule v) (fnil conj []) (:subject f)))
                                  m (:violations f)))
                        {} holds)]
    (section
     "HARD rule coverage exercised by this scenario"
     (str "Derived from the holds above -- this table reports the rules this run actually "
          "reached, not a hand-maintained list of the rules the governor implements (which "
          "would silently rot). " (count by-rule) " distinct rules fired.")
     ["Rule" "Times fired" "Admissions held"]
     (for [[rule subjects] (sort-by (comp name key) by-rule)]
       (td (str "<span class=\"critical\">" (esc (name rule)) "</span>")
           (str "<span class=\"num\">" (count subjects) "</span>")
           (str/join ", " (map code (distinct subjects))))))))

(defn- escalation-section [steps]
  (let [audit (audit-of steps)]
    (section
     "Escalations and human decisions"
     (str "Emitted on the graph's <code>:audit</code> channel. An escalation means the "
          "governor found nothing HARD but the rollout phase (or the actuation stake) still "
          "requires a licensed human.")
     ["Fact" "Op" "Admission" "Reason" "Phase" "Confidence" "Approver"]
     (for [f audit
           :when (#{:approval-requested :approval-granted} (:t f))]
       (td (if (= :approval-requested (:t f))
             "<span class=\"warn\">approval-requested</span>"
             "<span class=\"ok\">approval-granted</span>")
           (code (:op f))
           (code (:subject f))
           (if (:reason f) (code (:reason f)) "<span class=\"muted\">&mdash;</span>")
           (if (:phase f) (str "<span class=\"num\">" (:phase f) "</span>")
               "<span class=\"muted\">&mdash;</span>")
           (if (:confidence f) (str "<span class=\"num\">" (esc (:confidence f)) "</span>")
               "<span class=\"muted\">&mdash;</span>")
           (if (:by f) (code (:by f)) "<span class=\"muted\">&mdash;</span>"))))))

(defn- assessments-section [db]
  (section
   "Jurisdiction evidence checklists committed to the SSoT"
   (str "Read back out of the store with <code>hospital.store/assessment-of</code>. The "
        "checklist items are the jurisdiction's own required-evidence list from "
        "<code>hospital.facts/catalog</code> -- the governor refuses any actuation whose "
        "admission has no satisfying checklist on file.")
   ["Admission" "Jurisdiction" "Legal basis" "Checklist on file" "Approver retained in record"]
   (for [{:keys [id]} (store/all-admissions db)
         :let [a (store/assessment-of db id)]
         :when a]
     (td (code id)
         (esc (:jurisdiction a))
         (esc (:legal-basis a))
         (str "<ul style=\"margin:0;padding-left:1.1rem\">"
              (str/join "" (map #(str "<li>" (esc %) "</li>") (:checklist a)))
              "</ul>")
         (if-let [by (approver-in a)]
           (str "<span class=\"ok\">yes</span> " (code by))
           "<span class=\"critical\">no</span>")))))

(defn- credentials-section [db]
  (section
   "Clinician-credential screenings committed to the SSoT"
   (str "Read back with <code>hospital.store/credential-of</code>. Note the structural "
        "consequence visible below: a screening that finds a <code>:not-current</code> licence "
        "HARD-holds, so it never commits -- the register only ever contains screenings that "
        "passed.")
   ["Admission" "Verdict" "Approver retained in record"]
   (for [{:keys [id]} (store/all-admissions db)
         :let [c (store/credential-of db id)]
         :when c]
     (td (code id)
         (if (= :current (:verdict c))
           "<span class=\"ok\">:current</span>"
           (str "<span class=\"critical\">" (esc (:verdict c)) "</span>"))
         (if-let [by (approver-in c)]
           (str "<span class=\"ok\">yes</span> " (code by))
           "<span class=\"critical\">no</span>")))))

(defn- registry-section [title lead history steps kind-op]
  (section
   title lead
   ["Record id" "Kind" "Admission" "Jurisdiction" "Immutable" "Approver"]
   (for [r history]
     (let [aid (get r "admission_id")
           granted (first (filter #(and (= :approval-granted (:t %))
                                        (= kind-op (:op %))
                                        (= aid (:subject %)))
                                  (audit-of steps)))]
       (td (code (get r "record_id"))
           (esc (get r "kind"))
           (code aid)
           (esc (get r "jurisdiction"))
           (yes-no (get r "immutable") "true" "false")
           (if-let [by (approver-in r)]
             (str "<span class=\"ok\">" (code by) "</span>")
             (if granted
               (str (code (:by granted))
                    " <span class=\"warn\">(audit only; not retained in record)</span>")
               "<span class=\"critical\">no approver anywhere</span>")))))))

(defn- attribution-section [db steps]
  (let [ledger (vec (store/ledger db))
        assessed (keep #(store/assessment-of db (:id %)) (store/all-admissions db))
        screened (keep #(store/credential-of db (:id %)) (store/all-admissions db))
        treats   (store/treatment-history db)
        dischs   (store/discharge-history db)
        row (fn [effect artifact-desc artifacts]
              (let [n (count artifacts)
                    with (count (filter approver-in artifacts))]
                (td (code effect)
                    artifact-desc
                    (str "<span class=\"num\">" n "</span>")
                    (if (and (pos? n) (= n with))
                      (str "<span class=\"ok\">retained (" with "/" n ")</span>")
                      (if (zero? n)
                        "<span class=\"muted\">nothing committed</span>"
                        (str "<span class=\"critical\">dropped (" with "/" n ")</span>")))
                    (if (and (pos? n) (= n with))
                      (esc (str/join ", " (distinct (map approver-in artifacts))))
                      "<span class=\"warn\">audit channel only</span>"))))]
    (section
     "Approver attribution &mdash; measured, not assumed"
     (str "This table is computed at render time by probing each persisted artifact for an "
          "approver key " (code (pr-str approver-keys)) " -- it is not a hardcoded claim about "
          "this repo, so it stays true if the store changes. Where the approver is missing "
          "from the record it is joined from the graph's own "
          "<code>:approval-granted</code> audit fact and labelled as such, so a reader can "
          "always tell <em>&ldquo;nobody approved&rdquo;</em> from <em>&ldquo;the store did "
          "not keep it&rdquo;</em> -- in a clinical domain that distinction is the whole point "
          "of the console.")
     ["Commit effect" "Persisted artifact" "Committed" "Approver in the persisted record" "Approver"]
     [(row :assessment/set "assessment register (<code>:assessments</code>)" assessed)
      (row :credential-screening/set "credential register (<code>:credentials</code>)" screened)
      (row :admission/mark-treated
           "treatment-administration draft (<code>hospital.registry</code>)" treats)
      (row :admission/mark-discharged
           "discharge-authorization draft (<code>hospital.registry</code>)" dischs)
      (row :ledger "append-only decision ledger (<code>hospital.store/ledger</code>)" ledger)])))

(defn- gate-section [steps]
  (let [seen (proposals-by-op steps)]
    (section
     "Action gate (Clinical Oversight Governor + rollout phase)"
     (str "Derived from <code>hospital.phase/phases</code> and from the "
          "<code>:proposal</code> channel of the runs above -- the effect and stake columns "
          "are what the advisor actually proposed this run, not a hand-maintained table. "
          "Two independent layers agree that a real clinical act is always a human's call: "
          "the actuation stakes " (code (pr-str (sort (map str governor/high-stakes))))
          " escalate in the governor, AND those ops are absent from every phase's "
          "<code>:auto</code> set.")
     ["Op" "Commit effect" "Stake" "Writable in phases" "Auto-committable in phases"
      "Observed confidence"]
     (for [o (sort-by str phase/write-ops)]
       (let [writes (sort (keep (fn [[p {:keys [writes]}]] (when (writes o) p)) phase/phases))
             autos  (sort (keep (fn [[p {:keys [auto]}]] (when (auto o) p)) phase/phases))
             {:keys [effect stake confidences]} (get seen o)]
         (td (code o)
             (if effect (code effect) "<span class=\"muted\">&mdash;</span>")
             (if stake
               (str "<span class=\"critical\">" (esc stake) "</span>")
               "<span class=\"muted\">none</span>")
             (str/join ", " writes)
             (if (seq autos)
               (str "<span class=\"warn\">" (str/join ", " autos) "</span>")
               "<span class=\"critical\">never, at any phase</span>")
             (if confidences
               (str "<span class=\"num\">" (str/join ", " confidences) "</span>")
               "<span class=\"muted\">&mdash;</span>")))))))

(defn- phase-section []
  (section
   "Rollout phase ladder"
   (str "Straight out of <code>hospital.phase/phases</code>. This run used phase "
        (code (:phase operator)) " (" (esc (:label (get phase/phases (:phase operator)))) ").")
   ["Phase" "Label" "May write" "May auto-commit when governor-clean"]
   (for [p (sort (keys phase/phases))]
     (let [{:keys [label writes auto]} (get phase/phases p)]
       (td (str "<span class=\"num\">" p "</span>"
                (when (= p (:phase operator)) " <span class=\"badge\">this run</span>"))
           (esc label)
           (if (seq writes)
             (str/join " " (map code (sort-by str writes)))
             "<span class=\"muted\">nothing</span>")
           (if (seq auto)
             (str/join " " (map code (sort-by str auto)))
             "<span class=\"critical\">nothing</span>"))))))

(defn- catalog-section [db]
  (let [used (into (sorted-set) (map :jurisdiction (store/all-admissions db)))
        cov  (facts/coverage)]
    (section
     "Jurisdiction spec-basis catalog"
     (str "<code>hospital.facts/catalog</code> as seeded. " (esc (:note cov))
          " A jurisdiction absent from this table has NO spec-basis, and the governor holds "
          "any proposal that tries to invent one.")
     ["ISO3" "Jurisdiction" "Institutional regulator" "Legal basis" "Required evidence"
      "In this run"]
     (concat
      (for [iso3 (sort (keys facts/catalog))]
        (let [c (facts/spec-basis iso3)]
          (td (code iso3)
              (esc (:name c))
              (esc (:owner-authority c))
              (esc (:legal-basis c))
              (str "<span class=\"num\">" (count (:required-evidence c)) "</span> items")
              (if (used iso3)
                "<span class=\"ok\">admitted under</span>"
                "<span class=\"muted\">not used</span>"))))
      (for [iso3 (remove facts/catalog used)]
        (td (code iso3)
            "<span class=\"critical\">no entry</span>"
            "<span class=\"critical\">none &mdash; must not be invented</span>"
            "<span class=\"critical\">none</span>"
            "<span class=\"num\">0</span>"
            "<span class=\"ok\">admitted under</span>"))))))

(defn- ledger-section [db]
  (section
   "Append-only audit ledger (this run)"
   (str "Every immutable decision fact the run wrote, in order, exactly as "
        "<code>hospital.store/ledger</code> returns it. <em>Actor</em> is the actor that ran "
        "the op -- it is <strong>not</strong> the human approver, and this ledger does not "
        "carry one (see the attribution table above).")
   ["#" "Fact" "Op" "Admission" "Disposition" "Actor" "Basis"]
   (map-indexed
    (fn [i {:keys [t op subject disposition actor basis]}]
      (td (inc i)
          (case t
            :committed         "<span class=\"ok\">committed</span>"
            :governor-hold     "<span class=\"critical\">governor-hold</span>"
            :approval-rejected "<span class=\"warn\">approval-rejected</span>"
            (esc t))
          (code op)
          (code subject)
          (esc disposition)
          (if actor (code actor) "<span class=\"muted\">&mdash;</span>")
          (if (seq basis)
            (esc (str/join ", " (map #(if (keyword? %) (name %) (str %)) basis)))
            "<span class=\"muted\">&mdash;</span>")))
    (store/ledger db))))

(defn- thresholds-section []
  (section
   "Independently recomputed thresholds"
   (str "The governor never trusts a proposal's self-report for any of these -- it reads the "
        "admission's own permanent fields and recomputes.")
   ["Constant" "Value" "Enforced by"]
   [(td (code "hospital.registry/minimum-observation-hours")
        (str "<span class=\"num\">" registry/minimum-observation-hours "</span> hours")
        (code "observation-period-insufficient"))
    (td (code "hospital.governor/confidence-floor")
        (str "<span class=\"num\">" governor/confidence-floor "</span>")
        "escalate below this (SOFT -- a human may approve)")
    (td (code "hospital.governor/high-stakes")
        (esc (pr-str (sort (map str governor/high-stakes))))
        "always escalate, at every phase")
    (td (code "hospital.facts/catalog")
        (str "<span class=\"num\">" (count facts/catalog) "</span> jurisdictions")
        (code "no-spec-basis"))]))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole console from a `run-demo!` result."
  [{:keys [db steps]}]
  (str
   "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
   "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
   "<title>cloud-itonami-isic-8610 &middot; Hospital activities &middot; Operator Console</title>\n"
   "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
   "<header class=\"bar\">\n"
   "  <h1>Hospital activities (ISIC 8610) &mdash; Operator Console</h1>\n"
   "  <span class=\"badge\">build-time generated &middot; governor-gated &middot; "
   "treatment administration and discharge authorization are always a human clinician's call</span>\n"
   "</header>\n"
   "<main class=\"container\">\n"
   "  <p class=\"subtitle\">Generated by <code>hospital.render-html</code> "
   "(<code>clojure -M:dev:render-html</code>) by running the real "
   "<code>hospital.operation</code> actor graph &mdash; "
   "<code>:advise</code> &rarr; <code>:govern</code> &rarr; <code>:decide</code> &rarr; "
   "<code>:commit</code> | <code>:hold</code> | <code>:request-approval</code> &mdash; against "
   "the seeded admission directory. No value on this page is hand-typed: every id, name, "
   "hour count, record number, rule and detail string is read back out of the store, the "
   "governor verdicts or the graph audit channel. Deterministic: no timestamps, no "
   "randomness, byte-identical across reruns.</p>\n"
   (summary-section db steps)
   (admissions-section db)
   (holds-section db)
   (rule-coverage-section db)
   (steps-section db steps)
   (escalation-section steps)
   (attribution-section db steps)
   (assessments-section db)
   (credentials-section db)
   (registry-section
    "Treatment-administration drafts"
    (str "Built by <code>hospital.registry/register-treatment-administration</code> &mdash; "
         "jurisdiction-scoped sequence numbers, no invented check-digit standard. These are "
         "UNSIGNED drafts: signing is the hospital's own act, not this actor's.")
    (store/treatment-history db) steps :treatment/administer)
   (registry-section
    "Discharge-authorization drafts"
    (str "Built by <code>hospital.registry/register-discharge-authorization</code>, on its own "
         "independent sequence counter &mdash; administering a treatment and authorizing a "
         "discharge are two separate actuation events on the same admission, each with its own "
         "history and its own double-actuation guard.")
    (store/discharge-history db) steps :discharge/authorize)
   (gate-section steps)
   (phase-section)
   (thresholds-section)
   (catalog-section db)
   (ledger-section db)
   "  <footer>\n"
   "    <p>cloud-itonami-isic-8610 &mdash; hospital activities. Read-only sample rendered from "
   "an in-memory <code>hospital.store/MemStore</code> seeded with "
   "<code>hospital.store/demo-data</code>; the same actor runs unchanged against the "
   "<code>DatomicStore</code> backend (both pass "
   "<code>test/hospital/store_contract_test.clj</code>). Certificates produced here are "
   "<code>draft-unsigned</code> and are not issued by any registry.</p>\n"
   "  </footer>\n"
   "</main>\n</body></html>\n"))

;; ----------------------------- build-time invariants -----------------------------

(defn- assert-invariants!
  "Fails the BUILD, not a code review, if the console stops being
  evidence."
  [{:keys [db]}]
  (let [ledger (vec (store/ledger db))
        holds  (hold-facts ledger)
        seeded (into #{} (map :id) (store/all-admissions db))
        stray  (into (sorted-set) (remove seeded (keep :subject ledger)))]
    (when (zero? (count holds))
      (throw (ex-info (str "render-html: the scenario produced ZERO :governor-hold facts. "
                           "A console that does not exercise a HARD, un-overridable Clinical "
                           "Oversight Governor hold is not evidence that the governor works.")
                      {:ledger-facts (count ledger)})))
    (when (seq stray)
      (throw (ex-info (str "render-html: ledger names subject(s) absent from the seeded "
                           "admission directory: " (pr-str stray)
                           ". Every id on the console must trace to hospital.store/demo-data.")
                      {:stray stray :seeded seeded})))
    {:holds (count holds)
     :rules (into (sorted-set) (mapcat #(map (comp name :rule) (:violations %)) holds))}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        {:keys [holds rules]} (assert-invariants! result)
        html (render result)
        db (:db result)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, "
                  (count (store/ledger db)) " ledger facts, "
                  holds " HARD governor holds over " (count rules) " distinct rules: "
                  (str/join ", " rules) ", "
                  (count (store/treatment-history db)) " treatment drafts, "
                  (count (store/discharge-history db)) " discharge drafts)"))))
