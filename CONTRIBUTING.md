# Contributing

**Maturity: `:implemented`** — `src/nwfp/` implements the reference
NwfpAdvisor / NWFP Gathering Coordination Governor actor as a full
langgraph-clj StateGraph (`nwfp.operation`), with `interrupt-before`/
checkpoint-based human-in-the-loop resume for escalated operations.
Contributions that extend coverage are welcome: a Datomic/kotoba-server
`Store` backend, a real LLM `Advisor` implementation, additional Governor
rules, and jurisdiction/species/product-category reference-data expansion
in `nwfp.registry`. Open an issue or PR. License: AGPL-3.0-or-later.
