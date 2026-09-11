(ns nwfp.operation-test
  "Smoke tests for the compiled NwfpOperationActor graph itself (build +
  one happy path per op). The governor's full rule contract (HARD
  holds, escalation, phase gating) is exercised in `nwfp.governor-
  contract-test`; the Store contract in `nwfp.store-contract-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [nwfp.operation :as op]
            [nwfp.store :as store]))

(def coordinator {:actor-id "coord-1" :actor-role :nwfp-gathering-coordinator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "coord-1"}} {:thread-id tid :resume? true}))

(deftest test-actor-builds
  (testing "NwfpOperationActor can be built with a store"
    (let [s (store/mem-store)
          actor (op/build s)]
      (is (not (nil? actor))))))

(deftest test-harvest-record-logging-proposal
  (testing "Proposing a harvest-record log auto-commits when clean (phase 3, no physical/financial risk)"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          initial-ledger-size (count (store/get-ledger s))
          result (exec-op actor "t1"
                          {:op :log-harvest-record :effect :propose :subject "site-001"
                           :patch {:product-category :resin}}
                          coordinator)
          final-ledger-size (count (store/get-ledger s))]
      (is (> final-ledger-size initial-ledger-size))
      (is (= :commit (get-in result [:state :disposition]))))))

(deftest test-field-operation-scheduling
  (testing "Field operation scheduling always escalates for human approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t2"
                          {:op :schedule-field-operation :effect :propose :subject "op-1"
                           :value {:site-id "site-001" :product-category :resin
                                   :quantity-kg 50.0 :scheduled-date "2026-08-01"}}
                          coordinator)]
      (is (= :interrupted (:status result)))
      (is (= :commit (get-in (approve! actor "t2") [:state :disposition]))))))

(deftest test-sustainability-concern-escalation
  (testing "Sustainability concerns always escalate"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t3"
                          {:op :flag-sustainability-concern :effect :propose :subject "concern-1"
                           :value {:site-id "site-001" :severity :moderate :description "over-harvest sighting"}}
                          coordinator)]
      (is (= :interrupted (:status result))))))

(deftest test-supply-order-proposal
  (testing "Supply order proposal is submitted and (when below threshold + matching) escalates for approval"
    (let [s (-> (store/mem-store) (store/sample-data!))
          actor (op/build s)
          result (exec-op actor "t4"
                          {:op :order-supplies :effect :propose :subject "order-1"
                           :value {:items [{:name "tapping-tools" :qty 100 :unit-cost 2.0}]
                                   :claimed-total 200.0}}
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
