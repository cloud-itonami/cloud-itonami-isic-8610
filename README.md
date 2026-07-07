# cloud-itonami-isic-8610

Open Business Blueprint for **ISIC Rev.5 8610**: Hospital activities.
This repository publishes a hospital actor -- inpatient-admission
intake, jurisdiction assessment, credential screening, treatment
administration and discharge authorization -- as an OSS business that
any qualified, licensed hospital operator can fork, deploy, run,
improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103),
[`9602`](https://github.com/cloud-itonami/cloud-itonami-isic-9602),
[`9000`](https://github.com/cloud-itonami/cloud-itonami-isic-9000),
[`8890`](https://github.com/cloud-itonami/cloud-itonami-isic-8890)) --
a second human-health vertical (ISIC division 86) in this fleet,
alongside `8620`'s clinic, but for inpatient hospital care rather than
outpatient practice. Here it is **HospitalOps-LLM ⊣ Clinical
Oversight Governor**.

> **Why an actor layer at all?** An LLM is great at drafting an
> inpatient-admission summary, normalizing records, and checking
> whether an admission's own post-procedure observation window has
> actually elapsed -- but it has **no notion of which jurisdiction's
> hospital-institution licensing requirements are official, no
> license to administer a real treatment or authorize a real
> discharge, and no way to know on its own whether the treating
> clinician's own license is actually current**. Letting it
> administer a treatment or authorize a discharge directly invites
> fabricated jurisdiction citations, a discharge authorized before the
> minimum post-procedure observation window has elapsed, and a lapsed
> clinician license being quietly relied upon -- and liability, and
> patient-safety risk, for whoever runs it. This project seals the
> HospitalOps-LLM into a single node and wraps it with an independent
> **Clinical Oversight Governor**, a human **approval workflow**, and
> an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers inpatient-admission intake through jurisdiction
assessment, credential screening, treatment administration and
discharge authorization. It does **not**, by itself, hold any license
required to operate a hospital in a given jurisdiction, and it does
not claim to. It also does **not** model a full clinical-decision-
support/discharge-planning engine -- no procedure-specific recovery
protocols, no readmission-risk scoring, no full care-coordination
workflow (see `hospital.registry/minimum-observation-hours`'s own
docstring for the honest simplification this makes: a single
representative minimum post-procedure observation window, not a
procedure-by-procedure survey of every recovery-protocol variant).
Whoever deploys and operates a live instance (a licensed hospital
operator) supplies any jurisdiction-specific license, the real
clinical/medical expertise and the real hospital-information-system
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**Administering a real treatment or authorizing a real discharge is
never autonomous, at any phase, by construction.** Two independent
layers enforce this (`hospital.governor`'s `:actuation/administer-
treatment`/`:actuation/authorize-discharge` high-stakes gate and
`hospital.phase`'s phase table, which never puts `:treatment/
administer`/`:discharge/authorize` in any phase's `:auto` set) -- see
`hospital.phase`'s docstring and `test/hospital/phase_test.clj`'s
`treatment-administer-never-auto-at-any-phase`/`discharge-authorize-
never-auto-at-any-phase`. The actor may draft, check and recommend; a
human licensed clinician is always the one who actually administers a
treatment or authorizes a discharge. Like `6512`/`6622`/`6520`/`6530`/
`6820`/`6920`/`6611`/`8530`/`9200`/`9521`/`8730`/`9102`/`9103`/`8890`,
this actor has TWO actuation events.

## The core contract

```
admission intake + jurisdiction facts (hospital.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ HospitalOps- │ ─────────────▶ │ Clinical                    │  (independent system)
   │ LLM (sealed) │  + citations    │ Oversight Governor:          │
   └──────────────┘                 │ spec-basis · evidence-       │
                             commit ◀────┼──────────▶ hold │ incomplete ·
                                 │             │           │ observation-period-
                           record + ledger  escalate ─▶ human   insufficient (MINIMUM-
                                             (ALWAYS for         wait temporal) ·
                                              :treatment/            credential-not-current
                                              administer /            (unconditional) ·
                                              :discharge/authorize)     already-treated/-discharged
```

**The HospitalOps-LLM never administers a treatment or authorizes a
discharge the Clinical Oversight Governor would reject, and never does
so without a human sign-off.** Hard violations (fabricated
jurisdiction requirements; unsupported clinical evidence; an
insufficient post-procedure observation period; a not-current
clinician license; a double administration or discharge) force
**hold** and *cannot* be approved past; a clean administration/
discharge proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (treatment administration, discharge authorization) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a patient-transport and
vitals-monitoring robot assists physical ward operations, under the
actor, gated by the independent **Clinical Oversight Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Clinical Oversight Governor, treatment-administration + discharge-authorization draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`8610`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`, this vertical's
admission records are practice-specific rather than a shared cross-
operator data contract, so `hospital.*` runs on the generic identity/
forms/dmn/bpmn/audit-ledger stack only -- no bespoke domain capability
lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/hospital/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + separate treatment-administration/discharge-authorization history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded admission, and the double-administration/double-discharge guards check dedicated `:treated?`/`:discharged?` booleans rather than a `:status` value |
| `src/hospital/registry.cljc` | Treatment-administration + discharge-authorization draft records, plus `observation-period-elapsed?`/`minimum-observation-hours` -- the THIRD instance of this fleet's MINIMUM-threshold temporal-sufficiency shape (`veterinary`/`funeral` established the first two), applied UNCONDITIONALLY (every discharge needs the same minimum observation window) |
| `src/hospital/facts.cljc` | Per-jurisdiction hospital-institution licensing catalog with an official spec-basis citation per entry, honest coverage reporting -- INSTITUTIONAL regulators, distinct from `clinic.facts`'s individual-practitioner-licensing bodies |
| `src/hospital/hospitalopsllm.cljc` | **HospitalOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/credential-screening/treatment-administration/discharge-authorization proposals |
| `src/hospital/governor.cljc` | **Clinical Oversight Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · observation-period-insufficient, pure ground-truth MINIMUM-threshold recompute · credential-not-current, unconditional evaluation, the SIXTEENTH grounding of this discipline and THIRD specifically for the credential-not-current concept) + already-treated/already-discharged guards + 1 soft (confidence/actuation gate) |
| `src/hospital/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (both administration and discharge always human; admission intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/hospital/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/hospital/sim.cljc` | demo driver |
| `test/hospital/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers inpatient-admission intake through jurisdiction
assessment, credential screening, treatment administration and
discharge authorization -- the core governed lifecycle this
blueprint's own `docs/business-model.md` names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Admission intake + per-jurisdiction hospital-institution checklisting, HARD-gated on an official spec-basis citation (`:admission/intake`/`:jurisdiction/assess`) | A full clinical-decision-support/discharge-planning engine (procedure-specific recovery protocols, readmission-risk scoring, full care-coordination workflow -- see `observation-period-elapsed?`'s docstring) |
| Credential screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:credential/screen`) | Real hospital-information-system integration, billing/insurance-claims workflows |
| Treatment administration, HARD-gated on full clinical evidence and a double-administration guard (`:treatment/administer`) | Ongoing ward/nursing-care workflows themselves |
| Discharge authorization, HARD-gated on the admission's own post-procedure observation period having elapsed and a double-discharge guard (`:discharge/authorize`) | |
| Immutable audit ledger for every intake/assessment/screening/administration/discharge decision | |

Extending coverage is additive: add the next gate (e.g. a readmission-
risk check) as its own governed op with its own HARD checks and tests,
following the SAME "an independent governor re-verifies against the
actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`hospital.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `hospital.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `hospital.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `HospitalOps-LLM` + `Clinical Oversight Governor`
run as real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, modeled closely on
the twenty-six prior actors' architecture. See `docs/adr/0001-
architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
