(ns itad.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `itad.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [itad.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/device s "device-001"))))
    (is (true? (:registered? (store/device s "device-001"))))
    (is (= :purge (:wipe-level (store/device s "device-001"))))
    (is (true? (:verified? (store/device s "device-002"))))
    (is (true? (:registered? (store/device s "device-002"))))
    (is (= :clear (:wipe-level (store/device s "device-002"))))
    (is (false? (:verified? (store/device s "device-003"))))
    (is (false? (:registered? (store/device s "device-003"))))
    (is (= ["device-001" "device-002" "device-003"] (mapv :id (store/all-devices s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/wipe-history s)))
    (is (= [] (store/disposition-history s)))
    (is (= [] (store/safety-concerns s)))
    (is (zero? (store/next-wipe-sequence s)))
    (is (zero? (store/next-disposition-sequence s)))
    (is (false? (store/wipe-already-scheduled? s "wip-1")))
    (is (false? (store/disposition-already-proposed? s "dsp-1")))
    (is (nil? (store/wipe s "wip-1")))))

(deftest fresh-store-has-no-devices
  (let [s (store/mem-store)]
    (is (= [] (store/all-devices s)))
    (is (nil? (store/device s "device-001")))))

(deftest device-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :device/upsert :path ["device-001"]
                             :value {:functional-status :partial-function}})
    (is (= :partial-function (:functional-status (store/device s "device-001"))))
    (is (true? (:verified? (store/device s "device-001"))) "unrelated field preserved")
    (is (true? (:registered? (store/device s "device-001"))) "unrelated field preserved")))

(deftest wipe-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :wipe/schedule :path ["wip-1"]
                               :value {:device-id "device-001" :wipe-target-level :destroy}})
      (is (= "WIP-000000" (get (first (store/wipe-history s)) "record_id")))
      (is (= "data-wipe-schedule-draft" (get (first (store/wipe-history s)) "kind")))
      (is (true? (:scheduled? (store/wipe s "wip-1"))))
      (is (= "device-001" (:device-id (store/wipe s "wip-1"))))
      (is (= 1 (count (store/wipe-history s))))
      (is (= 1 (store/next-wipe-sequence s)))
      (is (true? (store/wipe-already-scheduled? s "wip-1")))
      (is (= "WIP-000000" (:wipe-number (store/wipe s "wip-1")))))))

(deftest safety-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-1"]
                             :value {:device-id "device-003" :concern :battery-hazard}})
    (is (= 1 (count (store/safety-concerns s))))
    (is (= :battery-hazard (:concern (first (store/safety-concerns s)))))
    (store/commit-record! s {:effect :safety-concern/flag :path ["concern-2"]
                             :value {:device-id "device-002" :concern :unverified-sensitive-data}})
    (is (= 2 (count (store/safety-concerns s))) "append-only")))

(deftest disposition-propose-commits-and-advances-sequence-and-device-disposition
  (let [s (seeded)]
    (store/commit-record! s {:effect :disposition/propose :path ["dsp-1"]
                             :value {:device-id "device-001" :disposition :resell
                                     :destination "certified-resale-channel"}})
    (is (= "DSP-000000" (get (first (store/disposition-history s)) "record_id")))
    (is (= "disposition-coordination-draft" (get (first (store/disposition-history s)) "kind")))
    (is (= 1 (count (store/disposition-history s))))
    (is (= 1 (store/next-disposition-sequence s)))
    (is (= "DSP-000000" (:disposition-number (store/disposition s "dsp-1"))))
    (is (true? (store/disposition-already-proposed? s "dsp-1")))
    (is (= :resell (:disposition (store/device s "device-001"))))))

(deftest ledger-is-append-only-and-order-preserving
  (let [s (store/mem-store)]
    (store/append-ledger! s {:op :a :disposition :commit})
    (store/append-ledger! s {:op :b :disposition :hold})
    (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))

(deftest generic-commit-record-path-writes-a-raw-record-by-id
  (testing "a record with no :effect key is written verbatim into the generic records map -- the store-level primitive underneath the domain-specific dispatch"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))

(deftest get-ledger-alias-matches-ledger
  (let [s (store/mem-store)]
    (store/append-ledger! s {:t :x})
    (is (= (store/ledger s) (store/get-ledger s)))))
