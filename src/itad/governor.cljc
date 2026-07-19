(ns itad.governor
  "IT Asset Recovery Governor -- the independent compliance layer that
  earns the TriageAdvisor the right to commit. The advisor has no
  notion of whether a device it wants to schedule a data wipe against
  has actually been functional-tested/registered, whether a proposal
  secretly tries to ACTUATE (rather than merely draft-schedule)
  destruction equipment, whether a proposal secretly tries to
  self-issue an R2v3-conformance/data-destruction CERTIFICATION (an
  authority this actor never holds), whether a disposition proposal
  would release a device for resale/refurbishment with an
  insufficient data-wipe level (NIST SP 800-88), or when an act stops
  being a coordination proposal and becomes direct destruction-
  equipment control, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:it-asset-recovery-governor` (see
  docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:shredder/actuate`
                                       or `:degausser/run`) is the
                                       'direct destruction-equipment
                                       control' scope violation this
                                       actor must NEVER perform --
                                       HARD, PERMANENT, unconditional.
    4. Destruction-equipment-
       actuate blocked            -- for `:schedule-data-wipe`, does
                                       the proposal's own `:value`
                                       declare `:actuate-equipment?
                                       true`? Directly actuating a
                                       shredder/degausser/wipe-station
                                       is this actor's other permanent
                                       scope boundary (see README `What
                                       this actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `itad.phase`: this op is never a
                                       member of any phase's `:auto`
                                       set either -- two independent
                                       layers agree).
    5. Certification authority
       blocked                     -- ANY proposal (any op) whose own
                                       `:value`/`:patch` declares
                                       `:issue-certification? true` is
                                       attempting to self-issue an
                                       R2v3-conformance/data-
                                       destruction certification mark
                                       -- an authority exclusively
                                       reserved to the accredited R2v3
                                       certification body, never this
                                       actor -- HARD, PERMANENT,
                                       unconditional.
    6. Device not verified/
       registered                  -- for `:schedule-data-wipe` AND
                                       `:propose-disposition`,
                                       INDEPENDENTLY verify the
                                       referenced device's own
                                       `:verified?` AND `:registered?`
                                       are both true
                                       (`itad.registry/device-ready?`)
                                       -- never trust the advisor's
                                       own rationale about
                                       verification/registration
                                       status. Grounded in this
                                       blueprint's own HARD invariant
                                       ('device record must be
                                       independently verified/
                                       registered before any action').
    7. Wipe already scheduled      -- for `:schedule-data-wipe`,
                                       refuses to schedule the SAME
                                       wipe record twice, off a
                                       dedicated `:scheduled?` fact
                                       (never a `:status` value).
    8. Disposition already
       proposed                    -- for `:propose-disposition`,
                                       refuses to propose the SAME
                                       disposition record twice, off a
                                       dedicated `:proposed?` fact.
    9. Insufficient wipe level for
       data-bearing disposition    -- for `:propose-disposition`, if
                                       `:disposition` is a data-
                                       bearing disposition (`:resell`/
                                       `:refurbish`), INDEPENDENTLY
                                       verify the device's own
                                       recorded `:wipe-level` has
                                       reached Purge or Destroy
                                       (`itad.registry/wipe-level-
                                       sufficient-for-disposition?`,
                                       NIST SP 800-88) -- ground truth
                                       from the device's own permanent
                                       fields, never a self-reported
                                       wipe-completion claim.
   10. Invalid disposition value   -- for `:propose-disposition`, if
                                       `:disposition` is outside the
                                       closed known set
                                       (`itad.registry/disposition-
                                       valid?`), the proposal is
                                       rejected rather than let a
                                       fabricated disposition through.
   11. Invalid category            -- for `:log-device-intake`, if the
                                       patch declares a `:category`
                                       outside the closed known set
                                       (`itad.registry/category-
                                       valid?`), the device record is
                                       rejected rather than let a
                                       fabricated category through.
   12. Invalid functional-status   -- for `:log-device-intake`, if the
                                       patch declares a
                                       `:functional-status` outside
                                       the closed known set
                                       (`itad.registry/functional-
                                       status-valid?`), the device
                                       record is rejected rather than
                                       let a fabricated status through.
   13. Invalid wipe-level          -- for `:log-device-intake`, if the
                                       patch declares a `:wipe-level`
                                       outside the closed NIST SP
                                       800-88 taxonomy
                                       (`itad.registry/wipe-level-
                                       valid?`), the device record is
                                       rejected rather than let a
                                       fabricated wipe-level through
                                       (this is the SAME field
                                       `:propose-disposition`'s HARD
                                       gate depends on -- rejecting
                                       fabricated values at intake is
                                       what makes the disposition gate
                                       trustworthy).
   14. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/safety-concern`,
                                       ALWAYS set for `:flag-safety-
                                       concern`) -- escalate to a human
                                       ITAD coordinator. SOFT: the
                                       human may approve."
  (:require [itad.registry :as registry]
            [itad.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-device-intake :schedule-data-wipe
    :flag-safety-concern :propose-disposition})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct destruction-
  equipment-control effect."
  #{:device/upsert :wipe/schedule
    :safety-concern/flag :disposition/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Safety concerns are the one op in this domain that always demands
  human eyes regardless of confidence."
  #{:coordination/safety-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- destruction-control-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct destruction-equipment control, a fabricated
  actuation effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :destruction-control-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は破壊機器への直接操作に該当する可能性があり、恒久的に禁止")}]))

