(ns nwfp.store
  "SSoT for the non-wood-forest-product (NWFP) gathering coordination
  actor, behind a `Store` protocol so the backend is a swap, not a
  rewrite -- the same seam every `cloud-itonami-isic-*` actor in this
  fleet uses (mirrors `forestry.store` module-for-module).

  Scope note: unlike some sibling actors, this build ships a single
  `MemStore` backend only (atom of EDN) -- the deterministic default
  for dev/tests/demo, no deps. Like `cloud-itonami-isic-0210`, this
  vertical is self-contained (no external NWFP capability library, no
  jurisdiction-scoped Datomic-parity requirement driving a second
  backend); a `langchain.db`-backed store can be added later behind
  the same protocol without changing any caller.

  Three kinds of entity live here:
    - `sites`            -- the central entity. A gathering site's
                            permit/quota record. `:verified?` marks
                            whether the site's own gathering permit has
                            actually been issued and its boundary
                            registered in the SSoT (never inferred
                            from a routine harvest-record patch).
                            `:harvest-quota-kg`/`:quota-used-kg` are the
                            site's own permanent permit-quota fields.
    - `field-operations` -- a scheduled gathering-trip DRAFT against a
                            site (`nwfp.registry`'s `register-field-
                            operation`). Dedicated `:scheduled?`
                            double-schedule guard (never a `:status`
                            value -- the same discipline every prior
                            governor's guards establish, informed by
                            `cloud-itonami-isic-6492`'s status-
                            lifecycle bug, ADR-2607071320).
    - `supply-orders`    -- a proposed procurement DRAFT (`nwfp.
                            registry`'s `register-supply-order`).

  Plus a generic `records` map (id -> raw record) used only for direct,
  domain-agnostic `commit-record!` calls (a record with no `:effect`
  key) -- the store-level primitive every sibling actor's own MemStore
  exposes underneath its domain-specific commit dispatch.

  The ledger stays append-only: 'which site was proposed, which
  gathering trip was scheduled against a verified in-quota site, which
  supply order was placed and at what independently-recomputed total,
  approved by whom' is always a query over an immutable log -- the
  audit trail a gathering cooperative or permit-issuing authority
  trusting this coordinator needs."
  (:require [nwfp.registry :as registry]))

(defprotocol Store
  (site [s id])
  (all-sites [s])
  (field-operation [s id])
  (all-field-operations [s])
  (supply-order [s id])
  (sustainability-concerns [s] "the append-only sustainability-concern log")
  (ledger [s])
  (operation-history [s] "the append-only field-operation-schedule history (nwfp.registry drafts)")
  (order-history [s] "the append-only supply-order history (nwfp.registry drafts)")
  (next-operation-sequence [s] "next field-operation-number sequence")
  (next-order-sequence [s] "next supply-order-number sequence")
  (field-operation-already-scheduled? [s operation-id] "has this field operation already been scheduled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (get-records [s] "the generic id -> raw-record map (domain-agnostic commit-record! path)")
  (with-sites [s sites] "replace/seed the site directory (map id->site)"))

;; ----------------------------- demo/sample data -----------------------------

(defn- sample-sites []
  {"site-001" {:id "site-001" :location "Cedar Hollow" :product-category :resin
               :area-ha 12.5 :harvest-quota-kg 500.0 :quota-used-kg 100.0
               :verified? true :permit-status :active :last-assessed "2026-06-01"}
   "site-002" {:id "site-002" :location "Birchwood Draw" :product-category :cork
               :area-ha 8.0 :harvest-quota-kg 500.0 :quota-used-kg 480.0
               :verified? true :permit-status :active :last-assessed "2026-06-01"}
   "site-003" {:id "site-003" :location "Fern Basin" :product-category :wild-mushrooms
               :area-ha 20.0 :harvest-quota-kg 300.0 :quota-used-kg 0.0
               :verified? false :permit-status :pending :last-assessed "2026-05-15"}})

;; ----------------------------- shared commit logic -----------------------------

(defn- schedule-field-operation!
  "Backend-agnostic `:field-operation/schedule` -- drafts the field-
  operation-schedule record via `nwfp.registry` and returns
  {:result .. :patch ..} for the caller to persist."
  [s operation-id site-id]
  (let [seq-n (next-operation-sequence s)
        result (registry/register-field-operation operation-id site-id seq-n)]
    {:result result
     :patch {:scheduled? true
             :operation-number (get result "operation_number")}}))

(defn- propose-supply-order!
  "Backend-agnostic `:supply-order/propose` -- drafts the supply-order
  record via `nwfp.registry` and returns {:result .. :patch ..} for
  the caller to persist."
  [s order-id]
  (let [seq-n (next-order-sequence s)
        result (registry/register-supply-order order-id seq-n)]
    {:result result
     :patch {:order-number (get result "order_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (site [_ id] (get-in @a [:sites id]))
  (all-sites [_] (sort-by :id (vals (:sites @a))))
  (field-operation [_ id] (get-in @a [:field-operations id]))
  (all-field-operations [_] (sort-by :id (vals (:field-operations @a))))
  (supply-order [_ id] (get-in @a [:supply-orders id]))
  (sustainability-concerns [_] (:sustainability-concerns @a))
  (ledger [_] (:ledger @a))
  (operation-history [_] (:operation-history @a))
  (order-history [_] (:order-history @a))
  (next-operation-sequence [_] (:operation-sequence @a 0))
  (next-order-sequence [_] (:order-sequence @a 0))
  (field-operation-already-scheduled? [_ operation-id]
    (boolean (get-in @a [:field-operations operation-id :scheduled?])))
  (get-records [_] (:records @a))
  (commit-record! [s {:keys [effect path value] :as record}]
    (cond
      (= effect :site/upsert)
      (swap! a update-in [:sites (first path)] merge (assoc value :id (first path)))

      (= effect :field-operation/schedule)
      (let [operation-id (first path)
            site-id (:site-id value)
            {:keys [result patch]} (schedule-field-operation! s operation-id site-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :operation-sequence (fnil inc 0))
                       (update-in [:field-operations operation-id] merge (assoc value :id operation-id) patch)
                       (update :operation-history registry/append result))))
        result)

      (= effect :sustainability-concern/flag)
      (let [concern-id (first path)
            concern (assoc value :id concern-id)]
        (swap! a update :sustainability-concerns conj concern)
        concern)

      (= effect :supply-order/propose)
      (let [order-id (first path)
            {:keys [result patch]} (propose-supply-order! s order-id)]
        (swap! a (fn [state]
                   (-> state
                       (update :order-sequence (fnil inc 0))
                       (update-in [:supply-orders order-id] merge (assoc value :id order-id) patch)
                       (update :order-history registry/append result))))
        result)

      ;; Domain-agnostic path: a raw record with an :id and no :effect
      ;; is written verbatim into the generic `records` map -- the
      ;; store-level primitive underneath the domain-specific dispatch
      ;; above (also what `chemmineops`-style siblings expose as their
      ;; own low-level commit path).
      (and (nil? effect) (:id record))
      (swap! a assoc-in [:records (:id record)] record)

      :else nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-sites [s sites] (when (seq sites) (swap! a assoc :sites sites)) s))

(defn mem-store
  "A fresh, empty MemStore."
  []
  (->MemStore (atom {:sites {} :field-operations {} :supply-orders {}
                      :records {} :sustainability-concerns []
                      :ledger [] :operation-sequence 0 :operation-history []
                      :order-sequence 0 :order-history []})))

(defn sample-data!
  "Seeds `s` (a MemStore) with a small, self-contained site set -- one
  verified in-quota site (gathering-trip eligible), one verified
  NEAR-quota site (scheduling anything but a tiny quantity exceeds its
  permit), one UNVERIFIED site (blocks any field-operation scheduling
  against it) -- so the actor + demo + tests run offline. Returns `s`
  (thread-friendly with `->`)."
  [s]
  (with-sites s (sample-sites))
  s)

;; ----------------------------- back-compat aliases -----------------------------
;; `get-ledger` mirrors `ledger` under the name the actor's own demo/test
;; harness (docs/adr/0001-architecture.md-era code) already calls.

(defn get-ledger [s] (ledger s))
