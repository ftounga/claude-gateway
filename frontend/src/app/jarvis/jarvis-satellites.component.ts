import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { HOLO_COLORS } from './stage3d/jarvis-motion';
import { JarvisWork } from './jarvis-works';

/** La phase d'un travail, telle que l'écran la montre (F-270 / SF-270-05, D6). */
export type SatellitePhase = JarvisWork['phase'];

/** Un satellite : un travail en fond, un nom court, une teinte de phase. Jamais une phrase. */
export interface Satellite {
  id: string;
  name: string;
  phase: SatellitePhase;
}

/** Au-delà, les satellites se resserrent (D8 : 8 délégations + 4 courses). */
export const MAX_SATELLITES = 12;
/** La durée d'un tour d'orbite (s). */
export const ORBIT_S = 60;
/** Sur téléphone, les plus récents seulement. */
export const MAX_COMPACT = 6;

/** La teinte de chaque phase : réfléchit (violet), exécute (or), attend votre oui (cyan), fini (vert), échec (rouge). */
export const PHASE_COLORS: Readonly<Record<SatellitePhase, string>> = {
  THINKING: HOLO_COLORS.deep,
  EXECUTING: HOLO_COLORS.exec,
  WAITING_YES: HOLO_COLORS.wait,
  QUEUED: HOLO_COLORS.deep,
  DONE: HOLO_COLORS.green,
  FAILED: HOLO_COLORS.alert,
  CANCELLED: HOLO_COLORS.normalDim,
};

/** L'ordre « le plus fort d'abord » : exécute > attend un oui > réfléchit > en attente > fini. */
const STRENGTH: Readonly<Record<SatellitePhase, number>> = {
  EXECUTING: 6, WAITING_YES: 5, THINKING: 4, QUEUED: 3, FAILED: 2, DONE: 1, CANCELLED: 0,
};

/** La phase la plus forte en cours (le halo du cœur, F-271 D8), ou `null` si rien ne tourne. */
export function strongestPhase(list: readonly Satellite[]): SatellitePhase | null {
  const live = list.filter((s) => s.phase !== 'DONE' && s.phase !== 'FAILED' && s.phase !== 'CANCELLED');
  return live.length === 0 ? null : live.reduce((a, b) => (STRENGTH[b.phase] > STRENGTH[a.phase] ? b : a)).phase;
}

/**
 * Les satellites à montrer : les travaux lus à la passerelle (tous pods, courses comprises) complétés par les travaux
 * que l'écran vient de lancer et que la passerelle ne connaît pas encore (réflexion), sans doublon, au plus
 * {@link MAX_SATELLITES}, du plus ancien au plus récent.
 */
export function satellitesOf(works: readonly JarvisWork[],
                             local: readonly { id: string; name: string; label?: string | null }[]): Satellite[] {
  const known = new Set(works.map((w) => w.id));
  const list = [
    ...works.map((w) => ({ id: w.id, name: w.name, label: w.label, phase: w.phase })),
    ...local.filter((l) => !known.has(l.id))
      .map((l) => ({ id: l.id, name: l.name, label: l.label ?? '', phase: 'THINKING' as const })),
  ].slice(-MAX_SATELLITES);
  // Deux travaux du même sujet portent le même nom : chacun prend alors les premiers mots de son libellé.
  const count = new Map<string, number>();
  list.forEach((s) => count.set(s.name, (count.get(s.name) ?? 0) + 1));
  return list.map((s) => ({ id: s.id, phase: s.phase,
    name: (count.get(s.name) ?? 0) > 1 ? labelName(s.label) || s.name : s.name }));
}

const SMALL_WORDS = new Set(['le', 'la', 'les', 'l', 'de', 'du', 'des', 'd', 'un', 'une', 'et', 'sur', 'pour', 'au', 'aux',
  'en', 'à', 'a']);

/** Deux mots qui distinguent un travail (« pipeline dev », « tickets backlog »), jamais une phrase. */
export function labelName(label: string | null | undefined): string {
  const words = (label ?? '').replace(/[.!?…:,;]+/g, ' ').split(/\s+/).filter((w) => w && !SMALL_WORDS.has(w.toLowerCase()));
  const name = words.slice(0, 2).join(' ');
  return name.length <= 24 ? name : `${name.slice(0, 23)}…`;
}

/**
 * **Le fond se voit** (F-270 / SF-270-05, D6) — chaque travail en fond est un satellite lumineux en orbite autour du
 * cœur, étiqueté d'un **nom court** (clé de ticket, « chantier 4 », sujet) et teinté par sa phase ; le cœur ne montre
 * que l'échange en cours. Aucun clic, aucune phrase. Décoratif : l'état est dit à la voix (« qu'est-ce qui tourne ? »)
 * et résumé pour les lecteurs d'écran.
 */
@Component({
  selector: 'app-jarvis-satellites',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (items().length > 0) {
      <div class="sat" [class.sat--compact]="compact()" aria-hidden="true">
          @for (s of items(); track s.id; let i = $index) {
            <div [class]="'sat__body sat__body--' + s.phase.toLowerCase()" [style.--sat-color]="color(s.phase)"
                 [style.animation-delay]="delay(i)">
              <span class="sat__pearl"></span>
              <span class="sat__name">{{ s.name }}</span>
            </div>
          }
      </div>
      <span class="sat__sr" role="status">{{ summary() }}</span>
    }
  `,
  styleUrl: './jarvis-satellites.component.scss',
})
export class JarvisSatellitesComponent {
  readonly satellites = input<Satellite[]>([], { alias: 'items' });
  /** Téléphone, repli léger : perles et noms plus petits, au plus {@link MAX_COMPACT}. */
  readonly compact = input(false);

  readonly items = computed(() => (this.compact() ? this.satellites().slice(-MAX_COMPACT) : this.satellites()));

  readonly summary = computed(() => this.items().map((s) => `${s.name} : ${PHASE_WORDS[s.phase]}`).join(', '));

  /** Le décalage sur l'orbite : les satellites se répartissent régulièrement (départ en haut). */
  delay(i: number): string {
    const n = Math.max(1, this.items().length);
    return `${-(ORBIT_S * (0.75 + i / n)).toFixed(2)}s`;
  }

  color(phase: SatellitePhase): string {
    return PHASE_COLORS[phase];
  }
}

/** Les mots du résumé accessible (un mot par phase). */
const PHASE_WORDS: Readonly<Record<SatellitePhase, string>> = {
  THINKING: 'réfléchit', EXECUTING: 'exécute', WAITING_YES: 'attend votre oui', QUEUED: 'en attente', DONE: 'fini',
  FAILED: 'échec', CANCELLED: 'arrêté',
};
