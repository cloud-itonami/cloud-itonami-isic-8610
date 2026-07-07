# ADR-0001: cloud-itonami-isic-8610 -- HospitalOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`
  ADR-0001s (the pattern this ADR ports); ADR-2607071250/
  ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/
  ADR-2607071654/ADR-2607071717/ADR-2607071732/ADR-2607071752/
  ADR-2607071819/ADR-2607071849/ADR-2607071922/ADR-2607072715/
  ADR-2607072730/ADR-2607072745/ADR-2607072800/ADR-2607072815/
  ADR-2607072830 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/
  `9000`/`8890`, the eighteen verticals built outside ADR-2607032000's
  original insurance/real-estate batch -- this is the nineteenth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8890`, this ADR deepens `cloud-itonami-
  isic-8610` (hospital activities) from `:blueprint` to `:implemented`,
  the twenty-seventh actor in this fleet -- a SECOND human-health
  vertical (ISIC division 86) alongside `8620`'s clinic, but for
  inpatient hospital care rather than outpatient practice.

## Problem

A hospital's treatment-administration/discharge-authorization
workflow bundles several distinct concerns under one governed
workflow:

1. **Jurisdiction hospital-institution licensing correctness** -- an
   official spec-basis citation from a real regulator (厚生労働省 via
   the Medical Care Act/CMS Conditions of Participation/the Care
   Quality Commission/the Gemeinsamer Bundesausschuss), distinct from
   `clinic.facts`'s individual-practitioner-licensing bodies, never
   fabricated.
2. **Post-procedure observation sufficiency** -- has an admission's
   own minimum post-procedure observation window actually elapsed
   before discharge? The THIRD instance of this fleet's MINIMUM-
   threshold temporal-sufficiency shape (`veterinary.registry/
   withdrawal-period-insufficient?` established the first, `funeral.
   registry/waiting-period-elapsed?` the second), applied here
   UNCONDITIONALLY (every discharge needs the same minimum observation
   window, unlike veterinary's food-producing-only gate).
3. **Credential resolution verification** -- has the treating
   clinician's own license actually stayed current before either a
   treatment is administered or a discharge is authorized? The
   hospital-specific reuse of the unconditional-evaluation screening
   discipline this fleet's `casualty.governor/sanctions-violations`
   originally established -- a SIXTEENTH distinct grounding overall,
   and the THIRD specifically for the credential-not-current concept
   (after `clinic` and `veterinary`).
4. **Real, high-stakes actuation, twice** -- administering a real
   treatment and authorizing a real discharge are two independently-
   gated real-world acts on the SAME entity (an inpatient admission).

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a hospital with an LLM" but "seal the
LLM inside a trust boundary and layer evidence-sufficiency,
observation-period verification, credential-resolution verification,
audit and human-approval on top of it, while structurally fixing both
real actuation events as human-only."

## Decision

### 1. HospitalOps-LLM is sealed into the bottom node; it never administers or discharges directly

`hospital.hospitalopsllm` returns exactly five kinds of proposal:
intake normalization, jurisdiction hospital-institution checklist,
credential screening, treatment-administration draft, and discharge-
authorization draft. No proposal writes the SSoT or commits a real
administration/discharge directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 hospital operation

`hospital.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `observation-period-elapsed?` is the THIRD instance of the MINIMUM-threshold temporal-sufficiency family, applied unconditionally

`veterinary.registry/withdrawal-period-insufficient?` established the
FIRST check in this fleet's MINIMUM-threshold temporal-sufficiency
family, gated on a `:food-producing?` type tag. `funeral.registry/
waiting-period-elapsed?` was the SECOND, applying the same shape
unconditionally (every decedent is subject to the same statutory
minimum wait). `observation-period-elapsed?` is the THIRD instance,
also applied unconditionally: every admission's discharge requires the
SAME minimum post-procedure observation window, so no type-tag gate
is needed here, matching `funeral`'s simpler application rather than
`veterinary`'s gated one.

### 4. `hospital.facts` cites INSTITUTIONAL regulators, distinct from `clinic.facts`'s practitioner-licensing bodies

