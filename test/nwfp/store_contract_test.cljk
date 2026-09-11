(ns nwfp.store-contract-test
  "The Store contract as executable tests. Single MemStore backend --
  see `nwfp.store` ns docstring for why a second (Datomic-backed)
  backend is out of scope for this build."
  (:require [clojure.test :refer [deftest is testing]]
            [nwfp.store :as store]))

(defn- seeded [] (-> (store/mem-store) (store/sample-data!)))

(deftest sample-data-read-basics
  (let [s (seeded)]
    (is (true? (:verified? (store/site s "site-001"))))
    (is (= 400.0 (- (:harvest-quota-kg (store/site s "site-001"))
                     (:quota-used-kg (store/site s "site-001")))))
    (is (true? (:verified? (store/site s "site-002"))))
    (is (= 20.0 (- (:harvest-quota-kg (store/site s "site-002"))
                    (:quota-used-kg (store/site s "site-002")))))
    (is (false? (:verified? (store/site s "site-003"))))
    (is (= ["site-001" "site-002" "site-003"] (mapv :id (store/all-sites s))))
    (is (= [] (store/ledger s)))
    (is (= [] (store/operation-history s)))
    (is (= [] (store/order-history s)))
    (is (= [] (store/sustainability-concerns s)))
    (is (zero? (store/next-operation-sequence s)))
    (is (zero? (store/next-order-sequence s)))
    (is (false? (store/field-operation-already-scheduled? s "op-1")))
    (is (nil? (store/field-operation s "op-1")))))

(deftest fresh-store-has-no-sites
  (let [s (store/mem-store)]
    (is (= [] (store/all-sites s)))
    (is (nil? (store/site s "site-001")))))

(deftest site-upsert-merges-preserving-untouched-fields
  (let [s (seeded)]
    (store/commit-record! s {:effect :site/upsert :path ["site-001"]
                             :value {:product-category :bark}})
    (is (= :bark (:product-category (store/site s "site-001"))))
    (is (true? (:verified? (store/site s "site-001"))) "unrelated field preserved")
    (is (= 500.0 (:harvest-quota-kg (store/site s "site-001"))) "unrelated field preserved")))

(deftest field-operation-schedule-commits-and-advances-sequence
  (testing "commit-record! (like every sibling actor's own MemStore) returns the store `s`, not the domain result -- inspect the store directly, matching the discipline the actor's own :commit node relies on"
    (let [s (seeded)]
      (store/commit-record! s {:effect :field-operation/schedule :path ["op-1"]
                               :value {:site-id "site-001" :product-category :resin :quantity-kg 50.0}})
      (is (= "FOP-000000" (get (first (store/operation-history s)) "record_id")))
      (is (= "field-operation-schedule-draft" (get (first (store/operation-history s)) "kind")))
      (is (true? (:scheduled? (store/field-operation s "op-1"))))
      (is (= "site-001" (:site-id (store/field-operation s "op-1"))))
      (is (= 1 (count (store/operation-history s))))
      (is (= 1 (store/next-operation-sequence s)))
      (is (true? (store/field-operation-already-scheduled? s "op-1")))
      (is (= "FOP-000000" (:operation-number (store/field-operation s "op-1")))))))

(deftest sustainability-concern-flag-appends
  (let [s (seeded)]
    (store/commit-record! s {:effect :sustainability-concern/flag :path ["concern-1"]
                             :value {:site-id "site-001" :severity :moderate}})
    (is (= 1 (count (store/sustainability-concerns s))))
    (is (= :moderate (:severity (first (store/sustainability-concerns s)))))
    (store/commit-record! s {:effect :sustainability-concern/flag :path ["concern-2"]
                             :value {:site-id "site-002" :severity :high}})
    (is (= 2 (count (store/sustainability-concerns s))) "append-only")))

(deftest supply-order-propose-commits-and-advances-sequence
  (let [s (seeded)]
    (store/commit-record! s {:effect :supply-order/propose :path ["order-1"]
                             :value {:items [{:qty 1 :unit-cost 1.0}] :claimed-total 1.0}})
    (is (= "ORD-000000" (get (first (store/order-history s)) "record_id")))
    (is (= "supply-order-draft" (get (first (store/order-history s)) "kind")))
    (is (= 1 (count (store/order-history s))))
    (is (= 1 (store/next-order-sequence s)))
    (is (= "ORD-000000" (:order-number (store/supply-order s "order-1"))))))

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
