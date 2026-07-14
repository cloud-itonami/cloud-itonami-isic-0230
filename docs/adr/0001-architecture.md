# ADR-0001: NwfpAdvisor ⊣ NWFP Gathering Coordination Governor architecture

## Status

Accepted. `cloud-itonami-isic-0230` built as a fresh scaffold (no prior
repo, no prior `:blueprint`/`:spec`-only publication) and promoted directly
to `:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-0230` publishes an OSS blueprint for community
gathering of non-wood forest products (NWFP): resin, cork, bark, nuts, wild
mushrooms, and medicinal plants. Like every actor in this fleet, the
blueprint alone is not an implementation: this ADR records the
governed-actor architecture that promotes it to real, tested code,
following the same langgraph StateGraph + independent Governor + Phase 0->3
rollout pattern established across the cloud-itonami fleet, closely
mirroring `cloud-itonami-isic-0210` (Silviculture and other forestry
activities) -- this fleet's closest domain analog (forestry operations
coordination).

This vertical has NO bespoke domain capability library in `kotoba-lang` to
wrap (there is no `kotoba-lang/nwfp`-style repo). This build therefore uses
self-contained domain logic -- pure functions in `nwfp.registry`
(`harvest-exceeds-quota?` ground-truth permit-quota checks against a
site's own `:harvest-quota-kg`/`:quota-used-kg`, `product-category-valid?`
product-category validation, `order-total-matches-claim?`/`order-exceeds-
threshold?` supply budget verification, plus `register-field-operation`/
`register-supply-order` draft-record construction) are re-verified
independently by `nwfp.governor` -- the same "ground truth, not
self-report" discipline established across prior actors. `nwfp.store`
ships a single `MemStore` backend (no second Datomic-backed store): this
vertical's SSoT needs no jurisdiction-scoped parity requirement, and a
second backend can be added later behind the same `Store` protocol without
changing any caller.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:nwfp-gathering-coordination-governor`, is intended to be unique
fleet-wide (verified against the closest sibling names at the time of
writing, `:forest-coordination-governor` for ISIC 0210 and
`:ranching-operations-governor` for ISIC 0141).

## Decision

### Decision 1: Self-contained domain logic (no external NWFP capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this NWFP
gathering vertical has NO pre-existing capability library to wrap. The
permit-quota / product-category / supply-budget validation functions live
as pure functions in `nwfp.registry` and are re-verified independently by
`nwfp.governor` -- the same "ground truth, not self-report" discipline
established across prior actors.

### Decision 2: Coordination, not control -- scope boundary at the back-office

This actor is **strictly back-office coordination** of NWFP gathering
operations. It does NOT:
- Dispatch field crews or perform any gathering directly
- Make land-use or protected-species management decisions (exclusive to
  the permit-issuing authority / landowner)
- Authorize gathering beyond a site's own permitted harvest quota

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human gathering-operations
manager approval. This is not a replacement for permit-holder authority --
it is a proposal-screening and documentation layer.

### Decision 3: Dual-escalation shape: sustainability concerns and supply orders both require approval

`:flag-sustainability-concern` (over-harvest/protected-species risk)
ALWAYS escalates, never auto-commits. Supply orders above a cost threshold
also escalate. Neither are "low-stakes proposals that the coordinator can
decide alone."

### Decision 4: HARD invariants (no override)

Four HARD governor checks that block proposals and cannot be overridden by
human approval:
1. Site must exist and be registered/permit-verified in the SSoT before a
   gathering trip may be scheduled against it
2. Proposals must be `:effect :propose` only (never direct control)
3. Direct field-crew dispatch (a proposal `:effect` outside the closed
   propose-shaped allowlist) is permanently blocked
4. Gathering a proposal's own claimed quantity that would push a site past
   its own independently-recomputed permitted harvest quota is permanently
   blocked

## Consequences

(+) NWFP gathering operations back-office now has a documented, governed,
auditable coordination layer that funnels all decisions through
independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human
gathering-operations sign-off.

(+) Scope is bounded and verifiable: four HARD invariants protect against
scope creep into unauthorized field-crew dispatch or over-quota
harvesting.

(-) Still a simulation/proposal layer, not a real field-operations control
system. Field-crew dispatch and physical gathering remain
human-controlled via external channels.

(-) No integration with real permitting/land-management databases (GIS,
species-population models, regulatory reporting) -- this is a standalone
coordinator blueprint.

## Verification

- `cloud-itonami-isic-0230`: `clojure -M:test` (and the equivalent
  `clojure -M:dev:test`) green (all tests pass) across
  `nwfp.operation-test`, `nwfp.governor-contract-test`, `nwfp.phase-test`,
  `nwfp.store-contract-test` and `nwfp.registry-test`; `clojure -M:dev:run`
  demo narrative exercises proposal submission, escalation, and every
  HARD-hold scenario directly (not-propose-effect, unknown-op,
  site-not-verified, harvest-quota-exceeded, already-scheduled,
  invalid-product-category, order-total-mismatch) plus the over-threshold
  order-supplies ESCALATE (not HOLD) case.
- All source is `.cljc` (portable ClojureScript / JVM / nbb) -- no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `clojure -M:test` resolves offline inside the monorepo checkout.
