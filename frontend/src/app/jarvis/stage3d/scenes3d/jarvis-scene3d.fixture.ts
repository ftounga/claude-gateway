import { SceneData, SceneView } from '../../scenes/jarvis-scene-reader';
import type { Ambient } from '../../scenes/jarvis-scene-bus.service';
import { NodeRef } from '../../scenes/jarvis-universe.models';

/**
 * F-236 — **scènes F-234 de test** (univers de test : CAGIP, sujet « Migration Forge », chantier 3 « Bascule DNS »,
 * Zahi attendu ; Edenred déconnecté). Partagées par les tests des scènes 3D et par le banc de captures.
 */

export const FORGE: NodeRef = { kind: 'subject', key: 's-forge', label: 'Migration Forge', clientKey: 'h-cagip',
  subjectId: 's-forge' };

/** Zahi, attendu par le chantier 3 (nœud posé par la disposition). */
export const ZAHI: NodeRef = { kind: 'person', key: 'h-cagip:zahi', label: 'Zahi', clientKey: 'h-cagip' };

/** Une scène F-234 telle que le lecteur la publie. */
export function sceneOf(id: string, key: string, libelle: string, extra: Partial<SceneView> = {},
    data?: SceneData): SceneView {
  return { id, key, libelle, count: 1, detail: null, entity: FORGE, pinned: false, since: 0, updatedAt: 0, data,
    ...extra };
}

/** Les scènes du banc, par nom de scène du banc (`scene-<nom>`). */
export const BANC_SCENE_VIEWS: Readonly<Record<string, SceneView>> = {
  sgen: sceneOf('GENERIQUE', 'radar_sujets', 'Radar · sujets du client', { count: 3,
    detail: 'radar sujets CAGIP' }),
  // SF-236-09 — commandes, tickets, fichiers.
  commande: sceneOf('S3', 'bash', 'Commande sur le poste', { detail: 'kubectl get pods -n forge' },
    { lines: ['forge-api-7d9c   1/1   Running   0   2d', 'export TOKEN=•••'] }),
  tickets: sceneOf('S4', 'tickets-Jira', 'Jira · consultation', { detail: 'acli jira workitem view FORGE-41' },
    { tickets: ['FORGE-41', 'FORGE-42', 'OPS-7'], source: 'Jira' }),
  fichiers: sceneOf('S5', 'write_file', 'Écriture d’un fichier', { detail: 'src/app/dns/bascule.ts' },
    { mode: 'write', files: ['plan.md', 'ttl.ts', 'bascule.ts'] }),
  lecture: sceneOf('S5', 'read_file', 'Lecture d’un fichier', { detail: 'docs/archi/dossier-bascule.pdf' },
    { mode: 'read', files: ['dossier-bascule.pdf'] }),
  recherche: sceneOf('S5', 'grep', 'Recherche dans les fichiers', { detail: 'ttl' }, { mode: 'search', pattern: 'ttl' }),
  // SF-236-10 — chantiers, étapes, attentes, « oui ».
  etape: sceneOf('S9', 'update_plan_step', 'L’étape avance', { detail: 'chantier 3 · étape 3 faite' },
    { progress: 'step', chantierKey: 's-forge#3', step: 3, status: 'faite' }),
  attente: sceneOf('S11', 'attente', 'Attente posée', {}, { person: 'Zahi', category: 'validation', attached: true }),
  'attente-levee': sceneOf('S11', 'attente', 'Attente levée', {},
    { person: 'Zahi', category: 'validation', attached: false }),
  oui: sceneOf('S2', 'confirm', 'J’attends votre « oui »', { pinned: true, detail: 'kubectl apply -f dns.yaml',
    since: 0 }, { deadline: 30_000, remaining: 18 }),
  question: sceneOf('S2', 'question', 'J’ai une question pour vous', { pinned: true,
    detail: 'Réponse attendue à l’écran du terminal' }, { question: true }),
  points: sceneOf('S14', 'questions', 'Points de route · 2/4', {}, { questions: [
    { libelle: 'Vérifier le TTL', state: 'TERMINEE' }, { libelle: 'Basculer la zone', state: 'TERMINEE' },
    { libelle: 'Prévenir Zahi', state: 'EN_COURS' }, { libelle: 'Surveiller 24 h', state: 'A_FAIRE' }] }),
  'le-point': sceneOf('S15', 'sujets_etat', 'Le point sur les sujets'),
  // SF-236-11 — À envoyer (le corps n'est jamais lu : seuls destinataire et objet).
  message: sceneOf('S1', 'block-mail', 'Mail en préparation', { entity: ZAHI, detail: 'À : Zahi · Objet : Bascule DNS' },
    { envelope: 'mail', to: 'Zahi', subject: 'Bascule DNS samedi', finished: false }),
  envoi: sceneOf('S1', 'block-mail', 'Mail prêt · à envoyer', { entity: ZAHI, detail: 'À : Zahi · Objet : Bascule DNS' },
    { envelope: 'mail', to: 'Zahi', subject: 'Bascule DNS samedi', finished: true }),
  // SF-236-12 — web, création, vidéo, passation, mémoire, essaim, veille.
  web: sceneOf('S6', 'web_search', 'Recherche sur le web', { detail: 'TTL DNS bascule Route53' },
    { address: 'TTL DNS bascule Route53', web: 'search' }),
  page: sceneOf('S7', 'page_publish', 'Page publiée', {}, { creation: 'page', title: 'Runbook de bascule DNS' }),
  montage: sceneOf('S7', 'video-montage', 'Salle de montage', {}, { creation: 'video', title: 'Cours bascule',
    checks: 3 }),
  video: sceneOf('S5b', 'video-watch', 'Regarder une vidéo', {}, { film: 'demo-bascule.mp4',
    extract: 'Extraction des plans · 12 images' }),
  passation: sceneOf('S10', 'ouvrir_sujet', 'Passation vers un sujet', { detail: 'Doc archi' },
    { fromLabel: 'Migration Forge', toLabel: 'Doc archi', toKey: 's-doc' }),
  souvenir: sceneOf('S12', 'recall', 'Souvenir rappelé', { detail: 'bascule DNS' }, { repere: 'Tour 34' }),
  carte: sceneOf('S12', 'carte_chercher', 'Carte du savoir', { detail: 'ingress forge' }),
  essaim: sceneOf('S13', 'explore', '4 explorations déléguées', { count: 4 }, { satellites: [
    'Où est défini le TTL ?', 'Qui consomme la zone ?', 'Quels ingress pointent ici ?', 'Le runbook existe ?'] }),
  veille: sceneOf('S17', 'veille_poser', 'Veille posée', { detail: 'Jira FORGE · lun → ven 9 h' }),
};