(defn- destruction-actuate-blocked-violations
  "HARD, PERMANENT, unconditional: a `:schedule-data-wipe` proposal
  whose own `:value` declares `:actuate-equipment? true` is attempting
  to directly actuate a shredder/degausser/wipe-station -- this actor
  may only ever propose/schedule a DRAFT data wipe, never actuate
  destruction equipment directly. No override, ever."
  [{:keys [op]} proposal]
  (when (and (= op :schedule-data-wipe)
             (true? (:actuate-equipment? (:value proposal))))
    [{:rule :destruction-actuate-blocked
      :detail "破壊機器への直接操作(actuate)提案は恒久的に禁止 -- 提案(draft)のみ許可"}]))

(defn- certification-authority-blocked-violations
  "HARD, PERMANENT, unconditional: ANY proposal (any op) whose own
  `:value`/`:patch` declares `:issue-certification? true` is attempting
  to self-issue an R2v3-conformance/data-destruction certification
  mark -- an authority exclusively reserved to the accredited R2v3
  certification body, never this actor. No phase and no human approval
  can ever override this."
  [proposal]
  (let [payload (or (:value proposal) (:patch proposal))]
    (when (true? (:issue-certification? payload))
      [{:rule :certification-authority-blocked
        :detail "R2v3適合証明・データ破壊証明の自己発行提案は恒久的に禁止 -- 認定機関の専権事項"}])))

