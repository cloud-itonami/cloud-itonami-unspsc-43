(ns itad.store
  "SSoT for the independent IT asset recovery and e-waste
  refurbishment coordination actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-*` actor in this fleet uses.

  Scope note: like its siblings (`cloud-itonami-isic-3091`'s own
  `motomfg.store`), this build ships a single `MemStore` backend only
  (atom of EDN) -- the deterministic default for dev/tests/demo, no
  deps. Per docs/adr/0001-architecture.md Decision 1, this vertical is
  self-contained (no external ITAD capability library, no
  jurisdiction-scoped Datomic-parity requirement driving a second
  backend); a `langchain.db`-backed store can be added later behind
  the same protocol without changing any caller.

  Three kinds of entity live here:
    - `devices`             -- the central entity. An intake device's
                             own category/functional-status/wipe-
                             level/battery-status/chain-of-custody
                             record. `:verified?` marks whether the
                             device's own claims have actually been
                             functional-tested (never inferred from a
                             routine intake patch); `:registered?`
                             marks whether its chain-of-custody is on
                             file; `:wipe-level` tracks the device's
                             own recorded sanitization ground truth
                             (NIST SP 800-88 Clear/Purge/Destroy
                             taxonomy -- see `itad.registry`).
    - `wipes`               -- a scheduled data-wipe DRAFT against a
                             device (`itad.registry`'s
                             `register-wipe`). Dedicated `:scheduled?`
                             double-schedule guard (never a `:status`
                             value -- the same discipline every prior
                             governor's guards establish, informed by
                             `cloud-itonami-isic-6492`'s
                             status-lifecycle bug, ADR-2607071320).
    - `dispositions`        -- a proposed resell/refurbish/certified-
                             recycle disposition DRAFT
                             (`itad.registry`'s
                             `register-disposition`).

  Plus a generic `records` map (id -> raw record) used only for
  direct, domain-agnostic `commit-record!` calls (a record with no
  `:effect` key) -- the store-level primitive every sibling actor's
  own MemStore exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which device was logged, which wipe
  was scheduled against a verified/registered device, which
  disposition was proposed and against what independently-recomputed
  wipe-sufficiency, approved by whom, which safety concern was
  flagged' is always a query over an immutable log -- the audit trail
  an operator or downstream customer trusting this coordinator needs."
  (:require [itad.registry :as registry]))

(defprotocol Store
  (device [s id])
  (all-devices [s])
  (wipe [s id])
  (all-wipes [s])
  (disposition [s id])
  (safety-concerns [s] "the append-only safety-concern log")
  (ledger [s])
  (wipe-history [s] "the append-only data-wipe-schedule history (itad.registry drafts)")
  (disposition-history [s] "the append-only disposition-coordination history (itad.registry drafts)")
  (next-wipe-sequence [s] "next wipe-number sequence")
  (next-disposition-sequence [s] "next disposition-number sequence")
  (wipe-already-scheduled? [s wipe-id] "has this data-wipe schedule already been scheduled?")
  (disposition-already-proposed? [s disposition-id] "has this disposition already been proposed?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-devices [s devices] "replace/seed the device directory (map id->device)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-devices []
  {"device-001" {:id "device-001" :category :laptop
                 :functional-status :functional :battery-status :nominal
                 :wipe-level :purge
                 :verified? true :registered? true
                 :last-assessed "2026-06-01"}
   "device-002" {:id "device-002" :category :desktop
                 :functional-status :functional :battery-status :none
                 :wipe-level :clear
                 :verified? true :registered? true
                 :last-assessed "2026-06-01"}
   "device-003" {:id "device-003" :category :mobile-phone
                 :functional-status :partial-function :battery-status :swollen
                 :wipe-level :none
                 :verified? false :registered? false
                 :last-assessed "2026-05-15"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-wipe!
  "Backend-agnostic `:wipe/schedule` -- drafts the data-wipe-schedule
  record via `itad.registry` and returns {:result .. :patch ..} for
  the caller to persist."
  [s wipe-id device-id]
  (let [seq-n (next-wipe-sequence s)
        result (registry/register-wipe wipe-id device-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :wipe-number (get result "wipe_number")}}))

(defn- propose-disposition!
  "Backend-agnostic `:disposition/propose` -- drafts the disposition-
  coordination record via `itad.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s disposition-id]
  (let [seq-n (next-disposition-sequence s)
        result (registry/register-disposition disposition-id seq-n)]
    {:result result
     :patch {:disposition-number (get result "disposition_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (device [_ id] (get-in @a [:devices id]))
  (all-devices [_] (sort-by :id (vals (:devices @a))))
  (wipe [_ id] (get-in @a [:wipes id]))
  (all-wipes [_] (sort-by :id (vals (:wipes @a))))
  (disposition [_ id] (get-in @a [:dispositions id]))
  (safety-concerns [_] (:safety-concerns @a))
  (ledger [_] (:ledger @a))
  (wipe-history [_] (:wipe-history @a))
  (disposition-history [_] (:disposition-history @a))
  (next-wipe-sequence [_] (:wipe-sequence @a 0))
  (next-disposition-sequence [_] (:disposition-sequence @a 0))
  (wipe-already-scheduled? [_ wipe-id]
    (boolean (get-in @a [:wipes wipe-id :scheduled?])))
  (disposition-already-proposed? [_ disposition-id]
    (boolean (get-in @a [:dispositions disposition-id :proposed?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :device/upsert)
      (swap! a update-in [:devices (first path)] merge (assoc value :id (first path)))

      (= effect :wipe/schedule)
      (let [wipe-id (first path)
            device-id (:device-id value)
            {:keys [result patch]} (schedule-wipe! s wipe-id device-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :wipe-sequence (fnil inc 0))
                       (update-in [:wipes wipe-id] merge (assoc value :id wipe-id) patch)
                       (update :wipe-history registry/append result))))
        result)

      (= effect :safety-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :safety-concerns conj concern)
        concern)

      (= effect :disposition/propose)
      (let [disposition-id (first path)
            device-id (:device-id value)
            {:keys [result patch]} (propose-disposition! s disposition-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :disposition-sequence (fnil inc 0))
                       (update-in [:dispositions disposition-id] merge (assoc value :id disposition-id :proposed? true) patch)
                       (update :disposition-history registry/append result)
                       (update-in [:devices device-id :disposition]
                                  (fn [_prev] (:disposition value))))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `logging`-style siblings expose as their own
      ;; low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-devices [s devices] (when (seq devices) (swap! a assoc :devices devices)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:devices {} :wipes {} :dispositions {}
                      :records {} :safety-concerns []
                      :ledger [] :wipe-sequence 0 :wipe-history []
                      :disposition-sequence 0 :disposition-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained device set --
  one verified+registered laptop already wiped to Purge (schedulable
  for wipe, and eligible for a resale/refurbish disposition), one
  verified+registered desktop wiped only to Clear (schedulable for
  wipe, but NOT eligible for a resale/refurbish disposition until
  wiped to Purge/Destroy -- HARD hold), one UNVERIFIED/unregistered
  mobile phone with a swollen battery and no wipe (blocks any wipe
  scheduling or disposition proposed against it) -- so the actor +
  demo + tests run offline. Returns `s` (thread-friendly with `->`)."
  [s]
  (with-devices s (sample-devices))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name several sibling actors'
;; own demo/test harnesses already call.

(defn get-ledger [s] (ledger s))
