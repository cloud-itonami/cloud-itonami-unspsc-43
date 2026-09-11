(ns itad.registry
  "Pure-function domain logic for the independent IT asset recovery
  and e-waste refurbishment coordination actor -- device verification,
  data-wipe-sufficiency recompute against a data-bearing disposition,
  device-category/functional-status/wipe-level validation, plus draft
  wipe-schedule/disposition-record construction.

  Per docs/adr/0001-architecture.md Decision 1, this vertical has NO
  pre-existing `kotoba-lang/itad`-style capability library to wrap
  (verified: no such repo exists). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by
  `itad.governor` -- the same 'ground truth, not self-report'
  discipline established across this fleet (most directly
  `cloud-itonami-isic-3091`'s `motomfg.registry`): never trust a
  proposal's own self-reported wipe-completion status when the input
  needed to independently re-derive it (the device's own recorded
  `:wipe-level`) is already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real ITAD system. It builds the DRAFT record a
  coordinator would keep (a scheduled data-wipe, a proposed
  disposition), not the act of actuating a shredder/degausser/wipe
  station, or issuing an R2v3-conformance/data-destruction
  certification (this actor NEVER does any of those -- see README
  `What this actor does NOT do`).

  SAFETY/DATA-SECURITY GROUNDING (verified 2026-07-19, no fabricated
  citation):

  - **NIST SP 800-88, 'Guidelines for Media Sanitization'** (current
    edition Rev. 2, September 2025 -- verified against
    csrc.nist.gov/pubs/sp/800/88) defines three sanitization
    categories: Clear, Purge, Destroy. `valid-wipe-levels` below uses
    exactly this taxonomy (plus `:none` for a not-yet-wiped device).
    `wipe-level-sufficient-for-disposition?` encodes the real-world
    data-security norm that a device released for RESALE or REFURBISH
    (still functions, data could be recovered from a mere logical
    Clear) must have reached at least Purge before release -- Clear
    alone is insufficient for a device leaving the operator's custody.
  - **R2v3 ('Responsible Recycling' Standard, Version 3)**, maintained
    by SERI (Sustainable Electronics Recycling International) and
    ANSI-approved, is the electronics-recycling/ITAD industry's own
    conformance standard for environmental, worker-safety and
    data-security practice (verified against
    sustainableelectronics.org/welcome-to-r2v3). This actor is never
    the accredited certification body -- see
    `itad.governor/certification-authority-blocked-violations`."
  )

;; ----------------------------- constants -----------------------------

(def valid-device-categories
  "The closed set of intake device-category values a device record may
  declare. Anything else is a fabricated/unrecognized category -- the
  governor HARD-holds rather than let an invented category through."
  #{:laptop :desktop :monitor :mobile-phone :tablet
    :server :networking-equipment :peripheral})

