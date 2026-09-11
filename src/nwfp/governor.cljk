(ns nwfp.governor
  "NWFP Gathering Coordination Governor -- the independent compliance
  layer that earns the NwfpAdvisor the right to commit. The advisor has
  no notion of whether a site it wants to schedule a gathering trip
  against has actually had its permit verified/registered, whether a
  proposal secretly tries to dispatch a field crew directly (rather
  than merely draft-schedule a trip), whether gathering the proposed
  quantity would push a site past its own permitted harvest quota,
  whether a supply order's own claimed total actually equals the sum
  of its own line items, or when an act stops being a coordination
  proposal and becomes direct field-crew dispatch, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD.

  `:itonami.blueprint/governor` is `:nwfp-gathering-coordination-
  governor` (see docs/adr/0001-architecture.md).

  Checks below, ALL HARD violations except the confidence/high-stakes
  gate (SOFT -- asks a human to look, and the human may approve):

    1. Request-level propose-only  -- did the CALLER's own request
                                       actually declare `:effect
                                       :propose`? Any other value is a
                                       mis-wired/compromised caller
                                       trying to bypass proposal-only
                                       mode -- HARD, unconditional,
                                       evaluated BEFORE anything else.
    2. Closed op allowlist         -- is `:op` one of the four ops this
                                       actor is authorized to coordinate?
                                       Anything else -- HARD hold.
    3. Closed effect allowlist     -- is the PROPOSAL's own `:effect`
                                       (what would actually commit) one
                                       of the four propose-shaped
                                       effects? A proposal effect
                                       outside this set (e.g. a
                                       hallucinated `:crew/dispatch` or
                                       `:harvest/execute`) is the
                                       'direct field-crew dispatch'
                                       scope violation this actor must
                                       NEVER perform -- HARD, PERMANENT,
                                       unconditional.
    4. Harvest quota exceeded      -- for `:schedule-field-operation`,
                                       INDEPENDENTLY recompute whether
                                       gathering the proposal's own
                                       claimed `:quantity-kg` would push
                                       the site's cumulative harvest
                                       past its own permitted
                                       `:harvest-quota-kg`
                                       (`nwfp.registry/harvest-exceeds-
                                       quota?`) -- ground truth from the
                                       site's own permanent permit
                                       fields, never a self-reported
                                       quantity claim. This is this
                                       actor's other permanent scope
                                       boundary (see README `What this
                                       actor does NOT do`) -- HARD,
                                       PERMANENT, unconditional. NO
                                       phase and NO human approval can
                                       ever override this (see
                                       `nwfp.phase`: this op is never a
                                       member of any phase's `:auto`
                                       set either -- two independent
                                       layers agree).
    5. Site not verified           -- for `:schedule-field-operation`,
                                       INDEPENDENTLY verify the
                                       referenced site's own
                                       `:verified?` is true (`nwfp.
                                       registry/site-verified?`) --
                                       never trust the advisor's own
                                       rationale about verification
                                       status. Grounded in this
                                       blueprint's own scope boundary:
                                       gathering trips must never be
                                       scheduled against a site whose
                                       permit has not actually been
                                       issued/registered.
    6. Already scheduled           -- for `:schedule-field-operation`,
                                       refuses to schedule the SAME
                                       field-operation record twice, off
                                       a dedicated `:scheduled?` fact
                                       (never a `:status` value).
    7. Invalid product category    -- for `:log-harvest-record`, if the
                                       patch declares a `:product-
                                       category` outside the closed
                                       known set (`nwfp.registry/
                                       product-category-valid?`), the
                                       harvest record is rejected
                                       rather than let a fabricated
                                       category through.
    8. Supply-order total mismatch -- for `:order-supplies`,
                                       INDEPENDENTLY recompute whether
                                       the order's own `:claimed-total`
                                       equals the sum of its own
                                       `:items` (`nwfp.registry/order-
                                       total-matches-claim?`) -- an
                                       honest reapplication of the SAME
                                       ground-truth-recompute discipline
                                       every sibling actor's own cost/
                                       total-matching check establishes.
    9. Confidence floor / high-
       stakes gate                  -- LLM confidence below threshold,
                                       OR the proposal's own `:stake` is
                                       in `high-stakes`
                                       (`:coordination/sustainability-
                                       concern`, ALWAYS set for
                                       `:flag-sustainability-concern`),
                                       OR (for `:order-supplies`) the
                                       order's own independently-
                                       recomputed total exceeds `nwfp.
                                       registry/supply-order-cost-
                                       threshold` -- escalate to a human
                                       gathering-operations/purchasing
                                       approver. SOFT: the human may
                                       approve."
  (:require [nwfp.registry :as registry]
            [nwfp.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed allowlist of coordination proposals this actor may ever
  route -- see README `What this actor does`."
  #{:log-harvest-record :schedule-field-operation
    :flag-sustainability-concern :order-supplies})

(def allowed-proposal-effects
  "The closed allowlist of SSoT-mutation effects a proposal may declare
  -- all four are propose-shaped drafts, NEVER a direct-field-crew-
  dispatch effect."
  #{:site/upsert :field-operation/schedule
    :sustainability-concern/flag :supply-order/propose})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Sustainability concerns are the one op in this domain that always
  demands human eyes regardless of confidence."
  #{:coordination/sustainability-concern})

