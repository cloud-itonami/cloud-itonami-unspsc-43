(ns itad.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean ITAD operator
  through intake -> data-wipe scheduling (escalate/approve) ->
  safety-concern flag (escalate/approve) -> disposition proposal
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  wipe scheduled against an UNVERIFIED/unregistered device, a
  disposition proposed against an UNVERIFIED/unregistered device, a
  resell disposition proposed against a device only wiped to Clear
  (insufficient per NIST SP 800-88), a proposal that tries to ACTUATE
  destruction equipment directly (permanently blocked, no override), a
  proposal that tries to self-issue an R2v3-conformance/data-
  destruction certification (permanently blocked, no override), a
  double-schedule of the same wipe, a device-intake patch with a
  fabricated category, a device-intake patch with a fabricated
  functional-status, and a device-intake patch with a fabricated
  wipe-level.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [itad.store :as store]
            [itad.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :itad-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-device-intake device-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-device-intake :effect :propose :subject "device-001"
                        :patch {:functional-status :functional :last-assessed "2026-07-14"}}
                       coordinator))

    (println "== schedule-data-wipe wip-1 on device-001 (verified, registered device -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                       :value {:device-id "device-001" :wipe-target-level :destroy
                               :actuate-equipment? false}}
                      coordinator)]
      (println r)
      (println "-- human ITAD coordinator approves --")
      (println (approve! actor "t2")))

    (println "== flag-safety-concern concern-1 on device-003 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-safety-concern :effect :propose :subject "concern-1"
                       :value {:device-id "device-003" :concern :battery-hazard
                               :description "リチウムイオン電池の膨張を確認、熱暴走リスクあり"}}
                      coordinator)]
      (println r)
      (println "-- human ITAD coordinator approves --")
      (println (approve! actor "t3")))

    (println "== propose-disposition dsp-1 on device-001 (verified, registered, wipe-level=purge -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :propose-disposition :effect :propose :subject "dsp-1"
                       :value {:device-id "device-001" :disposition :resell
                               :destination "certified-resale-channel"}}
                      coordinator)]
      (println r)
      (println "-- human ITAD coordinator approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-device-intake with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-device-intake :effect :direct-write :subject "device-001"
                        :patch {:functional-status :functional}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :actuate-shredder :effect :propose :subject "device-001"}
                       coordinator))

    (println "== schedule-data-wipe wip-2 on device-003 (UNVERIFIED/unregistered mobile phone -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-data-wipe :effect :propose :subject "wip-2"
                        :value {:device-id "device-003" :wipe-target-level :destroy
                                :actuate-equipment? false}}
                       coordinator))

    (println "== propose-disposition dsp-2 on device-003 (UNVERIFIED/unregistered device -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :propose-disposition :effect :propose :subject "dsp-2"
                        :value {:device-id "device-003" :disposition :certified-recycle
                                :destination "e-waste-recycler"}}
                       coordinator))

    (println "== propose-disposition dsp-3 on device-002 (resell requested, but wipe-level only :clear -> HARD hold, NIST SP 800-88) ==")
    (println (exec-op actor "t9"
                       {:op :propose-disposition :effect :propose :subject "dsp-3"
                        :value {:device-id "device-002" :disposition :resell
                                :destination "certified-resale-channel"}}
                       coordinator))

    (println "== schedule-data-wipe wip-3 on device-001 with :actuate-equipment? true -> HARD hold, PERMANENT, never reaches a human ==")
    (println (exec-op actor "t10"
                       {:op :schedule-data-wipe :effect :propose :subject "wip-3"
                        :value {:device-id "device-001" :wipe-target-level :destroy
                                :actuate-equipment? true}}
                       coordinator))

    (println "== schedule-data-wipe wip-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :schedule-data-wipe :effect :propose :subject "wip-1"
                        :value {:device-id "device-001" :wipe-target-level :destroy
                                :actuate-equipment? false}}
                       coordinator))

    (println "== log-device-intake device-001 with a fabricated category -> HARD hold ==")
    (println (exec-op actor "t12"
                       {:op :log-device-intake :effect :propose :subject "device-001"
                        :patch {:category :flying-saucer}}
                       coordinator))

    (println "== log-device-intake device-001 with a fabricated functional-status -> HARD hold ==")
    (println (exec-op actor "t13"
                       {:op :log-device-intake :effect :propose :subject "device-001"
                        :patch {:functional-status :haunted}}
                       coordinator))

    (println "== log-device-intake device-001 with a fabricated wipe-level -> HARD hold ==")
    (println (exec-op actor "t14"
                       {:op :log-device-intake :effect :propose :subject "device-001"
                        :patch {:wipe-level :vaporized}}
                       coordinator))

    (println "== log-device-intake device-001 attempting to self-issue an R2v3-conformance/data-destruction certification -> HARD hold, PERMANENT ==")
    (println (exec-op actor "t15"
                       {:op :log-device-intake :effect :propose :subject "device-001"
                        :patch {:issue-certification? true}}
                       coordinator))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft data-wipe records ==")
    (doseq [r (store/wipe-history db)] (println r))

    (println "\n== draft disposition records ==")
    (doseq [r (store/disposition-history db)] (println r))))
