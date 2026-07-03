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
release a device for resale without verified data-wipe evidence, or
suppress a chain-of-custody record without governor approval and audit
evidence.

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
