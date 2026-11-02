import { SceneView, baseName } from '../../scenes/jarvis-scene-reader';
import { stableHash } from '../jarvis-layout-3d';

/**
 * F-236 / SF-236-09 — **la mémoire de session de l'écran** (cadrage S4 « mémoire de session des tickets », N9 « carte
 * du dépôt en nuage »). Fichier <b>pur</b>, tenu par la scène 3D le temps de l'ouverture de `/jarvis` : rien n'est
 * écrit ailleurs, rien ne sort de l'appareil, <b>aucun appel réseau</b>. Elle ne retient que des <b>noms</b> déjà
 * affichés par le lecteur F-234 (clés de tickets, noms de fichiers), jamais un contenu.
 */

/** Un ticket s'estompe après 30 min sans mention (cadrage §8.1). */
export const TICKET_TTL_MS = 30 * 60_000;
/** Tickets retenus au plus (les plus récents). */
export const MAX_SESSION_TICKETS = 24;
/** Points du nuage du dépôt au plus. */
export const MAX_CLOUD_POINTS = 64;

export interface SessionTicket {
  key: string;
  source: 'Jira' | 'Confluence';
  /** Le poste (client) d'où le ticket a été consulté. */
  clientKey: string | null;
  seenAt: number;
}

export interface SessionFile {
  /** Le dossier (sans le nom), `''` à la racine. */
  dir: string;
  name: string;
  subjectId: string | null;
  /** Écrit (feuillet blanc) plutôt que lu. */
  write: boolean;
  seenAt: number;
}

export interface SessionSnapshot {
  tickets: readonly SessionTicket[];
  files: readonly SessionFile[];
}

export const EMPTY_SESSION: SessionSnapshot = { tickets: [], files: [] };

export class SessionMemory {
  private readonly tickets = new Map<string, SessionTicket>();
  private readonly files = new Map<string, SessionFile>();
  private lastScene: SceneView | null = null;

  /**
   * Une scène publiée par le lecteur : S4 retient ses tickets, S5 ses fichiers. Rend vrai si la mémoire a changé.
   * `clientKey` / `subjectId` : le poste et le sujet courants.
   */
  see(scene: SceneView | null, clientKey: string | null, subjectId: string | null, now: number): boolean {
    if (!scene || scene === this.lastScene) {
      return false;
    }
    this.lastScene = scene;
    let changed = false;
    if (scene.id === 'S4') {
      for (const key of scene.data?.tickets ?? []) {
        this.tickets.set(key, { key, source: scene.data?.source ?? 'Jira', clientKey, seenAt: now });
        changed = true;
      }
      trim(this.tickets, MAX_SESSION_TICKETS);
    }
    if (scene.id === 'S5' && scene.data?.mode !== 'search' && scene.data?.mode !== 'list') {
      const write = scene.data?.mode === 'write';
      const path = scene.detail ?? '';
      for (const name of scene.data?.files ?? []) {
        const dir = dirOf(path, name);
        const key = `${subjectId ?? ''}|${dir}/${name}`;
        const before = this.files.get(key);
        this.files.set(key, { dir, name, subjectId, write: write || (before?.write ?? false), seenAt: now });
        changed = true;
      }
      trim(this.files, MAX_CLOUD_POINTS);
    }
    return changed;
  }

  /** Ce que l'écran dessine à `now` : les tickets vus depuis moins de 30 min, les fichiers de la session. */
  snapshot(now: number): SessionSnapshot {
    return {
      tickets: [...this.tickets.values()].filter((t) => now - t.seenAt <= TICKET_TTL_MS)
        .sort((a, b) => a.seenAt - b.seenAt),
      files: [...this.files.values()].sort((a, b) => a.seenAt - b.seenAt),
    };
  }
}

/** Le dossier d'un fichier, lu dans le détail de la scène (le chemin, déjà masqué et tronqué par le lecteur). */
export function dirOf(path: string, name: string): string {
  const flat = (path ?? '').trim().replace(/\\/g, '/');
  if (!flat || baseName(flat) !== name) {
    return '';
  }
  const cut = flat.lastIndexOf('/');
  return cut > 0 ? flat.slice(0, cut).replace(/^\.\//, '') : '';
}

/** La place d'un dossier dans le nuage : un angle et une hauteur stables (hachage), mêmes dossiers ⇒ même amas. */
export function folderSpot(dir: string): { angle: number; height: number } {
  return { angle: stableHash(`dir:${dir}`) * Math.PI * 2, height: (stableHash(`dir~${dir}`) - 0.5) * 0.8 };
}

function trim<V extends { seenAt: number }>(map: Map<string, V>, max: number): void {
  if (map.size <= max) {
    return;
  }
  const oldest = [...map.entries()].sort((a, b) => a[1].seenAt - b[1].seenAt).slice(0, map.size - max);
  for (const [key] of oldest) {
    map.delete(key);
  }
}
