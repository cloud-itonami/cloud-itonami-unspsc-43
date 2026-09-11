(ns itad.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT actuate a shredder/degausser/wipe-station
  directly... does NOT self-issue an R2v3-conformance/data-destruction
  certification... does NOT release a device for resale/refurbishment
  without a NIST SP 800-88 Purge/Destroy-level wipe') implemented
  faithfully. The single invariant under test:

    TriageAdvisor never schedules a data wipe, flags a safety concern,
    or proposes a disposition the IT Asset Recovery Governor would
    reject; `:schedule-data-wipe`/`:flag-safety-concern`/
    `:propose-disposition` NEVER auto-commit at any phase;
    `:log-device-intake` (no physical/financial risk) MAY auto-commit
    when clean; and every decision (commit OR hold) leaves exactly one
    ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [itad.store :as store]
            [itad.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :itad-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-device-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-device-intake :effect :propose :subject "device-001"
                   :patch {:functional-status :functional}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :functional (:functional-status (store/device db "device-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-data-wipe-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                     :value {:device-id "device-001" :wipe-target-level :destroy
                             :actuate-equipment? false}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/wipe db "wip-1"))))
        (is (= 1 (count (store/wipe-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-device-intake :effect :direct-write :subject "device-001"
                     :patch {:functional-status :functional}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :actuate-shredder :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest device-not-verified-is-held-for-wipe-and-unoverridable
  (testing "scheduling a wipe against an unverified/unregistered device -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-data-wipe :effect :propose :subject "wip-2"
                     :value {:device-id "device-003" :wipe-target-level :destroy
                             :actuate-equipment? false}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:device-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/wipe-history db))))))

(deftest device-not-verified-is-held-for-disposition-and-unoverridable
  (testing "proposing a disposition against an unverified/unregistered device -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :propose-disposition :effect :propose :subject "dsp-2"
                     :value {:device-id "device-003" :disposition :certified-recycle
                             :destination "e-waste-recycler"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:device-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/disposition-history db))))))

(deftest insufficient-wipe-level-is-held-and-unoverridable
  (testing "a resell disposition against a device only wiped to :clear -> HOLD, NIST SP 800-88 Purge/Destroy not reached"
    (let [[db actor] (fresh)
          res (exec-op actor "t7"
                    {:op :propose-disposition :effect :propose :subject "dsp-3"
                     :value {:device-id "device-002" :disposition :resell
                             :destination "certified-resale-channel"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:insufficient-wipe-level} (-> (store/ledger db) last :basis)))
      (is (empty? (store/disposition-history db))))))

(deftest certified-recycle-does-not-require-wipe-level
  (testing "a certified-recycle disposition against a verified/registered device is not blocked by wipe-level insufficiency"
    (let [[_db actor] (fresh)
          res (exec-op actor "t7b"
                    {:op :propose-disposition :effect :propose :subject "dsp-4"
                     :value {:device-id "device-002" :disposition :certified-recycle
                             :destination "e-waste-recycler"}}
                    coordinator)]
      (is (= :interrupted (:status res)) "clean disposition -- escalates for approval, not held"))))

(deftest destruction-equipment-actuate-is-held-and-permanently-blocked
  (testing "a proposal that sets :actuate-equipment? true -> HOLD, PERMANENT, never reaches request-approval even though the device is verified and registered"
    (let [[db actor] (fresh)
          res (exec-op actor "t8"
                    {:op :schedule-data-wipe :effect :propose :subject "wip-3"
                     :value {:device-id "device-001" :wipe-target-level :destroy
                             :actuate-equipment? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:destruction-actuate-blocked} (-> (store/ledger db) last :basis)))
      (is (empty? (store/wipe-history db))))))

(deftest certification-authority-is-held-and-permanently-blocked
  (testing "a proposal that sets :issue-certification? true -> HOLD, PERMANENT, never reaches request-approval -- this actor is never the R2v3-conformance/data-destruction certification authority"
    (let [[db actor] (fresh)
          res (exec-op actor "t8b"
                    {:op :log-device-intake :effect :propose :subject "device-001"
                     :patch {:issue-certification? true}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:certification-authority-blocked} (-> (store/ledger db) last :basis)))
      (is (not (true? (:issue-certification? (store/device db "device-001"))))
          "fabricated self-certification never lands in the SSoT"))))

(deftest schedule-data-wipe-double-schedule-is-held
  (testing "scheduling the SAME wipe record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9a" {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                                  :value {:device-id "device-001" :wipe-target-level :destroy
                                          :actuate-equipment? false}} coordinator)
          _ (approve! actor "t9a")
          res (exec-op actor "t9" {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                                   :value {:device-id "device-001" :wipe-target-level :destroy
                                           :actuate-equipment? false}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:wipe-already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/wipe-history db))) "still only the one earlier schedule"))))