/** SF-236-13 — une ambiance de test : rien. */
export const QUIET: Ambient = { hostDown: null, liveSubjects: new Set(), calling: null, cost: null, alert: null };

/** SF-236-13 — les ambiances du banc (`ambiance-<nom>`). */
export const BANC_AMBIENTS: Readonly<Record<string, Ambient>> = {
  'poste-muet': { ...QUIET, hostDown: { clientKey: 'h-cagip', name: 'CAGIP' } },
  vivants: { ...QUIET, liveSubjects: new Set(['s-forge', 's-doc', 's-web']) },
  cloche: { ...QUIET, calling: { subjectId: 's-doc', label: 'Doc archi' } },
  cout: { ...QUIET, cost: '0,42 €' },
  veille: { ...QUIET, alert: { clientKey: 'h-cagip', label: 'Veille Jira · CAGIP — 2 changements' } },
  // SF-236-25 — le radar : trois alertes réelles, trois échos (veille et poste muet en rouge, cloche en attente).
  radar: { ...QUIET, hostDown: { clientKey: 'h-eden', name: 'Edenred' }, calling: { subjectId: 's-doc', label: 'Doc archi' },
    alert: { clientKey: 'h-cagip', label: 'Veille Jira · CAGIP — 2 changements' } },
};

/** SF-236-09 — une session de test : des fichiers lus et écrits dans trois dossiers (nuage du dépôt). */
export const BANC_SESSION_FILES: readonly { path: string; write: boolean }[] = [
  { path: 'src/app/dns/zone.ts', write: false }, { path: 'src/app/dns/ttl.ts', write: false },
  { path: 'src/app/dns/bascule.ts', write: true }, { path: 'docs/plan.md', write: false },
  { path: 'docs/runbook.md', write: false }, { path: 'k8s/ingress.yaml', write: false },
  { path: 'k8s/forge-api.yaml', write: true }, { path: 'src/app/dns/records.ts', write: false },
];
