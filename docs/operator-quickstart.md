# Operator Quickstart

## Prerequisites

- **Clojure CLI** (`clojure` / `clj`) installed
- **Java** (JDK 11+; Clojure handles the rest)
- If you're forking outside this workspace monorepo: override `:local/root` paths in `deps.edn` with git coordinates for `langgraph-clj` and `langchain-clj`

## Run the demo

```bash
clojure -M:dev:run
```

This walks two clean admission lifecycles (treatment administration → discharge authorization) plus five HARD-hold cases through the OperationActor. The output shows:

- Intake, jurisdiction assessment, credential screening, treatment-administration proposal, and discharge-authorization proposal
- Each escalation to human clinician for approval
- Complete audit ledger and draft records for treatment and discharge

This is the fastest way to understand the governor contract in action.

## Run tests

```bash
clojure -M:dev:test
```

Tests verify:
- Governor contract (hard-hold and soft-hold behavior)
- Phase invariants (auto-eligible operations vs. human-gated operations)
- Store parity (MemStore and DatomicStore produce identical results)
- Registry conformance (admission records match expected schemas)
- Facts coverage (jurisdiction catalog has official spec-basis citations)

## Run linting

```bash
clojure -M:lint
```

Static analysis with clj-kondo. Errors fail CI; warnings pass.

## Where the Clinical Oversight Governor lives

The **Clinical Oversight Governor** implementation is in:
- **Primary logic:** `src/hospital/governor.cljc`
- **Stateful actor:** `src/hospital/operation.cljc` (langgraph-clj StateGraph)
- **Phase definitions:** `src/hospital/phase.cljc` (read-only → assisted intake → assisted assess → supervised)

### The governor's role

1. **Validates jurisdiction compliance** — checks that the jurisdiction and hospital-institution license citations exist in `src/hospital/facts.cljc` with an official spec-basis
2. **Screens clinician credentials** — verifies that the treating clinician's license is current (evaluated unconditionally)
3. **Enforces minimum observation period** — verifies that post-procedure observation time has elapsed before authorizing discharge
4. **Prevents double actuation** — guards against administering the same treatment twice or authorizing the same discharge twice
5. **Gathers clinical evidence** — checks that treatment proposals have sufficient supporting evidence
6. **Enforces human approval** — all treatment administrations and discharge authorizations are escalated to a licensed clinician; they are never autonomous

### Hard holds vs. soft holds

- **Hard holds:** Fabricated jurisdiction citations, insufficient clinical evidence, insufficient post-procedure observation time, stale clinician license, or double actuation — these cannot be overridden and force escalation to a human for manual review
- **Soft holds:** Low confidence in the proposal or a general actuation gate — still escalates to human, but may be approved with review

## Jurisdiction coverage

Hospital-institution licensing requirements are seeded in `src/hospital/facts.cljc/catalog` for 4 jurisdictions: JPN, USA, GBR, DEU. This is a starting catalog to prove the governor contract end-to-end, not a claim of global coverage.

**Adding a jurisdiction is additive:** one map entry in `hospital.facts/catalog`, citing a real official source — never fabricate a jurisdiction's requirements to make coverage look bigger.

Run `clojure -M:dev:run` and check the coverage report in the output to see how many requested jurisdictions have official spec-basis citations.

## Deployment options

**Self-host:** Fork this repo, override `deps.edn` dependencies with your own git coordinates (outside a monorepo), and deploy to your own infrastructure. See `docs/operator-guide.md` for minimum production controls.

**Managed hosting:** Register on itonami.cloud for managed deployment, backups, and support. See `docs/business-model.md` for revenue and support options.

## Governance & licensing

- Code and templates are AGPL-3.0-or-later
- Trust controls are non-negotiable: human sign-off for treatment and discharge, hard holds for rule violations
- Audit export required for every hold, approval, and care action
- See `GOVERNANCE.md` for the full framework

## Next steps

1. Run `clojure -M:dev:run` to see the actor in action
2. Read `README.md` for the full architecture and design rationale
3. Read `docs/business-model.md` for customer profiles and open business model
4. Read `docs/operator-guide.md` for production deployment checklist
5. See `docs/adr/0001-architecture.md` for detailed architectural decisions
