(ns itad.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:schedule-data-wipe` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [itad.phase :as phase]))

(deftest schedule-data-wipe-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in future entries, auto-commits a real data wipe"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :schedule-data-wipe))
          (str "phase " n " must not auto-commit :schedule-data-wipe")))))

(deftest flag-safety-concern-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :flag-safety-concern))
        (str "phase " n " must not auto-commit :flag-safety-concern"))))

(deftest propose-disposition-never-auto-at-any-phase
  (doseq [[n {:keys [auto]}] phase/phases]
    (is (not (contains? auto :propose-disposition))
        (str "phase " n " must not auto-commit :propose-disposition"))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-risk-ops
  (testing ":log-device-intake carries no physical/financial risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:log-device-intake} (:auto (get phase/phases 3))))))

(deftest schedule-data-wipe-enabled-from-phase-3-only
  (is (contains? (:writes (get phase/phases 3)) :schedule-data-wipe))
  (is (not (contains? (:writes (get phase/phases 2)) :schedule-data-wipe)))
  (is (not (contains? (:writes (get phase/phases 1)) :schedule-data-wipe))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :log-device-intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :schedule-data-wipe} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :flag-safety-concern} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :propose-disposition} :commit)))))

(deftest gate-auto-commits-the-one-eligible-write-when-clean
  (is (= :commit (:disposition (phase/gate 3 {:op :log-device-intake} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :log-device-intake} :commit)))))

(deftest verdict->disposition-maps-hard-to-hold
  (is (= :hold (phase/verdict->disposition {:hard? true :escalate? false}))))

(deftest verdict->disposition-maps-escalate
  (is (= :escalate (phase/verdict->disposition {:hard? false :escalate? true}))))

(deftest verdict->disposition-maps-commit
  (is (= :commit (phase/verdict->disposition {:hard? false :escalate? false}))))
