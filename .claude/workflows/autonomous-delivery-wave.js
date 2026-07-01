export const meta = {
  name: 'autonomous-delivery-wave',
  description: "Livre une vague de N features V1 du backlog PRODUCT_SPEC (claude-gateway) en équipe d'agents, gouvernance CLAUDE.md respectée, décider-par-défaut + flag, auto-merge, plafond budget. NE CRÉE PAS de feature. V1 = gateway pure (refuse OCR/RAG/pgvector).",
  whenToUse: "Vague autonome : 'livre N features V1 du backlog, récap à la fin'. Suppose ai-skills/autonomous-delivery-wave.md.",
  phases: [
    { title: 'Bootstrap', detail: 'git fetch origin/main, détecter SF en cours, lire mémoire + PROJECT.md' },
    { title: 'Audit+File', detail: 'file des features V1 « À faire » de PRODUCT_SPEC + périmètre gateway pure' },
    { title: 'Classer', detail: 'étiqueter chaque feature 🟢/🟠/🔴' },
    { title: 'Livrer', detail: 'par feature : cadrage → mini-spec → readiness → dev(back//front) → checklists → merge' },
    { title: 'Docs+Staging', detail: 'docs groupées + 1 déploiement staging unique' },
    { title: 'Récap', detail: "récap unique d'arbitrages" },
  ],
}

// ─── args ────────────────────────────────────────────────────────────────────
// args = { waveSize?, dateISO?, features?: string[], concurrency? }
// dateISO OBLIGATOIRE (Date.now() interdit dans les scripts Workflow) — passé au lancement.
const WAVE_SIZE = (args && args.waveSize) || 10
const DATE = (args && args.dateISO) || 'DATE-A-PASSER-EN-ARGS'
const EXPLICIT = (args && args.features) || null   // si fournie, court-circuite l'audit→file
const CONCURRENCY = (args && args.concurrency) || 6 // borné par le rate-limit Anthropic, pas le CPU

// ─── schémas de sortie (validation au tool-call, pas de parsing) ─────────────
const QUEUE_SCHEMA = {
  type: 'object',
  required: ['inProgress', 'queue'],
  properties: {
    inProgress: { type: 'array', items: { type: 'object', required: ['id', 'state', 'action'],
      properties: { id: {type:'string'}, state:{type:'string'}, action:{type:'string'} } } },
    queue: { type: 'array', items: { type: 'object', required: ['id', 'title', 'risk', 'subfeatures'],
      properties: {
        id: { type: 'string' }, title: { type: 'string' },
        risk: { enum: ['green', 'orange', 'red'] },
        reason: { type: 'string' },
        parallelizable: { type: 'boolean' },
        subfeatures: { type: 'array', items: { type: 'string' } },
      } } },
  },
}
const DELIVERY_SCHEMA = {
  type: 'object',
  required: ['featureId', 'status'],
  properties: {
    featureId: { type: 'string' },
    status: { enum: ['merged', 'halted', 'failed'] },
    prs: { type: 'array', items: { type: 'string' } },
    arbitrages: { type: 'array', items: { type: 'object', required: ['decision', 'why', 'reversible'],
      properties: { gate:{type:'string'}, decision:{type:'string'}, why:{type:'string'},
        alternative:{type:'string'}, reversible:{type:'boolean'} } } },
    haltQuestion: { type: 'string' },
    residualRisks: { type: 'array', items: { type: 'string' } },
  },
}

// ─── Phase 1 : Bootstrap + File ──────────────────────────────────────────────
phase('Bootstrap')
log('Vague autonome claude-gateway — bootstrap état + file V1')

const plan = await agent(
  `Tu prépares une vague de livraison autonome de ${WAVE_SIZE} features V1 pour claude-gateway.
   Lis et applique ai-skills/autonomous-delivery-wave.md (Phases 0 et 1) + docs/PROJECT.md (source de vérité).
   1) git fetch origin ; raisonne sur origin/main. Détecte les sessions parallèles actives
      (git reflog -10 = pulls/rebases non émis par toi).
   2) Détecte le travail RÉELLEMENT en cours non terminé. ⚠️ NE te fie PAS à 'git branch --no-merged' :
      le repo merge en SQUASH → les branches livrées apparaissent faussement non-mergées.
      Source de vérité = statut PRODUCT_SPEC.md ('Terminée' = fini) + gh pr list --state open.
      Pour une branche suspecte, teste le CONTENU : git cherry origin/main feat/SF-X (vide = déjà dans main). -> inProgress.
   3) Lis MEMORY.md + les mémoires projet pertinentes (déploiement staging, pivot V1, git-flow-autonomy).
   4) ${EXPLICIT ? `File IMPOSÉE par le PO : ${JSON.stringify(EXPLICIT)}. Ordonne-la par dépendances.`
        : `Construis la file des ${WAVE_SIZE} features V1 prioritaires statut "À faire"/"À spécifier" de
           docs/PRODUCT_SPEC.md (features V1 uniquement : F-01,02,03,04,09,10,11,12 ; JAMAIS les V2 F-05/06/07/08).
           Ordonne par dépendances → valeur → effort. F-01 (auth) conditionne tout : en premier.`}
   5) PÉRIMÈTRE V1 = PASSERELLE PURE. Toute feature/subfeature impliquant OCR, Textract, embeddings,
      pgvector, RAG, chunking, recherche vectorielle, indexation documentaire → HORS SCOPE (risk='red', reason).
   6) Classe CHAQUE feature 🟢 green / 🟠 orange / 🔴 red (cf. skill Phase 2) avec reason, et pour chacune
      liste ses subfeatures prévues (SF-XX-YY) et si elle est parallélisable back/front.
   NE CRÉE AUCUNE feature absente de PRODUCT_SPEC.md. Retourne le JSON QUEUE_SCHEMA.`,
  { label: 'bootstrap+queue', phase: 'Audit+File', schema: QUEUE_SCHEMA, agentType: 'Explore' }
)

