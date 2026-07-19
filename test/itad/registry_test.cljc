(ns itad.registry-test
  (:require [clojure.test :refer [deftest is]]
            [itad.registry :as r]))

;; ----------------------------- device-verified? / device-registered? / device-ready? -----------------------------

(deftest device-is-verified-when-flagged
  (is (true? (r/device-verified? {:id "d1" :verified? true}))))

(deftest device-is-not-verified-when-false-or-missing
  (is (false? (r/device-verified? {:id "d1" :verified? false})))
  (is (false? (r/device-verified? {:id "d1"}))))

(deftest device-is-registered-when-flagged
  (is (true? (r/device-registered? {:registered? true}))))

(deftest device-is-not-registered-when-false-or-missing
  (is (false? (r/device-registered? {:registered? false})))
  (is (false? (r/device-registered? {}))))

(deftest device-ready-requires-both
  (is (true? (r/device-ready? {:verified? true :registered? true})))
  (is (false? (r/device-ready? {:verified? true :registered? false})))
  (is (false? (r/device-ready? {:verified? false :registered? true})))
  (is (false? (r/device-ready? {}))))

;; ----------------------------- wipe-level-sufficient-for-disposition? -----------------------------

(deftest purge-is-sufficient-for-resell
  (is (true? (r/wipe-level-sufficient-for-disposition? {:wipe-level :purge} :resell))))

(deftest destroy-is-sufficient-for-refurbish
  (is (true? (r/wipe-level-sufficient-for-disposition? {:wipe-level :destroy} :refurbish))))

(deftest clear-is-insufficient-for-resell
  (is (false? (r/wipe-level-sufficient-for-disposition? {:wipe-level :clear} :resell))))

(deftest none-is-insufficient-for-refurbish
  (is (false? (r/wipe-level-sufficient-for-disposition? {:wipe-level :none} :refurbish)))
  (is (false? (r/wipe-level-sufficient-for-disposition? {} :refurbish))))

(deftest certified-recycle-is-always-sufficient-regardless-of-wipe-level
  (is (true? (r/wipe-level-sufficient-for-disposition? {:wipe-level :none} :certified-recycle)))
  (is (true? (r/wipe-level-sufficient-for-disposition? {} :certified-recycle))))

;; ----------------------------- category-valid? -----------------------------

(deftest known-categories-are-valid
  (doseq [c [:laptop :desktop :monitor :mobile-phone :tablet
             :server :networking-equipment :peripheral]]
    (is (r/category-valid? c))))

(deftest fabricated-category-is-invalid
  (is (not (r/category-valid? :flying-saucer)))
  (is (not (r/category-valid? nil))))

;; ----------------------------- functional-status-valid? -----------------------------

(deftest known-functional-statuses-are-valid
  (doseq [fs [:functional :partial-function :non-functional]]
    (is (r/functional-status-valid? fs))))

(deftest fabricated-functional-status-is-invalid
  (is (not (r/functional-status-valid? :haunted)))
  (is (not (r/functional-status-valid? nil))))

;; ----------------------------- wipe-level-valid? -----------------------------

(deftest known-wipe-levels-are-valid
  (doseq [wl [:none :clear :purge :destroy]]
    (is (r/wipe-level-valid? wl))))

(deftest fabricated-wipe-level-is-invalid
  (is (not (r/wipe-level-valid? :vaporized)))
  (is (not (r/wipe-level-valid? nil))))

;; ----------------------------- disposition-valid? -----------------------------

(deftest known-dispositions-are-valid
  (doseq [d [:resell :refurbish :certified-recycle]]
    (is (r/disposition-valid? d))))

(deftest fabricated-disposition-is-invalid
  (is (not (r/disposition-valid? :launch-into-sun)))
  (is (not (r/disposition-valid? nil))))

;; ----------------------------- register-wipe -----------------------------

(deftest wipe-is-a-draft-not-a-real-sanitization
  (let [result (r/register-wipe "wip-1" "device-001" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest wipe-assigns-wipe-number
  (let [result (r/register-wipe "wip-1" "device-001" 7)]
    (is (= (get result "wipe_number") "WIP-000007"))
    (is (= (get-in result ["record" "wipe_id"]) "wip-1"))
    (is (= (get-in result ["record" "device_id"]) "device-001"))
    (is (= (get-in result ["record" "kind"]) "data-wipe-schedule-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest wipe-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-wipe "" "device-001" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-wipe "wip-1" "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-wipe "wip-1" "device-001" -1))))

;; ----------------------------- register-disposition -----------------------------

(deftest disposition-is-a-draft-not-a-real-release
  (let [result (r/register-disposition "dsp-1" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest disposition-assigns-disposition-number
  (let [result (r/register-disposition "dsp-1" 7)]
    (is (= (get result "disposition_number") "DSP-000007"))
    (is (= (get-in result ["record" "disposition_id"]) "dsp-1"))
    (is (= (get-in result ["record" "kind"]) "disposition-coordination-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest disposition-validation-rules
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disposition "" 0)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (r/register-disposition "dsp-1" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-wipe "wip-1" "device-001" 0)
        hist (r/append [] c1)
        c2 (r/register-wipe "wip-2" "device-001" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "WIP-000000" (get-in hist2 [0 "record_id"])))
    (is (= "WIP-000001" (get-in hist2 [1 "record_id"])))))
