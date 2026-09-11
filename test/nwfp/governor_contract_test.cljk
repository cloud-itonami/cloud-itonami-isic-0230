(ns nwfp.governor-contract-test
  "The governor contract as executable tests -- this vertical's own
  scope boundary ('does NOT dispatch field crews directly... does NOT
  authorize gathering beyond a site's own permitted quota') implemented
  faithfully. The single invariant under test:

    NwfpAdvisor never schedules a field operation, flags a
    sustainability concern, or places a supply order the NWFP Gathering
    Coordination Governor would reject; `:schedule-field-operation`/
    `:flag-sustainability-concern`/`:order-supplies` (over threshold)
    NEVER auto-commit at any phase; `:log-harvest-record` (no physical/
    financial risk) MAY auto-commit when clean; and every decision
    (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [nwfp.store :as store]
            [nwfp.operation :as op]))

(defn- fresh []
  (let [db (-> (store/mem-store) (store/sample-data!))]
    [db (op/build db)]))

(def coordinator {:actor-id "coord-1" :actor-role :nwfp-gathering-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest clean-log-harvest-record-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :log-harvest-record :effect :propose :subject "site-001"
                   :patch {:product-category :resin}} coordinator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= :resin (:product-category (store/site db "site-001"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest schedule-field-operation-always-needs-approval
  (testing "scheduling is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :schedule-field-operation :effect :propose :subject "op-1"
                     :value {:site-id "site-001" :product-category :resin
                             :quantity-kg 50.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (true? (:scheduled? (store/field-operation db "op-1"))))
        (is (= 1 (count (store/operation-history db))))))))

(deftest effect-not-propose-is-held
  (testing "a request whose own :effect is not :propose -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :log-harvest-record :effect :direct-write :subject "site-001"
                     :patch {:product-category :resin}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:not-propose-effect} (-> (store/ledger db) first :basis))))))

(deftest unknown-op-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t4" {:op :dispatch-gatherers :effect :propose :subject "x"} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:unknown-op} (-> (store/ledger db) first :basis)))))

(deftest site-not-verified-is-held-and-unoverridable
  (testing "scheduling against an unverified site -> HOLD, settles immediately, no interrupt"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :schedule-field-operation :effect :propose :subject "op-3"
                     :value {:site-id "site-003" :product-category :wild-mushrooms
                             :quantity-kg 10.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:site-not-verified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/operation-history db))))))

(deftest harvest-quota-exceeded-is-held-and-unoverridable
  (testing "a gathering trip whose requested quantity exceeds the site's remaining permit quota -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :schedule-field-operation :effect :propose :subject "op-4"
                     :value {:site-id "site-002" :product-category :cork
                             :quantity-kg 100.0 :scheduled-date "2026-08-01"}}
                    coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:harvest-quota-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/operation-history db))))))

(deftest schedule-field-operation-double-schedule-is-held
  (testing "scheduling the SAME field-operation record twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (exec-op actor "t7a" {:op :schedule-field-operation :effect :propose :subject "op-1"
                                  :value {:site-id "site-001" :product-category :resin
                                          :quantity-kg 50.0 :scheduled-date "2026-08-01"}} coordinator)
          _ (approve! actor "t7a")
          res (exec-op actor "t7" {:op :schedule-field-operation :effect :propose :subject "op-1"
                                   :value {:site-id "site-001" :product-category :resin
                                           :quantity-kg 50.0 :scheduled-date "2026-08-01"}} coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-scheduled} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/operation-history db))) "still only the one earlier schedule"))))

(deftest invalid-product-category-is-held
  (let [[db actor] (fresh)
        res (exec-op actor "t8" {:op :log-harvest-record :effect :propose :subject "site-001"
                                 :patch {:product-category :unobtainium-fabricated}} coordinator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (some #{:invalid-product-category} (-> (store/ledger db) last :basis)))
    (is (not= :unobtainium-fabricated (:product-category (store/site db "site-001"))) "fabricated category never lands in the SSoT")))

(deftest order-total-mismatch-is-held
  (testing "a claimed total that doesn't equal the sum of its own line items -> HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t9" {:op :order-supplies :effect :propose :subject "order-2"
                                    :value {:items [{:qty 100 :unit-cost 7.5}] :claimed-total 1000.0}}
                       coordinator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:order-total-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/order-history db))))))

(deftest order-exceeds-threshold-escalates-not-holds
  (testing "a CLEAN order whose own recomputed total exceeds the cost threshold -> ESCALATE, not a HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t10" {:op :order-supplies :effect :propose :subject "order-3"
                                    :value {:items [{:qty 2 :unit-cost 4000.0}] :claimed-total 8000.0}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t10")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/order-history db))))))))

(deftest sustainability-concern-always-escalates-even-high-confidence
  (testing "flag-sustainability-concern always escalates -- never auto-committed, regardless of confidence"
    (let [[db actor] (fresh)
          res (exec-op actor "t11" {:op :flag-sustainability-concern :effect :propose :subject "concern-1"
                                    :value {:site-id "site-001" :severity :moderate
                                            :description "近隣個体群の樹脂採取圧の兆候"}}
                       coordinator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t11")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (= 1 (count (store/sustainability-concerns db))))))))

(deftest sustainability-concern-approval-rejected-leaves-no-record-only-a-hold-fact
  (let [[db actor] (fresh)
        _ (exec-op actor "t12" {:op :flag-sustainability-concern :effect :propose :subject "concern-2"
                                :value {:site-id "site-001" :severity :low :description "y"}}
                   coordinator)
        r (reject! actor "t12")]
    (is (= :hold (get-in r [:state :disposition])))
    (is (= 0 (count (store/sustainability-concerns db))) "rejected approval never reaches the commit node")
    (is (= 1 (count (store/ledger db))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N settled operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :log-harvest-record :effect :propose :subject "site-001"
                          :patch {:product-category :resin}} coordinator)
      (exec-op actor "b" {:op :log-harvest-record :effect :propose :subject "site-001"
                          :patch {:product-category :fabricated}} coordinator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
