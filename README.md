# cloud-itonami-unspsc-43

Open UNSPSC Blueprint for **UNSPSC segment 43**: Information Technology
Broadcasting and Telecommunications.

This repository designs a forkable OSS business for an independent IT
asset recovery and e-waste refurbishment contractor: a sorting/inspection
robot performs device triage and data-wipe verification under a
governor-gated actor, so an independent ITAD (IT Asset Disposition)
operator keeps auditable chain-of-custody and data-destruction records
instead of renting a closed ITAD SaaS. Complements
[`cloud-itonami-3830`](https://github.com/cloud-itonami/cloud-itonami-3830)
(Local Materials Recovery) at the electronics-specific layer.

**Status: design blueprint, no code implemented yet.** This repository
has zero files under `src/` and no `test/` directory — the Triage
Advisor and IT Asset Recovery Governor described below do not exist in
code. It is not (yet) a governed Advisor⊣Governor actuation actor; the
Core Contract section specifies what that pipeline is intended to
enforce once built, not current behavior. See
[`cloud-itonami-isco-1324`](https://github.com/cloud-itonami/cloud-itonami-isco-1324)
for this fleet's minimal implemented reference (`actor`/`advisor`/
`governor`/`store`), and the `cloud-itonami-assoc-*` /
`cloud-itonami-municipality-*` / `cloud-itonami-lei-*` repos for this
fleet's honest not-an-actuation-actor disclaimer pattern.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a sorting/inspection robot
performs device triage, functional testing and data-wipe verification
under an actor that proposes a resell/refurbish/recycle disposition and an
independent **IT Asset Recovery Governor** that gates it. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions (a
device containing unverified sensitive data, or a battery-safety hazard)
require human sign-off before any resale or recycling.

## Core Contract (design intent — not yet implemented)

```text
device intake + chain-of-custody registry
        |
        v
Triage Advisor -> IT Asset Recovery Governor -> disposition, or human sign-off
        |
        v
robot triage actions (gated) + data-wipe/disposition record + audit ledger
```

**No code exists yet in this repo** — no `src/`, no `test/`, only this
design document plus `blueprint.edn` and `docs/`. Once built, no
automated triage will be able to dispatch a robot action the governor
refuses, release a device for resale without verified data-wipe
evidence, or suppress a chain-of-custody record without governor
approval and audit evidence — but none of that is enforced today.

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
