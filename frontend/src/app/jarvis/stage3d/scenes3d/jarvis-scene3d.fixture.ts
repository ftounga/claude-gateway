import { SceneData, SceneView } from '../../scenes/jarvis-scene-reader';
import { NodeRef } from '../../scenes/jarvis-universe.models';

/**
 * F-236 — **scènes F-234 de test** (univers de test : CAGIP, sujet « Migration Forge », chantier 3 « Bascule DNS »,
 * Zahi attendu ; Edenred déconnecté). Partagées par les tests des scènes 3D et par le banc de captures.
 */

export const FORGE: NodeRef = { kind: 'subject', key: 's-forge', label: 'Migration Forge', clientKey: 'h-cagip',
  subjectId: 's-forge' };

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
};

/** SF-236-09 — une session de test : des fichiers lus et écrits dans trois dossiers (nuage du dépôt). */
export const BANC_SESSION_FILES: readonly { path: string; write: boolean }[] = [
  { path: 'src/app/dns/zone.ts', write: false }, { path: 'src/app/dns/ttl.ts', write: false },
  { path: 'src/app/dns/bascule.ts', write: true }, { path: 'docs/plan.md', write: false },
  { path: 'docs/runbook.md', write: false }, { path: 'k8s/ingress.yaml', write: false },
  { path: 'k8s/forge-api.yaml', write: true }, { path: 'src/app/dns/records.ts', write: false },
];
