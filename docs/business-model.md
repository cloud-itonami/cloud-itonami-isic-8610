# Business Model: Hospital activities

## Classification

- Repository: `cloud-itonami-isic-8610`
- ISIC Rev.5: `8610`
- Activity: hospital activities -- inpatient medical, surgical and diagnostic services under licensed medical staff
- Social impact: care quality, data sovereignty, transparent audit

## Customer

- independent/community hospitals
- cooperative hospital networks
- rural/critical-access hospital operators

## Offer

- patient intake
- triage/diagnostic-plan proposal
- treatment/discharge proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per facility
- support: monthly retainer with SLA
- migration: import from an incumbent hospital-information system
- per-admission processing fee

## Trust Controls

- no treatment or discharge is administered without human sign-off (licensed medical staff)
- a fabricated diagnostic rationale forces a hold, not an override
- every treatment path is auditable
- patient health data stays outside Git
- emergency manual override paths remain outside LLM control
