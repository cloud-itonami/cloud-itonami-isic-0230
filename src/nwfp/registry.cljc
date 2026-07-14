(ns nwfp.registry
  "Pure-function domain logic for the non-wood-forest-product (NWFP)
  gathering coordination actor -- gathering-site/permit verification
  checks, harvest-quota ground-truth recompute, product-category
  validation, supply-order budget verification, and draft field-
  operation/supply-order record construction.

  This vertical has NO pre-existing `kotoba-lang/nwfp`-style capability
  library to wrap (mirrors cloud-itonami-isic-0210's own Decision 1 --
  see docs/adr/0001-architecture.md). The domain logic therefore lives
  here as pure functions, re-verified INDEPENDENTLY by `nwfp.governor`
  -- the same 'ground truth, not self-report' discipline every sibling
  actor's own registry establishes (e.g. `forestry.registry/stand-
  immature-for-harvest?`, `chemmineops.registry/royalty-matches-
  claim?`): never trust a proposal's own self-reported total/quantity/
  verdict when the inputs needed to recompute it independently are
  already on record.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real forestry/permitting system. It builds the DRAFT
  record a gathering coordinator would keep (a scheduled gathering
  trip, a supply order), not the act of dispatching field crews,
  physically harvesting a non-wood forest product, or placing a real
  purchase order (this actor NEVER does either -- see README `What
  this actor does NOT do`).")

;; ----------------------------- constants -----------------------------

(def valid-product-categories
  "The closed set of non-wood-forest-product category values a harvest
  record may declare. Anything else is a fabricated/unrecognized
  category -- the governor HARD-holds rather than let an invented
  category pass through (mirrors `forestry.registry/valid-health-
  statuses`)."
  #{:resin :cork :bark :nuts :wild-mushrooms :medicinal-plants})

(def supply-order-cost-threshold
  "Supply orders whose independently-recomputed total exceeds this
  amount always escalate to a human gathering-operations approver,
  regardless of confidence -- see `nwfp.governor`'s high-stakes gate."
  5000.0)

;; ----------------------------- site checks -----------------------------

(defn site-verified?
  "Ground-truth check: has `site`'s own record been marked verified
  (i.e. its gathering permit has actually been issued and its
  boundary registered in the SSoT, not merely logged from an
  unverified harvest-record patch)? A pure predicate over the site's
  own permanent field -- no proposal inspection needed."
  [site]
  (true? (:verified? site)))

(defn harvest-quota-remaining-kg
  "The ground-truth remaining permit quota for `site` -- its own
  recorded `:harvest-quota-kg` minus its own recorded `:quota-used-kg`.
  Both are permanent fields on the site's own permit record, never a
  self-reported running total from a proposal."
  [{:keys [harvest-quota-kg quota-used-kg]}]
  (- (double (or harvest-quota-kg 0)) (double (or quota-used-kg 0))))

(defn harvest-exceeds-quota?
  "Ground-truth check for a `:schedule-field-operation` proposal:
  would gathering `quantity-kg` against `site` push its cumulative
  harvest past its own permitted `:harvest-quota-kg`? Needs no
  proposal-level trust -- both inputs (the site's own permit fields,
  the proposal's own claimed quantity) are independently re-derived by
  `nwfp.governor` before this predicate is ever consulted."
  [site quantity-kg]
  (let [q (double (or quantity-kg 0))]
    (> q (harvest-quota-remaining-kg site))))

(defn product-category-valid?
  "Is `category` one of the closed, known non-wood-forest-product
  category values? nil/blank is treated as invalid (a harvest-record
  patch must declare a real category, not omit it silently)."
  [category]
  (contains? valid-product-categories category))

;; ----------------------------- supply-order checks -----------------------------

(defn order-total
  "The ground-truth total for `order`'s own `:items` (each `{:qty n
  :unit-cost c}`) -- independent of whatever `:claimed-total` the
  proposal itself carries."
  [{:keys [items]}]
  (reduce (fn [acc {:keys [qty unit-cost]}]
            (+ acc (* (double (or qty 0)) (double (or unit-cost 0)))))
          0.0
          items))

(defn order-total-matches-claim?
  "Does `order`'s own `:claimed-total` equal the independently
  recomputed `order-total`? An honest reapplication of the SAME
  ground-truth-recompute discipline every sibling actor's own cost/
  total-matching check establishes, reapplied to a gathering supply-
  order line."
  [{:keys [claimed-total] :as order}]
  (and (number? claimed-total)
       (== (double claimed-total) (order-total order))))

(defn order-exceeds-threshold?
  "Does `order`'s own independently-recomputed total exceed
  `supply-order-cost-threshold`? Computed from the order's own line
  items, never from a self-reported `:stake` or confidence value."
  [order]
  (> (order-total order) supply-order-cost-threshold))

;; ----------------------------- draft record construction -----------------------------

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the human gathering-operations approver's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-field-operation
  "Validate + construct the FIELD-OPERATION SCHEDULING DRAFT -- a
  proposed gathering trip on a verified, in-quota site. Pure function
  -- does not dispatch a field crew or physically gather any product;
  it builds the RECORD a coordinator would keep. `nwfp.governor`
  independently re-verifies the site's own verified/quota ground
  truth before this is ever allowed to commit."
  [operation-id site-id sequence]
  (when-not (and operation-id (not= operation-id ""))
    (throw (ex-info "field-operation: operation_id required" {})))
  (when-not (and site-id (not= site-id ""))
    (throw (ex-info "field-operation: site_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "field-operation: sequence must be >= 0" {})))
  (let [operation-number (str "FOP-" (zero-pad sequence 6))
        record {"record_id" operation-number
                "kind" "field-operation-schedule-draft"
                "operation_id" operation-id
                "site_id" site-id
                "immutable" true}]
    {"record" record "operation_number" operation-number
     "certificate" (unsigned-certificate "FieldOperationSchedule" operation-number operation-number)}))

(defn register-supply-order
  "Validate + construct the SUPPLY-ORDER DRAFT -- a proposed equipment/
  permit-fee procurement. Pure function -- does not place any real
  purchase order; it builds the RECORD a coordinator would keep.
  `nwfp.governor` independently re-verifies the order's own claimed-
  total against `order-total`, and escalates (never auto-commits) any
  order whose recomputed total exceeds `supply-order-cost-threshold`,
  before this is ever allowed to commit."
  [order-id sequence]
  (when-not (and order-id (not= order-id ""))
    (throw (ex-info "supply-order: order_id required" {})))
  (when (< sequence 0)
    (throw (ex-info "supply-order: sequence must be >= 0" {})))
  (let [order-number (str "ORD-" (zero-pad sequence 6))
        record {"record_id" order-number
                "kind" "supply-order-draft"
                "order_id" order-id
                "immutable" true}]
    {"record" record "order_number" order-number
     "certificate" (unsigned-certificate "SupplyOrder" order-number order-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
