(ns hospital.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean admission
  through intake -> jurisdiction assessment -> credential screening ->
  treatment-administration proposal (always escalates) -> human
  approval -> commit, then through discharge-authorization proposal
  (always escalates) -> human approval -> commit, then shows five
  HARD holds (a jurisdiction with no spec-basis, an insufficient post-
  procedure observation period, a not-current clinician license
  screened directly via `:credential/screen` [never via an actuation
  op against an unscreened admission -- see this actor's own governor
  ns docstring / the lesson `parksafety`'s ADR-2607071922 Decision 5,
  `eldercare`'s, `museum`'s, `conservation`'s, `salon`'s,
  `entertainment`'s and `casework`'s ADR-0001s already recorded], and
  a double administration/discharge of an already-processed admission)
  that never reach a human at all, and prints the audit ledger + the
  draft treatment-administration and discharge-authorization records."
  (:require [langgraph.graph :as g]
            [hospital.store :as store]
            [hospital.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :clinician :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== admission/intake admission-1 (JPN, clean; 6h since procedure, license current) ==")
    (println (exec! actor "t1" {:op :admission/intake :subject "admission-1"
                                :patch {:id "admission-1" :patient-name "Sakura Tanaka"}} operator))

    (println "== jurisdiction/assess admission-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "admission-1"} operator))
    (println (approve! actor "t2"))

    (println "== credential/screen admission-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :credential/screen :subject "admission-1"} operator))
    (println (approve! actor "t3"))

    (println "== treatment/administer admission-1 (always escalates -- actuation/administer-treatment) ==")
    (let [r (exec! actor "t4" {:op :treatment/administer :subject "admission-1"} operator)]
      (println r)
      (println "-- human clinician approves --")
      (println (approve! actor "t4")))

    (println "== discharge/authorize admission-1 (always escalates -- actuation/authorize-discharge) ==")
    (let [r (exec! actor "t5" {:op :discharge/authorize :subject "admission-1"} operator)]
      (println r)
      (println "-- human clinician approves --")
      (println (approve! actor "t5")))

    (println "== jurisdiction/assess admission-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "admission-2" :no-spec? true} operator))

    (println "== jurisdiction/assess admission-3 (escalates -- human approves; sets up the observation-period test) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "admission-3"} operator))
    (println (approve! actor "t7"))

    (println "== discharge/authorize admission-3 (1h since procedure < 4h minimum -> HARD hold) ==")
    (println (exec! actor "t8" {:op :discharge/authorize :subject "admission-3"} operator))

    (println "== credential/screen admission-4 (not-current license -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t9" {:op :credential/screen :subject "admission-4"} operator))

    (println "== treatment/administer admission-1 AGAIN (double-administration -> HARD hold) ==")
    (println (exec! actor "t10" {:op :treatment/administer :subject "admission-1"} operator))

    (println "== discharge/authorize admission-1 AGAIN (double-discharge -> HARD hold) ==")
    (println (exec! actor "t11" {:op :discharge/authorize :subject "admission-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft treatment-administration records ==")
    (doseq [r (store/treatment-history db)] (println r))

    (println "== draft discharge-authorization records ==")
    (doseq [r (store/discharge-history db)] (println r))))
