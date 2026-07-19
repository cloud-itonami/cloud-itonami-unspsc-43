(ns itad.operation-test
  "Smoke tests for the compiled ItadOperationActor graph itself (build
  + one happy path per op). The governor's full rule contract (HARD
  holds, escalation, phase gating) is exercised in
  `itad.governor-contract-test`; the Store contract in
  `itad.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [itad.operation :as op]
            [itad.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :itad-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "ItadOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-device-intake-logging-proposal
  (testing "Proposing a device-intake log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-device-intake :effect :propose :subject "device-001"
                           :patch {:functional-status :functional}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-data-wipe-scheduling
  (testing "Data-wipe scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                           :value {:device-id "device-001" :wipe-target-level :destroy
                                   :actuate-equipment? false}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-safety-concern-escalation
  (testing "Safety concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-safety-concern :effect :propose :subject "concern-1"
                           :value {:device-id "device-003" :concern :battery-hazard :description "swollen cell"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-disposition-proposal
  (testing "Disposition proposal is submitted and (when clean) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :propose-disposition :effect :propose :subject "dsp-1"
                           :value {:device-id "device-001" :disposition :resell
                                   :destination "certified-resale-channel"}}
                          coordinator)]
      (is (some? result))
      (is (= :interrupted (:status result))))))

(deftest test-ledger-is-append-only
  (testing "Audit ledger is append-only"
    (let [s (store/mem-store)
          initial-count (count (store/get-ledger s))]
      (store/append-ledger! s {:t :test-entry})
      (is (= (inc initial-count) (count (store/get-ledger s)))))))

(deftest test-records-are-committed
  (testing "The domain-agnostic commit-record! path stores a raw record by :id"
    (let [s (store/mem-store)
          record {:id "test-001" :data "test"}]
      (store/commit-record! s record)
      (is (= record (get (store/get-records s) "test-001"))))))
