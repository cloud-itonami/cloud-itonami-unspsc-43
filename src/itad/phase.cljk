(ns itad.phase
  "Phase 0->3 staged rollout for the independent IT asset recovery and
  e-waste refurbishment coordination actor.

    Phase 0  read-only          -- no writes, still governor-gated.
    Phase 1  assisted-intake    -- device-intake logging allowed,
                                    every write needs human approval.
    Phase 2  assisted-coordinate -- adds safety-concern flags and
                                    disposition proposals, still
                                    approval.
    Phase 3  supervised-auto    -- adds data-wipe scheduling (still
                                    always approval -- see below);
                                    governor-clean, high-confidence
                                    `:log-device-intake` (no physical/
                                    financial risk) may auto-commit.

  `:schedule-data-wipe` is deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- a permanent structural fact, not a
  rollout milestone still to come. Scheduling a real data wipe means a
  device is actually queued for sanitization equipment; it is always a
  human coordinator's call. `itad.governor`'s `destruction-actuate-
  blocked-violations` HARD-blocks actuate attempts unconditionally,
  and the confidence/high-stakes gate independently never lets
  `:flag-safety-concern` auto-commit either -- multiple independent
  layers agree on where this actor's authority ends. Like every prior
  sibling's phase-3 `:auto` set, this domain has only ONE member
  (`:log-device-intake`) -- no separate no-risk lifecycle distinct
  from ordinary record logging.")

(def write-ops
  #{:log-device-intake :schedule-data-wipe
    :flag-safety-concern :propose-disposition})

;; NOTE the invariant: `:schedule-data-wipe` is a member of
;; `write-ops` (governor-gated like any write) but is NEVER a member
;; of any phase's `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed
  to auto-commit when governor-clean>}."
  {0 {:label "read-only"           :writes #{}                                            :auto #{}}
   1 {:label "assisted-intake"     :writes #{:log-device-intake}                           :auto #{}}
   2 {:label "assisted-coordinate" :writes #{:log-device-intake :flag-safety-concern
                                             :propose-disposition}                          :auto #{}}
   3 {:label "supervised-auto"     :writes write-ops
      :auto #{:log-device-intake}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:schedule-data-wipe` is never auto-eligible at any phase, so it
    always escalates once the governor clears it (or holds if the
    governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an IT Asset Recovery Governor verdict to a base disposition
  before the phase gate. NOTE: this `:disposition` is the ACTOR's own
  routing decision (`:commit`/`:hold`/`:escalate`) -- an unrelated
  namesake of the domain `:disposition` field on a device
  (`:resell`/`:refurbish`/`:certified-recycle`, see `itad.registry`).
  The two never appear in the same map; ITAD's own vocabulary and this
  fleet's actor-routing vocabulary happen to share the English word."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
