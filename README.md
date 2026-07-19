# cloud-itonami-unspsc-43

Open UNSPSC Blueprint (implemented actor) for **UNSPSC segment 43**:
Information Technology Broadcasting and Telecommunications.

This repository publishes a forkable OSS business for an independent IT
asset recovery and e-waste refurbishment contractor: a sorting/inspection
robot performs device triage and data-wipe verification under a
governor-gated actor, so an independent ITAD (IT Asset Disposition)
operator keeps auditable chain-of-custody and data-destruction records
instead of renting a closed ITAD SaaS. Complements
[`cloud-itonami-3830`](https://github.com/cloud-itonami/cloud-itonami-3830)
(Local Materials Recovery) at the electronics-specific layer.

Built on this workspace's `langgraph-clj` StateGraph runtime -- the
same actor pattern as [`cloud-itonami-isic-3091`](https://github.com/cloud-itonami/cloud-itonami-isic-3091)
(MotoAdvisor ⊣ Motorcycle Plant Operations Governor, whose structure
this actor ports, adapted to a single-entity `devices` gate -- see
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md)
Decision 0). Here it is **TriageAdvisor ⊣ IT Asset Recovery
Governor**.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a sorting/inspection robot
performs device triage, functional testing and data-wipe verification
under an actor that proposes a resell/refurbish/recycle disposition and an
independent **IT Asset Recovery Governor** that gates it. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions (a
device containing unverified sensitive data, or a battery-safety hazard)
require human sign-off before any resale or recycling.

## Core Contract

```text
device intake + chain-of-custody registry
        |
        v
Triage Advisor -> IT Asset Recovery Governor -> disposition, or human sign-off
        |
        v
robot triage actions (gated) + data-wipe/disposition record + audit ledger
```

No automated triage can dispatch a robot action the governor refuses,
release a device for resale without an independently-verified
NIST SP 800-88 Purge/Destroy-level data wipe already on record, or
suppress a chain-of-custody record without governor approval and audit
evidence.

## Implementation

Portable `.cljc` namespaces under `src/itad/`:

- `registry` -- pure domain logic: device verified+registered checks,
  data-wipe-sufficiency recompute against NIST SP 800-88's own Clear/
  Purge/Destroy taxonomy, category/functional-status/wipe-level/
  disposition validation, draft wipe-schedule/disposition-record
  construction. R2v3 (SERI) is the referenced certification standard
  this actor is never the accredited body for -- see
  [`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md)
  Decision 0 for the verified citation record.
- `store` -- SSoT behind a `Store` protocol (`MemStore`); devices,
  wipes, dispositions, safety concerns and the audit ledger all live
  here.
- `advisor` -- the contained intelligence node (`mock-advisor` default,
  `llm-advisor` swap-in); returns proposals only.
- `governor` -- the independent IT Asset Recovery Governor (thirteen
  concrete checks, four HARD invariants).
- `phase` -- 0->3 staged rollout; data-wipe scheduling is never
  auto-committed at any phase.
- `operation` -- the StateGraph (1 run = 1 coordination request);
  `sim` drives the demo.

`clojure -M:test` (74 tests, 207 assertions, 0 failures). See
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for
the full design, including why this build gates both write ops
against a single `devices` entity rather than this fleet's usual
two-entity shape.

## Capability layer

Resolves via [`kotoba-lang/unspsc`](https://github.com/kotoba-lang/unspsc)
(UNSPSC segment `43`). Required capabilities:

- :robotics
- :identity
- :telemetry
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