Even where a jurisdiction overlaps with `clinic.facts`'s catalog (e.g.
JPN, USA, GBR, DEU), `hospital.facts` cites the regulator responsible
for the HOSPITAL's own operating license/accreditation (医療法/CMS
Conditions of Participation/the CQC/the G-BA), not the individual
clinician's practice license (医師法/FSMB/the GMC-GDC/the
Bundesärztekammer) -- an honest, domain-accurate differentiation
between institutional and individual licensing concerns that happen
to be regulated by different (or in GBR's case, the same) bodies in
the same jurisdiction.

### 5. Credential-not-current screening reuses the unconditional-evaluation discipline for a sixteenth distinct grounding, and a third specifically for this clinical-licensing concept

`credential-not-current-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:credential/screen`, `:treatment/administer` AND
`:discharge/authorize` -- the SIXTEENTH distinct application of this
exact discipline in this fleet overall, and the THIRD specifically for
the credential-not-current clinical-licensing concept (`clinic`
established it, `veterinary` reused it verbatim for the identical
concept applied to animal patients, `hospital` is the third reuse for
inpatient care).

### 6. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson already recorded by `parksafety`/`eldercare`/`museum`/`conservation`/`salon`/`entertainment`/`casework`

`credential-not-current-is-held-and-unoverridable` calls `:credential/
screen` directly against `admission-4` (a not-current license), NOT
`:treatment/administer`/`:discharge/authorize` against an unscreened
admission -- because a failing screen is itself a HARD hold whose
payload never persists to the store, so the actuation ops alone could
never discover the bad ground-truth flag through this check family
without the screening op having actually been run first. This build
applied that lesson PROACTIVELY for a seventh consecutive vertical
(after `eldercare`, `museum`, `conservation`, `salon`,
`entertainment` and `casework`), further reinforcing that lessons
recorded in this fleet's ADRs transfer forward reliably.

### 7. Dual actuation, matching `6512`/`6622`/`6520`/`6530`/`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`/`8890`'s shape

`hospital.governor`'s `high-stakes` set has exactly two members
(`:actuation/administer-treatment`, `:actuation/authorize-discharge`),
each acting on the SAME admission entity, each with its OWN history
collection (`treatment-history`/`discharge-history`), sequence counter
and dedicated double-actuation-guard boolean.

### 8. Double-administration/double-discharge guards check dedicated booleans, not `:status`

`already-treated-violations`/`already-discharged-violations` check
`:treated?`/`:discharged?`, dedicated booleans set once and never
cleared, rather than a `:status` value that could legitimately advance
past a checked state (the exact trap `cloud-itonami-isic-6492`'s
ADR-0001 documents in detail, explicitly avoided BY DESIGN in every
sibling actor's equivalent guard since). This actor's `:status` never
needs to encode "has this actuation already happened" at all -- a
deliberate architectural choice applied here for a seventeenth
consecutive time.

### 9. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`/`9102`/`9103`/`9602`/`9000`/`8890`, and unlike most other
actors in this fleet, this vertical's admission records are practice-
specific rather than a shared cross-operator data contract --
`hospital.*` runs on the generic identity/forms/dmn/bpmn/audit-ledger
stack only, per the blueprint's own explicit statement.

## Consequences

- (+) Inpatient hospital care gets the same governed, auditable-actor
  treatment as the twenty-six prior actors, and this fleet now has a
  NINETEENTH concrete precedent for extending past ADR-2607032000's
  original scope, deepening human-health coverage (ISIC division 86)
  alongside `8620`'s clinic with a genuinely different care model
  (inpatient vs. outpatient).
- (+) `observation-period-elapsed?` is a genuine structural
  contribution: the third instance of the MINIMUM-threshold temporal-
  sufficiency family, applied unconditionally to a fresh domain-
  authentic ground truth.
- (+) `hospital.facts`'s institutional-vs-practitioner regulator
  distinction is a genuine domain-modeling contribution: the first
  time this fleet has explicitly differentiated two different
  licensing concerns within the SAME broad health sector and even the
  SAME jurisdictions.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/hospital/phase_test.clj`'s `treatment-
  administer-never-auto-at-any-phase`/`discharge-authorize-never-
  auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/hospital/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The credential-not-current test/demo correctly applied the
  established SCREENING-op-directly pattern for a seventh consecutive
  vertical -- further evidence that lessons recorded in this fleet's
  ADRs continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `hospital.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `observation-period-elapsed?` models only a single representative
  minimum post-procedure observation window (4 hours), not a
  procedure-by-procedure survey, nor a full clinical-decision-support/
  discharge-planning engine (procedure-specific recovery protocols,
  readmission-risk scoring, full care-coordination workflow are out of
  scope -- see that fn's own docstring); real hospital-information-
  system integration and ongoing ward/nursing-care workflows are all
  out of scope for this OSS actor -- each operator's responsibility
  (see README's coverage table).
- 36 tests / 172 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All eighteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (86) overlaps with `8620` |
| Keep `cloud-itonami-isic-8610` at `:blueprint` only | ❌ | The standing direction continues past `8890`; inpatient hospital care is a natural, well-precedented next domain, deepening this fleet's human-health coverage with a genuinely different care model than `8620`'s outpatient clinic |
| Cite the SAME practitioner-licensing bodies `clinic.facts` uses (reuse the catalog wholesale) | ❌ | A hospital's own institutional operating license/accreditation is a genuinely distinct regulatory concern from an individual clinician's practice license -- citing institutional regulators (Medical Care Act/CMS CoP/CQC/G-BA) rather than practitioner boards (医師法/FSMB/GMC-GDC/Bundesärztekammer) is the honest, domain-accurate choice even where it means two catalogs share the same jurisdictions |
| Test `credential-not-current-violations` via an actuation op against an unscreened admission (the shape `parksafety`'s ORIGINAL, buggy test used) | ❌ | Already proven wrong by `parksafety`'s ADR-2607071922 Decision 5 and reconfirmed by six later siblings' ADR-0001s -- a failing screen never persists its payload to the store, so the actuation ops alone cannot discover the bad ground-truth flag through this check family; this build tested the SCREENING op directly from the start |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/hospital`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`, first eighteen post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-8610/docs/adr/0001-architecture.md` (this ADR)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
