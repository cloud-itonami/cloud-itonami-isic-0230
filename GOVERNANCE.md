# Governance

Maintained by the cloud-itonami org (gftdcojp). Decisions land as ADRs in the
superproject ledger. The actor pattern (advisor-LLM sealed behind an
independent governor, append-only audit ledger) is non-negotiable per
ADR-2607011000: the governor gates every action; direct field-crew
dispatch, gathering beyond a site's own permitted harvest quota, and any
non-`:propose` effect are permanently blocked; `:high`/`:safety-critical`
actions (sustainability/over-harvest concerns, high-cost supply orders)
require human sign-off.