(def valid-functional-statuses
  "The closed set of functional-test-result values a device record may
  declare."
  #{:functional :partial-function :non-functional})

(def valid-wipe-levels
  "The closed set of data-sanitization levels a device record may
  declare, drawn directly from NIST SP 800-88's own Clear/Purge/Destroy
  taxonomy (plus `:none` for a device that has not yet been wiped).
  Anything else is a fabricated/unrecognized wipe level -- the
  governor HARD-holds rather than let an invented level through."
  #{:none :clear :purge :destroy})

(def valid-dispositions
  "The closed set of disposition values a device may be proposed for."
  #{:resell :refurbish :certified-recycle})

(def data-bearing-dispositions
  "Dispositions that keep the device's storage physically intact and
  moving to a NEW custodian outside the operator -- resale and
  refurbishment. These are the dispositions `wipe-level-sufficient-
  for-disposition?` gates; `:certified-recycle` (physical destruction
  downstream) is not gated the same way because the storage media
  itself is being destroyed, not handed to a new owner intact."
  #{:resell :refurbish})

(def sufficient-wipe-levels-for-resale
  "Per NIST SP 800-88: a device leaving the operator's custody with its
  storage physically intact (resale/refurbishment) must have reached
  at least Purge -- a mere Clear (mandatory overwrite of user-
  addressable space only) does not protect against laboratory
  recovery attacks the way Purge/Destroy do."
  #{:purge :destroy})

;; ----------------------------- device checks -----------------------------

(defn device-verified?
  "Ground-truth check: has `device`'s own record been marked verified
  (i.e. it has actually been functional-tested, not merely referenced
  from an unverified intake patch)? A pure predicate over the
  device's own permanent field -- no proposal inspection needed."
  [device]
  (true? (:verified? device)))

(defn device-registered?
  "Ground-truth check: does `device`'s own record carry a
  `:registered?` true flag (i.e. its chain-of-custody is on file in
  the operator's device registry)? Scheduling a data wipe or proposing
  a disposition against a device that is not on file and registered is
  the exact scope violation this actor's HARD invariant ('device
  record must be independently verified/registered before any
  action') exists to block."
  [device]
  (true? (:registered? device)))

(defn device-ready?
  "Combined ground-truth gate: the device must be both `verified?` AND
  `registered?` before ANY data wipe may be scheduled or disposition
  proposed against it."
  [device]
  (and (device-verified? device) (device-registered? device)))

(defn wipe-level-sufficient-for-disposition?
  "Ground-truth check for a `:propose-disposition` proposal: if
  `disposition` is a data-bearing disposition (`:resell`/`:refurbish`
  -- the device's storage stays physically intact and changes
  custody), is the device's own recorded `:wipe-level` in
  `sufficient-wipe-levels-for-resale` (Purge or Destroy, per NIST SP
  800-88)? `:certified-recycle` is always sufficient regardless of
  wipe-level (physical media destruction happens downstream). Needs no
  proposal inspection -- its inputs are permanent fields already on
  the device's own record."
  [device disposition]
  (if (contains? data-bearing-dispositions disposition)
    (contains? sufficient-wipe-levels-for-resale (:wipe-level device))
    true))

(defn category-valid?
  "Is `category` one of the closed, known device-category values?
  nil/blank is treated as invalid (an intake patch that declares a
  category field at all must declare a real one, not omit it
  silently)."
  [category]
  (contains? valid-device-categories category))

(defn functional-status-valid?
  "Is `functional-status` one of the closed, known values?"
  [functional-status]
  (contains? valid-functional-statuses functional-status))

(defn wipe-level-valid?
  "Is `wipe-level` one of the closed NIST SP 800-88 values?"
  [wipe-level]
  (contains? valid-wipe-levels wipe-level))

(defn disposition-valid?
  "Is `disposition` one of the closed, known disposition values?"
  [disposition]
  (contains? valid-dispositions disposition))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human ITAD auditor's/R2v3-accredited certification body's act,
  not this actor's. And NEVER an R2v3-conformance/data-destruction
  certification mark -- this actor is never the accredited
  certification body (see README `What this actor does NOT do`)."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-wipe
  "Validate + construct the DATA-WIPE-SCHEDULE DRAFT -- a proposed
  sanitization operation against a verified, registered device. Pure
  function -- does not actuate any shredder/degausser/wipe-station; it
  builds the RECORD a coordinator would keep. `itad.governor`
  independently re-verifies the device's own verified/registered
  ground truth, and permanently blocks any attempt to directly actuate
  destruction equipment (see README `Actuation`), before this is ever
  allowed to commit."
  [wipe-id device-id sequence]
  (when-not (and wipe-id (not= wipe-id ""))
    (throw (ex-info "wipe: wipe_id required" {})))
  (when-not (and device-id (not= device-id ""))
    (throw (ex-info "wipe: device_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "wipe: sequence must be >= 0" {})))
  (let [wipe-number (str "WIP-" (zero-pad sequence 6))
        record {"record_id" wipe-number
                "kind" "data-wipe-schedule-draft"
                "wipe_id" wipe-id
                "device_id" device-id
                "immutable" true}]
    {"record" record "wipe_number" wipe-number
     "certificate" (unsigned-certificate "DataWipeSchedule" wipe-number wipe-number)}))

(defn register-disposition
  "Validate + construct the DISPOSITION-COORDINATION DRAFT -- a
  proposed resell/refurbish/certified-recycle disposition against a
  verified, registered device. Pure function -- does not release the
  device to any real buyer/recycler or issue any real conformance
  certification; it builds the RECORD a coordinator would keep.
  `itad.governor` independently re-verifies the disposition's own
  claimed wipe-sufficiency against `wipe-level-sufficient-for-
  disposition?`, before this is ever allowed to commit."
  [disposition-id sequence]
  (when-not (and disposition-id (not= disposition-id ""))
    (throw (ex-info "disposition: disposition_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "disposition: sequence must be >= 0" {})))
  (let [disposition-number (str "DSP-" (zero-pad sequence 6))
        record {"record_id" disposition-number
                "kind" "disposition-coordination-draft"
                "disposition_id" disposition-id
                "immutable" true}]
    {"record" record "disposition_number" disposition-number
     "certificate" (unsigned-certificate "DispositionCoordination" disposition-number disposition-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