;; ----------------------------- checks -----------------------------

(defn- no-propose-effect-violations
  "HARD, unconditional, evaluated first: the caller's own request MUST
  declare `:effect :propose` -- any other value is a mis-wired or
  compromised caller trying to bypass proposal-only mode."
  [{:keys [effect]}]
  (when (not= effect :propose)
    [{:rule :not-propose-effect
      :detail (str "request :effect は :propose のみ許可 (受信値: " (pr-str effect) ")")}]))

(defn- unknown-op-violations
  "HARD: `:op` must be one of the closed allowlist this actor
  coordinates -- never route an unrecognized operation."
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :unknown-op
      :detail (str op " はこの actor が扱う操作の許可リストに無い")}]))

(defn- dispatch-blocked-violations
  "HARD, PERMANENT: the proposal's own `:effect` -- what would actually
  commit -- must be within the closed propose-shaped effect allowlist.
  Anything else (direct field-crew dispatch, a fabricated actuation
  effect) is this actor's central scope boundary."
  [proposal]
  (when-not (contains? allowed-proposal-effects (:effect proposal))
    [{:rule :dispatch-blocked
      :detail (str "proposal :effect (" (pr-str (:effect proposal))
                   ") は採取要員の直接派遣に該当する可能性があり、恒久的に禁止")}]))

(defn- harvest-quota-exceeded-violations
  "HARD, PERMANENT, unconditional: for a `:schedule-field-operation`,
  INDEPENDENTLY recompute whether the proposal's own claimed
  `:quantity-kg` would push the site's cumulative harvest past its own
  permitted `:harvest-quota-kg` -- ground truth from the site's own
  permanent permit fields. No override, ever."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [{:keys [site-id quantity-kg]} (:value proposal)
          s (and site-id (store/site st site-id))]
      (when (and s (registry/harvest-exceeds-quota? s quantity-kg))
        [{:rule :harvest-quota-exceeded
          :detail (str site-id " の要求採取量(" quantity-kg "kg)が残り許可量("
                       (registry/harvest-quota-remaining-kg s) "kg)を超過")}]))))

(defn- site-not-verified-violations
  "For `:schedule-field-operation`, INDEPENDENTLY verify the referenced
  site exists and is `:verified?` -- never trust the advisor's own
  report."
  [{:keys [op]} proposal st]
  (when (= op :schedule-field-operation)
    (let [site-id (:site-id (:value proposal))
          s (and site-id (store/site st site-id))]
      (when-not (and s (registry/site-verified? s))
        [{:rule :site-not-verified
          :detail (str site-id " は未検証、または存在しない -- 許可証が登録されていない状態での採取行程提案")}]))))

(defn- already-scheduled-violations
  "For `:schedule-field-operation`, refuses to schedule the SAME
  field-operation record twice, off a dedicated `:scheduled?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :schedule-field-operation)
    (when (store/field-operation-already-scheduled? st subject)
      [{:rule :already-scheduled
        :detail (str subject " は既にスケジュール済み")}])))

(defn- invalid-product-category-violations
  "For `:log-harvest-record`, if the patch declares a `:product-
  category` outside the closed known set, reject rather than let a
  fabricated category through."
  [{:keys [op]} proposal]
  (when (= op :log-harvest-record)
    (let [category (:product-category (:value proposal))]
      (when (and (some? category) (not (registry/product-category-valid? category)))
        [{:rule :invalid-product-category
          :detail (str category " は既知の product-category 値ではない")}]))))

(defn- order-total-mismatch-violations
  "For `:order-supplies`, INDEPENDENTLY recompute whether the order's
  own claimed total equals the sum of its own line items via `nwfp.
  registry/order-total-matches-claim?` -- needs no store lookup at
  all, an honest reapplication of the same discipline every sibling
  actor's own cost/total-matching check establishes."
  [{:keys [op]} proposal]
  (when (= op :order-supplies)
    (let [order (:value proposal)]
      (when-not (registry/order-total-matches-claim? order)
        [{:rule :order-total-mismatch
          :detail (str "申告合計(" (:claimed-total order)
                       ")が独立再計算値(" (registry/order-total order) ")と一致しない")}]))))

(defn check
  "Censors a NwfpAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (no-propose-effect-violations request)
                           (unknown-op-violations request)
                           (dispatch-blocked-violations proposal)
                           (harvest-quota-exceeded-violations request proposal st)
                           (site-not-verified-violations request proposal st)
                           (already-scheduled-violations request st)
                           (invalid-product-category-violations request proposal)
                           (order-total-mismatch-violations request proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        high-cost-order? (and (= (:op request) :order-supplies)
                               (registry/order-exceeds-threshold? (:value proposal)))
        stakes? (or (boolean (high-stakes (:stake proposal))) high-cost-order?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
