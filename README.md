# cloud-itonami-isic-0230: Gathering of non-wood forest products

An autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office non-wood-forest-product (NWFP) gathering operations: harvest-record logging, field-trip scheduling, sustainability-concern monitoring, and supply procurement.

## What this actor does

Proposes **coordination** of NWFP gathering operations:
- `:log-harvest-record` — quantity/species/location gathering data logging (proposal only)
- `:schedule-field-operation` — gathering-trip scheduling proposal
- `:flag-sustainability-concern` — surface an over-harvest/protected-species concern (always escalates)
- `:order-supplies` — equipment/permit-fee procurement proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY:**
- Does NOT dispatch field crews or gather any product directly
- Does NOT make land-use or protected-species management decisions (that's the permit-issuing authority's/landowner's exclusive human authority)
- Does NOT authorize gathering beyond a site's own independently-verified permitted harvest quota
- ONLY proposes/coordinates operations back-office; all actuation requires explicit human approval

## Architecture

Classic governed-actor pattern (`nwfp.operation/build`, a langgraph-clj StateGraph):
1. **`nwfp.advisor`** (sealed intelligence node, `NwfpAdvisor`): proposes decisions only, never commits
2. **`nwfp.governor`** (independent, `NWFP Gathering Coordination Governor`): validates against domain rules, re-derived from `nwfp.registry`'s pure functions and `nwfp.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct field-crew-dispatch effect)
     - A field operation may only be scheduled against a gathering site independently verified/permit-registered in the SSoT
     - A gathering trip's own claimed `:quantity-kg` may never push a site's cumulative harvest past its own independently-recomputed permitted `:harvest-quota-kg` (permanent, no override)
     - No double-scheduling the same field-operation record
     - No fabricated `:product-category` value on a harvest-record patch
     - A supply order's claimed total must independently recompute correctly from its own line items
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-sustainability-concern` always escalates, regardless of confidence
     - `:order-supplies` whose independently-recomputed total exceeds `nwfp.registry/supply-order-cost-threshold`
     - Low-confidence proposals
3. **`nwfp.phase`** (Phase 0->3 rollout): `:schedule-field-operation`/`:flag-sustainability-concern`/`:order-supplies` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-harvest-record` may auto-commit at phase 3 when clean
4. **`nwfp.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
