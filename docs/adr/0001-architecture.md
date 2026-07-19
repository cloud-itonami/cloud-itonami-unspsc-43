# ADR-0001: TriageAdvisor ⊣ IT Asset Recovery Governor architecture

## Status

Accepted. `cloud-itonami-unspsc-43` promoted from blueprint to
`:implemented`, following the verified fresh-scaffold protocol
established by prior actors in this fleet.

## Context

`cloud-itonami-unspsc-43` publishes an OSS blueprint for an
independent IT asset disposition (ITAD) operator: device-intake data
logging (category/functional-status/wipe-level/battery-status),
data-wipe scheduling against an intake device, battery-hazard/
unverified-sensitive-data concern flagging, and resell/refurbish/
certified-recycle disposition proposal against a device. Like every
actor in this fleet, the blueprint alone is not an implementation:
this ADR records the governed-actor architecture that promotes it to
real, tested code, following the same langgraph StateGraph +
independent Governor + Phase 0->3 rollout pattern established across
the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-3091` (Manufacture of
motorcycles, `motomfg`): both are back-office coordination actors with
a real safety/compliance dimension, and both share the same four-op
shape (`:log-X`/`:schedule-Y`/`:flag-safety-concern`/`:coordinate-Z`
or `:propose-Z`). Unlike `motomfg` (and unlike this fleet's other
richer siblings), this vertical has only ONE gated entity type,
`devices` -- not two. ITAD's real workflow genuinely centers on a
single device moving through intake -> triage -> wipe -> disposition;
there is no natural second entity analogous to `motomfg.equipment`
(the closest candidate, the sorting/inspection robot itself, is
infrastructure this actor DRIVES, not a record this actor gates
actions AGAINST -- see README `Robotics premise`). `:schedule-data-
wipe` and `:propose-disposition` therefore both gate against the SAME
`devices` entity's own `:verified?`/`:registered?` ground truth (one
combined `device-not-verified-violations` check, not two duplicated
near-identical checks) -- an intentional, documented departure from
the two-entity shape, not an oversight.

This vertical has NO pre-existing `kotoba-lang/itad`-style capability
library to wrap (verified: no such repo exists). This build therefore
uses self-contained domain logic -- pure functions in `itad.registry`
(device verification, data-wipe-sufficiency recompute, category/
functional-status/wipe-level/disposition validation) are re-verified
independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `motomfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:it-asset-recovery-governor`, was grep-verified UNIQUE fleet-wide
(`grep -rl it-asset-recovery-governor orgs/cloud-itonami`, zero other
hits before this repo's implementation) before writing any code here.

### Decision 0: Real-standard grounding (verified, not fabricated)

This build cites exactly two real, well-documented standards, both
web-verified 2026-07-19 against their own authoritative sources before
being referenced in code or docs:

- **NIST SP 800-88, 'Guidelines for Media Sanitization'** (current
  edition Rev. 2, published September 2025, verified against
  csrc.nist.gov/pubs/sp/800/88 -- the withdrawn Rev. 1 dates to
  December 2014). Defines the Clear/Purge/Destroy sanitization-
  category taxonomy `itad.registry/valid-wipe-levels` uses directly
  (plus `:none` for a not-yet-wiped device). This build encodes the
  real data-security norm that Clear alone is insufficient for a
  device leaving custody with its media physically intact
  (resell/refurbish) -- `itad.registry/wipe-level-sufficient-for-
  disposition?` requires Purge or Destroy for those two dispositions,
  and does not gate `:certified-recycle` the same way (the media is
  physically destroyed downstream, so the sanitization-level nuance
  does not apply the same way).
- **R2v3 ('Responsible Recycling' Standard, Version 3)**, maintained
  by SERI (Sustainable Electronics Recycling International), ANSI-
  approved, verified against sustainableelectronics.org/welcome-to-
  r2v3 -- the ITAD/electronics-recycling industry's own conformance
  standard covering environmental, worker-safety and data-security
  practice. This actor is never the accredited certification body --
  see `itad.governor/certification-authority-blocked-violations`.

No fabricated citation (no invented statute number, no invented
agency, no invented certification body) is used anywhere in this
build. Device-category/functional-status closed sets are domain
vocabulary, not standards citations, and are documented as such.

### Decision 0b: `:disposition` naming overlap (documented, not a bug)

ITAD's own business vocabulary uses "disposition" (resell/refurbish/
certified-recycle) as its central domain concept -- the same English
word this fleet's actor-routing layer already uses for its own
`:commit`/`:hold`/`:escalate` decision (every sibling actor's
`operation.cljc`/`phase.cljc` channel and `commit-fact`/`hold-fact`
map key). The two senses never appear in the same map (the domain
value lives under a proposal's `:value :disposition` / a device's own
`:disposition` field; the actor-routing value lives under the
top-level `:disposition` channel and `hold-fact`/`commit-fact`'s own
`:disposition` key) -- see `itad.phase/verdict->disposition`'s
docstring for the explicit disambiguation. Renaming either sense would
break parity with every sibling actor's own vocabulary or invent an
artificial synonym for ITAD's own real, correct industry term; keeping
both, documented, is the more honest choice.

## Decision

### Decision 1: Self-contained domain logic (no external ITAD capability library to wrap)

The device-verification / data-wipe-sufficiency / category /
functional-status / wipe-level / disposition validation functions
live as pure functions in `itad.registry` and are re-verified
independently by `itad.governor` -- the same "ground truth, not
self-report" discipline established across prior actors (most
directly `motomfg.registry`).

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of ITAD
operations. It does NOT:
- Actuate a shredder, degausser, or wipe-station directly
- Make data-security or disposition-authorization decisions (exclusive to the human ITAD coordinator / accredited R2v3 auditor)
- Actuate any destruction equipment
- Self-issue an R2v3-conformance/data-destruction certification mark

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human ITAD-coordinator
approval. This is not a replacement for the coordinator's authority or
the accredited certification body's authority -- it is a
proposal-screening and documentation layer.

**CRITICAL SAFETY/DATA-SECURITY BOUNDARY**: ITAD touches both a
physical-safety domain (lithium-ion battery-hazard risk) and a
data-security domain (unverified sensitive data on a device leaving
custody). Safety-concern flagging NEVER auto-commits. All such
concerns escalate immediately to human review, and a resale/
refurbishment disposition can never commit against a device whose own
recorded wipe-level has not independently been verified sufficient.

### Decision 3: Safety-concern escalation -- always human sign-off

`:flag-safety-concern` (battery-hazard, thermal-runaway risk,
unverified-sensitive-data concern) ALWAYS escalates, never
auto-commits. This is not a "low-stakes proposal" -- it is a
circuit-breaker that must reach human authority.

### Decision 4: One entity, two independently-verified gates plus a data-security gate

Unlike `motomfg`'s two-entity shape, this vertical gates BOTH
`:schedule-data-wipe` and `:propose-disposition` against the SAME
`devices` entity's own `:verified?`/`:registered?` fields (see
Decision 0 above for why). `:propose-disposition` layers a SECOND,
independent ground-truth check on top: if the proposed disposition is
data-bearing (`:resell`/`:refurbish`), the device's own recorded
`:wipe-level` must independently be re-verified as Purge or Destroy
(NIST SP 800-88) -- never taken on the advisor's self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into thirteen concrete
checks in `itad.governor`, mirroring `motomfg.governor`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. The device record must be independently verified/registered before any action is taken against it, and a data-bearing disposition's wipe-sufficiency must independently recompute against the device's own logged wipe-level
2. Proposals must be `:effect :propose` only (never direct destruction-equipment control)
3. Direct destruction-equipment actuation, or self-issued R2v3-conformance/data-destruction certification, is permanently blocked
4. The op allowlist is closed -- `:log-device-intake`/`:schedule-data-wipe`/`:flag-safety-concern`/`:propose-disposition` only

## Consequences

(+) IT asset disposition operations back-office now has a documented,
governed, auditable coordination layer that funnels all decisions
through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world destruction-equipment actuation
requires human ITAD-coordinator sign-off, and no R2v3-conformance/
data-destruction certification can ever be self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into thirteen concrete governor checks) protect against scope creep
into unauthorized destruction-equipment actuation, certification
self-issuance, or releasing a device for resale with an insufficient
data-wipe. Safety concerns are a circuit-breaker, not a threshold.

(+) Safety-critical AND data-security discipline are both explicit:
safety-concern flagging cannot be rate-limited, suppressed, or
auto-decided by phase gate, and a resale/refurbishment disposition can
never commit without an independently-verified Purge/Destroy-level
wipe already on the device's own record.

(-) Still a simulation/proposal layer, not a real ITAD control system.
Destruction-equipment actuation, data-security decisions, and
certification issuance remain human-/institution-controlled via
external channels.

(-) No integration with real ITAD databases (wipe-equipment telemetry,
chain-of-custody scanning, R2v3-auditor APIs) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-unspsc-43`: `clojure -M:test` green (74 tests / 207
  assertions, 0 failures, 0 errors, verified from a fresh worktree
  checkout), demo narrative (`clojure -M:dev:run`) exercises proposal
  submission, escalation, and every HARD-hold scenario directly
  (not-propose-effect, unknown-op, device-not-verified for both gated
  ops, insufficient-wipe-level, destruction-actuate-blocked,
  certification-authority-blocked, wipe-already-scheduled, invalid-
  category, invalid-functional-status, invalid-wipe-level), exit code
  0.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
- NIST SP 800-88 Rev. 2 and R2v3 (SERI) were web-verified 2026-07-19
  against csrc.nist.gov / sustainableelectronics.org before being
  cited in `itad.registry` and `itad.governor` docstrings -- no
  fabricated citation.
