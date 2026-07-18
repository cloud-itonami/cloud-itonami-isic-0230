(ns nwfp.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 7): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`nwfp.operation` -> `nwfp.governor` -> `nwfp.store`) through a
  scenario adapted from this repo's own `nwfp.sim` demo driver
  (`clojure -M:dev:run`, confirmed to run correctly against the real
  seeded site directory before this file was written -- every id/subject
  it references (site-001/site-002/site-003, op-1/op-2/op-3,
  concern-1, order-1/order-2/order-3) traces to `nwfp.store/sample-
  sites` or is a fresh subject id created by the scenario itself, and
  every disposition it produces matches `nwfp.governor`'s own rules
  exactly -- unlike `cloud-itonami-isic-851`'s `schoolops.sim`, this
  repo's own sim driver was safe to mine directly rather than author
  from scratch), trimmed to a representative subset (one phase-3
  auto-commit, two escalate-then-approve dispositions with distinct
  escalation reasons, and three distinct HARD-hold reasons) and
  rendered deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verified by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [nwfp.store :as store]
            [nwfp.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :gathering-operations-manager :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: site-001 clears a clean harvest-record patch
  (phase-3 auto-commit, no risk); a gathering-trip scheduling proposal
  against site-001 (verified, in-quota) escalates on the phase gate
  (`:schedule-field-operation` is never auto-eligible at any phase) and
  is approved; a sustainability-concern flag on site-001 ALWAYS
  escalates (governor `high-stakes`) and is approved; a supply order
  below the cost threshold escalates on the phase gate and is approved;
  a second supply order whose independently-recomputed total EXCEEDS
  the cost threshold escalates for a distinct reason (governor
  high-stakes cost gate, not just the phase gate) and is approved.
  Then three distinct HARD-hold reasons, none reaching a human: a
  gathering-trip proposal against site-003 (UNVERIFIED -- its permit
  has never been issued/registered); a gathering-trip proposal against
  site-002 requesting 100kg against only 20kg of remaining permitted
  quota; a supply order whose claimed total (1000.0) does not match
  its own line items' independently recomputed total (750.0). Returns
  the resulting store -- every field read by `render` below is real
  governor/store output, not a hand-typed copy."
  []
  (let [db (-> (store/mem-store) (store/sample-data!))
        actor (op/build db)]
    (exec! actor "site001-harvest" {:op :log-harvest-record :effect :propose :subject "site-001"
                                     :patch {:product-category :resin :last-assessed "2026-07-14"}})

    (exec! actor "op1-schedule" {:op :schedule-field-operation :effect :propose :subject "op-1"
                                  :value {:site-id "site-001" :product-category :resin
                                          :quantity-kg 50.0 :scheduled-date "2026-08-01"}})
    (approve! actor "op1-schedule")

    (exec! actor "concern1-flag" {:op :flag-sustainability-concern :effect :propose :subject "concern-1"
                                   :value {:site-id "site-001" :severity :moderate
                                           :description "近隣個体群の樹脂採取圧の兆候"}})
    (approve! actor "concern1-flag")

    (exec! actor "order1-place" {:op :order-supplies :effect :propose :subject "order-1"
                                  :value {:items [{:name "tapping-tools" :qty 20 :unit-cost 25.0}
                                                   {:name "collection-bags" :qty 200 :unit-cost 1.25}]
                                          :claimed-total 750.0}})
    (approve! actor "order1-place")

    (exec! actor "order3-place" {:op :order-supplies :effect :propose :subject "order-3"
                                  :value {:items [{:name "distillation-still" :qty 2 :unit-cost 4000.0}]
                                          :claimed-total 8000.0}})
    (approve! actor "order3-place")

    (exec! actor "op2-schedule" {:op :schedule-field-operation :effect :propose :subject "op-2"
                                  :value {:site-id "site-003" :product-category :wild-mushrooms
                                          :quantity-kg 10.0 :scheduled-date "2026-08-01"}})

    (exec! actor "op3-schedule" {:op :schedule-field-operation :effect :propose :subject "op-3"
                                  :value {:site-id "site-002" :product-category :cork
                                          :quantity-kg 100.0 :scheduled-date "2026-08-01"}})

    (exec! actor "order2-place" {:op :order-supplies :effect :propose :subject "order-2"
                                  :value {:items [{:name "permit-fees" :qty 100 :unit-cost 7.5}]
                                          :claimed-total 1000.0}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- site-row [ledger {:keys [id location product-category harvest-quota-kg quota-used-kg verified? permit-status]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc location) (esc (name (or product-category :n-a)))
          (esc (str quota-used-kg "/" harvest-quota-kg " kg"))
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"warn\">unverified</span>")
          (esc (name (or permit-status :n-a)))
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract (README
  ;; `Ops` table, `nwfp.governor`/`nwfp.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-harvest-record</code></td><td><span class=\"ok\">phase-3 auto when clean</span></td></tr>"
   "        <tr><td><code>:schedule-field-operation</code></td><td><span class=\"warn\">ALWAYS human approval &middot; site-verified?/quota independently re-checked &middot; over-quota is a permanent HARD block, no phase or approval can ever override it</span></td></tr>"
   "        <tr><td><code>:flag-sustainability-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto, any phase</span></td></tr>"
   "        <tr><td><code>:order-supplies</code></td><td><span class=\"warn\">ALWAYS human approval &middot; independent line-item total recompute &middot; escalates further if the recomputed total exceeds the cost threshold</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        sites (store/all-sites db)
        site-rows (str/join "\n" (map (partial site-row ledger) sites))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-0230 &middot; non-wood forest product gathering coordination</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Non-wood forest product gathering coordination (ISIC 0230) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never dispatches field crews or executes purchases directly</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Gathering sites</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>nwfp.store</code> via <code>nwfp.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Site</th><th>Location</th><th>Product</th><th>Quota used/total</th><th>Permit</th><th>Status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     site-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (NWFP Gathering Coordination Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Harvest quotas and supply-order totals are independently recomputed, never trusted from the proposal; a site's own verification status is independently re-checked, never trusted from the advisor's rationale.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/operation-history db)) "field operations scheduled,"
             (count (store/order-history db)) "supply orders placed )")))
