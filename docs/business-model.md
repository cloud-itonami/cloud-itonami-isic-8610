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

- no treatment is administered and no discharge is authorized without
  human sign-off (a licensed clinician)
- a fabricated jurisdiction citation, incomplete clinical evidence, an
  insufficient post-procedure observation period, or a not-current
  clinician license -- each forces a hold, not an override
- an admission's treatment/discharge cannot each be finalized twice: a
  double-administration or double-discharge attempt is held off this
  actor's own admission facts alone, with no upstream comparison
  needed
- every intake, assessment, screening and administration/discharge
  path is auditable
- patient health data stays outside Git
- emergency manual override paths remain outside LLM control
