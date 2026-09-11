(ns nwfp.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean site through
  intake -> field-operation scheduling (escalate/approve) -> sustain-
  ability-concern flag (escalate/approve) -> supply-order proposal
  (escalate/approve), then shows HARD-hold scenarios: a mis-wired
  request whose own `:effect` is not `:propose`, an unrecognized op, a
  field-operation scheduled against an UNVERIFIED site, a gathering
  trip whose requested quantity would exceed the site's permitted
  harvest quota (permanently blocked, no override), a double-schedule
  of the same field operation, a harvest-record patch with a
  fabricated product-category, and a supply order whose claimed total
  doesn't match its own line items -- plus one supply order that is
  CLEAN but exceeds the cost threshold, which escalates rather than
  auto-commits.

  Like every sibling actor's own demo, each check is exercised directly
  and independently below, one request per HARD-hold scenario, the SAME
  'exercise the failure mode directly, never only via a happy-path
  actuation' discipline `parksafety`'s ADR-2607071922 Decision 5 and
  every sibling since establish."
  (:require [langgraph.graph :as g]
            [nwfp.store :as store]
            [nwfp.operation :as op]))

(def coordinator {:actor-id "coord-1" :actor-role :nwfp-gathering-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn -main [& _args]
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]

    (println "== log-harvest-record site-001 (clean patch -> phase-3 auto-commit) ==")
    (println (exec-op actor "t1"
                       {:op :log-harvest-record :effect :propose :subject "site-001"
                        :patch {:product-category :resin :last-assessed "2026-07-14"}}
                       coordinator))

    (println "== schedule-field-operation op-1 on site-001 (verified, in-quota -- escalates, approve) ==")
    (let [r (exec-op actor "t2"
                      {:op :schedule-field-operation :effect :propose :subject "op-1"
                       :value {:site-id "site-001" :product-category :resin
                               :quantity-kg 50.0 :scheduled-date "2026-08-01"}}
                      coordinator)]
      (println r)
      (println "-- human gathering-operations manager approves --")
      (println (approve! actor "t2")))

    (println "== flag-sustainability-concern concern-1 on site-001 (always escalates -- approve) ==")
    (let [r (exec-op actor "t3"
                      {:op :flag-sustainability-concern :effect :propose :subject "concern-1"
                       :value {:site-id "site-001" :severity :moderate
                               :description "近隣個体群の樹脂採取圧の兆候"}}
                      coordinator)]
      (println r)
      (println "-- human gathering-operations manager approves --")
      (println (approve! actor "t3")))

    (println "== order-supplies order-1 (clean, matching total, below threshold -- escalates, approve) ==")
    (let [r (exec-op actor "t4"
                      {:op :order-supplies :effect :propose :subject "order-1"
                       :value {:items [{:name "tapping-tools" :qty 20 :unit-cost 25.0}
                                       {:name "collection-bags" :qty 200 :unit-cost 1.25}]
                               :claimed-total 750.0}}
                      coordinator)]
      (println r)
      (println "-- human purchasing approver approves --")
      (println (approve! actor "t4")))

    (println "\n-- HARD-hold scenarios --\n")

    (println "== log-harvest-record with :effect other than :propose -> HARD hold (structural) ==")
    (println (exec-op actor "t5"
                       {:op :log-harvest-record :effect :direct-write :subject "site-001"
                        :patch {:product-category :resin}}
                       coordinator))

    (println "== unrecognized op -> HARD hold ==")
    (println (exec-op actor "t6"
                       {:op :dispatch-gatherers :effect :propose :subject "site-001"}
                       coordinator))

    (println "== schedule-field-operation op-2 on site-003 (UNVERIFIED -> HARD hold) ==")
    (println (exec-op actor "t7"
                       {:op :schedule-field-operation :effect :propose :subject "op-2"
                        :value {:site-id "site-003" :product-category :wild-mushrooms
                                :quantity-kg 10.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== schedule-field-operation op-3 on site-002 (requested 100kg > 20kg remaining -> HARD hold) ==")
    (println (exec-op actor "t8"
                       {:op :schedule-field-operation :effect :propose :subject "op-3"
                        :value {:site-id "site-002" :product-category :cork
                                :quantity-kg 100.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== schedule-field-operation op-1 AGAIN (double-schedule -> HARD hold) ==")
    (println (exec-op actor "t9"
                       {:op :schedule-field-operation :effect :propose :subject "op-1"
                        :value {:site-id "site-001" :product-category :resin
                                :quantity-kg 50.0 :scheduled-date "2026-08-01"}}
                       coordinator))

    (println "== log-harvest-record site-001 with a fabricated product-category -> HARD hold ==")
    (println (exec-op actor "t10"
                       {:op :log-harvest-record :effect :propose :subject "site-001"
                        :patch {:product-category :unobtainium-fabricated}}
                       coordinator))

    (println "== order-supplies order-2 (claimed 1000.0 vs recompute 750.0 -> HARD hold) ==")
    (println (exec-op actor "t11"
                       {:op :order-supplies :effect :propose :subject "order-2"
                        :value {:items [{:name "permit-fees" :qty 100 :unit-cost 7.5}]
                                :claimed-total 1000.0}}
                       coordinator))

    (println "== order-supplies order-3 (clean but exceeds cost threshold -> ESCALATE, not HOLD -- approve) ==")
    (let [r (exec-op actor "t12"
                      {:op :order-supplies :effect :propose :subject "order-3"
                       :value {:items [{:name "distillation-still" :qty 2 :unit-cost 4000.0}]
                               :claimed-total 8000.0}}
                      coordinator)]
      (println r)
      (println "-- human purchasing approver approves --")
      (println (approve! actor "t12")))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== draft field-operation records ==")
    (doseq [r (store/operation-history db)] (println r))

    (println "\n== draft supply-order records ==")
    (doseq [r (store/order-history db)] (println r))))