(defn- device-not-verified-violations
  "For `:schedule-data-wipe` AND `:propose-disposition`, INDEPENDENTLY
  verify the referenced device exists and is both `:verified?` AND
  `:registered?` -- never trust the advisor's own report. This is the
  HARD invariant ('device record must be independently verified/
  registered before any action')."
  [{:keys [op]} proposal st]
  (when (contains? #{:schedule-data-wipe :propose-disposition} op)
    (let [device-id (:device-id (:value proposal))
          d (and device-id (store/device st device-id))]
      (when-not (and d (registry/device-ready? d))
        [{:rule :device-not-verified
          :detail (str device-id " は未検証または未登録、もしくは存在しない -- 検証済み・登録済み端末記録が無い状態での作業提案")}]))))

(defn- wipe-already-scheduled-violations
  "For `:schedule-data-wipe`, refuses to schedule the SAME wipe record
  twice, off a dedicated `:scheduled?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-data-wipe)
    (when (store/wipe-already-scheduled? st subject)
      [{:rule :wipe-already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- disposition-already-proposed-violations
  "For `:propose-disposition`, refuses to propose the SAME disposition
  record twice, off a dedicated `:proposed?` fact."
  [{:keys [op subject]} st]
  (when (= op :propose-disposition)
    (when (store/disposition-already-proposed? st subject)
      [{:rule :disposition-already-proposed
        :detail (str subject " は既に処分提案済み")}])))

(defn- insufficient-wipe-level-violations
  "For `:propose-disposition`, if the proposal's own `:disposition` is
  a data-bearing disposition (`:resell`/`:refurbish`), INDEPENDENTLY
  verify the device's own recorded `:wipe-level` has reached Purge or
  Destroy (NIST SP 800-88) -- ground truth from the device's own
  permanent fields, never a self-reported wipe-completion claim."
  [{:keys [op]} proposal st]
  (when (= op :propose-disposition)
    (let [{:keys [device-id disposition]} (:value proposal)
          d (and device-id (store/device st device-id))]
      (when (and d (not (registry/wipe-level-sufficient-for-disposition? d disposition)))
        [{:rule :insufficient-wipe-level
          :detail (str device-id " の記録済みデータ消去レベル(" (:wipe-level d)
                       ")は " disposition " への処分に不十分 -- NIST SP 800-88 の Purge/Destroy 未到達")}]))))

(defn- invalid-disposition-violations
  "For `:propose-disposition`, if `:disposition` is outside the closed
  known set, reject rather than let a fabricated disposition through."
  [{:keys [op]} proposal]
  (when (= op :propose-disposition)
    (let [disposition (:disposition (:value proposal))]
      (when (and (some? disposition) (not (registry/disposition-valid? disposition)))
        [{:rule :invalid-disposition
          :detail (str disposition " は既知の disposition 値ではない")}]))))

(defn- invalid-category-violations
  "For `:log-device-intake`, if the patch declares a `:category`
  outside the closed known set, reject rather than let a fabricated
  category through."
  [{:keys [op]} proposal]
  (when (= op :log-device-intake)
    (let [category (:category (:value proposal))]
      (when (and (some? category) (not (registry/category-valid? category)))
        [{:rule :invalid-category
          :detail (str category " は既知の category 値ではない")}]))))

(defn- invalid-functional-status-violations
  "For `:log-device-intake`, if the patch declares a
  `:functional-status` outside the closed known set, reject rather
  than let a fabricated status through."
  [{:keys [op]} proposal]
  (when (= op :log-device-intake)
    (let [fs (:functional-status (:value proposal))]
      (when (and (some? fs) (not (registry/functional-status-valid? fs)))
        [{:rule :invalid-functional-status
          :detail (str fs " は既知の functional-status 値ではない")}]))))

(defn- invalid-wipe-level-violations
  "For `:log-device-intake`, if the patch declares a `:wipe-level`
  outside the closed NIST SP 800-88 taxonomy, reject rather than let a
  fabricated wipe-level through -- this is the same field
  `:propose-disposition`'s HARD gate depends on."
  [{:keys [op]} proposal]
  (when (= op :log-device-intake)
    (let [wl (:wipe-level (:value proposal))]
      (when (and (some? wl) (not (registry/wipe-level-valid? wl)))
        [{:rule :invalid-wipe-level
          :detail (str wl " は既知の wipe-level 値ではない (NIST SP 800-88: none/clear/purge/destroy)")}]))))

(defn check
  "Censors a TriageAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (destruction-control-blocked-violations proposal)
                           (destruction-actuate-blocked-violations request proposal)
                           (certification-authority-blocked-violations proposal)
                           (device-not-verified-violations request proposal st)
                           (wipe-already-scheduled-violations request st)
                           (disposition-already-proposed-violations request st)
                           (insufficient-wipe-level-violations request proposal st)
                           (invalid-disposition-violations request proposal)
                           (invalid-category-violations request proposal)
                           (invalid-functional-status-violations request proposal)
                           (invalid-wipe-level-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