(deftest propose-disposition-double-proposal-is-held
  (testing "proposing the SAME disposition record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t9c" {:op :propose-disposition :effect :propose :subject "dsp-1"
                                  :value {:device-id "device-001" :disposition :resell
                                          :destination "certified-resale-channel"}} coordinator)
          _ (approve! actor "t9c")
          res (exec-op actor "t9d" {:op :propose-disposition :effect :propose :subject "dsp-1"
                                    :value {:device-id "device-001" :disposition :resell
                                            :destination "certified-resale-channel"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:disposition-already-proposed} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/disposition-history db))) "still only the one earlier proposal"))))

(deftest invalid-disposition-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t10" {:op :propose-disposition :effect :propose :subject "dsp-5"
                                  :value {:device-id "device-001" :disposition :launch-into-sun
                                          :destination "orbit"}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-disposition} (-> (store/ledger db) last :basis)))
    (is (empty? (store/disposition-history db)))))

(deftest invalid-category-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t11" {:op :log-device-intake :effect :propose :subject "device-001"
                                  :patch {:category :flying-saucer}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-category} (-> (store/ledger db) last :basis)))
    (is (not= :flying-saucer (:category (store/device db "device-001"))) "fabricated category never lands in the SSoT")))

(deftest invalid-functional-status-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t12" {:op :log-device-intake :effect :propose :subject "device-001"
                                  :patch {:functional-status :haunted}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-functional-status} (-> (store/ledger db) last :basis)))
    (is (not= :haunted (:functional-status (store/device db "device-001"))) "fabricated functional-status never lands in the SSoT")))

(deftest invalid-wipe-level-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t13" {:op :log-device-intake :effect :propose :subject "device-001"
                                  :patch {:wipe-level :vaporized}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-wipe-level} (-> (store/ledger db) last :basis)))
    (is (not= :vaporized (:wipe-level (store/device db "device-001"))) "fabricated wipe-level never lands in the SSoT")))

(deftest safety-concern-always-escalates-even-high-confidence
  (testing "flag-safety-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t14" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                    :value {:device-id "device-003" :concern :battery-hazard
                                            :description "swollen lithium-ion cell"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t14")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/safety-concerns db))))))))

(deftest safety-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t15" {:op :flag-safety-concern :effect :propose :subject "concern-2"
                                :value {:device-id "device-001" :concern :unverified-sensitive-data :description "y"}}
                   coordinator)
        r (reject! actor "t15")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/safety-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest propose-disposition-always-needs-approval
  (testing "a CLEAN disposition proposal is never auto-eligible -- always escalates, even when wipe-sufficient"
    (let [[db actor] (fresh)
          res (exec-op actor "t16" {:op :propose-disposition :effect :propose :subject "dsp-1"
                                    :value {:device-id "device-001" :disposition :resell
                                            :destination "certified-resale-channel"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t16")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/disposition-history db))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-device-intake :effect :propose :subject "device-001"
                          :patch {:functional-status :functional}} coordinator)
      (exec-op actor "b" {:op :log-device-intake :effect :propose :subject "device-001"
                          :patch {:functional-status :haunted}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