log(`File: ${plan.queue.map(f => `${f.id}(${f.risk})`).join(', ')}`)
if (plan.inProgress.length) log(`En cours à finir d'abord: ${plan.inProgress.map(p => p.id).join(', ')}`)

const toFinish = plan.inProgress.map(p => ({ id: p.id, title: p.action, risk: 'green', resume: true, subfeatures: [] }))
const toDeliver = [...toFinish, ...plan.queue.filter(f => f.risk !== 'red')]
const parked = plan.queue.filter(f => f.risk === 'red')

phase('Classer')
log(`${toDeliver.length} à livrer, ${parked.length} parkées (🔴 HALT / hors-scope V1)`)

// ─── Phase 2 : Livraison — pipeline par feature, budget-borné ─────────────────
phase('Livrer')
const results = []
for (const f of toDeliver) {
  if (budget.total && budget.remaining() < 80_000) {
    log(`Budget restant ${Math.round(budget.remaining()/1000)}k < seuil — arrêt propre avant ${f.id}`)
    break
  }
  log(`▶ ${f.id} (${f.risk}) — reste ${budget.total ? Math.round(budget.remaining()/1000)+'k' : '∞'}`)

  const res = await agent(
    `Tu livres la feature ${f.id} ("${f.title}") de claude-gateway de bout en bout en autonomie.
     ${f.resume ? 'REPRENDS le travail en cours non terminé de cette feature et FINIS-le.' : ''}
     Applique INTÉGRALEMENT ai-skills/autonomous-delivery-wave.md (Phase 3) + feature-autonome.md
     + parallel-frontback-delivery.md, et respecte docs/PROJECT.md (V1 = gateway pure) + CLAUDE.md.
     Séquence CLAUDE.md par SF : cadrage cohérence → mini-spec (docs/features/${f.id}/) → readiness →
     dev (back//front si contrat API figé, worktrees isolés, UUID Liquibase + n° migration pré-assignés,
     isolation user_id sur tout accès données, provider via interface AIProvider jamais Anthropic direct) →
     compile+tests verts (mvn -pl backend test ; npm run build && npm test) →
     review checklist → release checklist → gh pr create → gh pr merge --squash --delete-branch
     (backend AVANT frontend ; secrets hors du code ; pas de clé loggée).
     RÈGLE GATE: sur un gate produit RÉVERSIBLE (🟠 cohérence écran, choix UX, nouvelle table simple),
     DÉCIDE par défaut, implémente, et TRACE l'arbitrage. Sur IRRÉVERSIBLE/sécurité/coûteux non réversible,
     ou toute dérive hors périmètre V1 (OCR/RAG/pgvector/Textract) → status 'halted' + haltQuestion.
     INTERDIT: déploiement staging par feature (l'orchestrateur déploie une seule fois à la fin).
     Si main casse post-merge et non résolu en 2 tentatives/30min → revert + status 'failed'.
     NE CRÉE AUCUNE feature. Retourne le JSON DELIVERY_SCHEMA.`,
    { label: `deliver:${f.id}`, phase: 'Livrer', schema: DELIVERY_SCHEMA, isolation: 'worktree' }
  )
  results.push(res)
  log(`${f.id} → ${res ? res.status : 'null'}`)
}

// ─── Phase 3 : Docs groupées + staging unique ────────────────────────────────
phase('Docs+Staging')
const merged = results.filter(r => r && r.status === 'merged')
if (merged.length) {
  await agent(
    `Docs post-merge groupées de la vague (Phase 4 de la skill, volet docs uniquement).
     Features mergées: ${merged.map(m => m.featureId).join(', ')}.
     UN commit docs/wave-${DATE}-complete : statuts "Terminée" dans docs/PRODUCT_SPEC.md pour chaque
     feature dont TOUTES les SF sont mergées, 1 entrée historique PAR SF, MAJ docs/ARCHITECTURE_CANONIQUE.md
     si de nouvelles tables ont été créées. Ne touche PAS à .github/ ni .claude/.
     ⚠️ NE DÉPLOIE PAS en staging : le déploiement (build images + APP_JWT_SECRET + kustomize) est fait
     manuellement par l'orchestrateur après revue de la vague. Commit docs uniquement.`,
    { label: 'docs', phase: 'Docs+Staging' }
  )
}

// ─── Phase 4 : Récap unique d'arbitrages ─────────────────────────────────────
phase('Récap')
return {
  date: DATE,
  delivered: merged.map(m => ({ id: m.featureId, prs: m.prs, arbitrages: m.arbitrages || [], risks: m.residualRisks || [] })),
  halted: results.filter(r => r && r.status === 'halted').map(r => ({ id: r.featureId, question: r.haltQuestion })),
  parkedRed: parked.map(f => ({ id: f.id, reason: f.reason })),
  failed: results.filter(r => r && r.status === 'failed').map(r => r.featureId),
  notReached: toDeliver.slice(results.length).map(f => f.id),
  budgetSpent: budget.total ? `${Math.round(budget.spent()/1000)}k / ${Math.round(budget.total/1000)}k` : 'illimité',
}
