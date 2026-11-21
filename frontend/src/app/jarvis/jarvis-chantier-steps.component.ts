import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { HOLO_COLORS } from './stage3d/jarvis-motion';
import { JarvisStep, JarvisWork, isLive } from './jarvis-works';

/** Au-delà, la constellation se lirait mal (la passerelle en rend 12 au plus). */
export const MAX_STARS = 12;

/** La teinte d'une étoile : faite (vert), en cours (or, exécute), bloquée (rouge), à faire (le texte holographique, creux). */
export const STAR_COLORS: Readonly<Record<JarvisStep['state'], string>> = {
  DONE: HOLO_COLORS.green,
  CURRENT: HOLO_COLORS.exec,
  BLOCKED: HOLO_COLORS.alert,
  TODO: HOLO_COLORS.text,
};

/**
 * Le chantier à montrer en constellation (F-271 / SF-271-05) : le travail vivant le plus récent qui porte des étapes,
 * sinon le plus récent fini encore visible (le plan vient d'être posé). `null` s'il n'y en a pas.
 */
export function featuredChantier(works: readonly JarvisWork[]): JarvisWork | null {
  const withSteps = works.filter((w) => (w.steps?.length ?? 0) > 0);
  const live = withSteps.filter((w) => isLive(w));
  const pool = live.length ? live : withSteps;
  return pool.length ? pool[pool.length - 1] : null;
}

/** Une étoile placée : nom court, état, position en % du cœur (centre 50/50). */
export interface Star {
  name: string;
  state: JarvisStep['state'];
  x: number;
  y: number;
}

/**
 * Les étoiles sur un arc sous le cœur, de gauche à droite (l'ordre du plan se lit comme une phrase… sans phrase).
 * L'arc va de 160° à 20° (sens trigonométrique, 0° à droite) sur un rayon de 78 % du cœur.
 */
export function starsOf(steps: readonly JarvisStep[]): Star[] {
  const list = steps.slice(0, MAX_STARS);
  const n = list.length;
  const from = 160;
  const to = 20;
  return list.map((s, i) => {
    const angle = ((n === 1 ? 90 : from + ((to - from) * i) / (n - 1)) * Math.PI) / 180;
    return { name: s.name, state: s.state, x: 50 + 78 * Math.cos(angle), y: 50 + 78 * Math.sin(angle) };
  });
}

/**
 * **Le chantier en constellation** (F-271 / SF-271-05, cadrage S3-S5) — les étapes du chantier en cours, comme des étoiles
 * sous le cœur : un nom court chacune (« SNS », « IAM », « MR »), faites en vert, l'étape en cours en or (exécute),
 * bloquée en rouge, à faire pâles, reliées d'un trait fin. Aucune phrase, aucun clic ; décoratif (l'état se dit à la voix).
 */
@Component({
  selector: 'app-jarvis-chantier-steps',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (stars().length > 0) {
      <div class="cst" aria-hidden="true">
        <svg class="cst__links" viewBox="-40 -40 180 180" preserveAspectRatio="none">
          <polyline [attr.points]="links()" />
        </svg>
        @for (s of stars(); track $index) {
          <div [class]="'cst__star cst__star--' + s.state.toLowerCase()" [style.left.%]="s.x" [style.top.%]="s.y"
               [style.--cst-color]="color(s.state)">
            <span class="cst__dot"></span>
            <span class="cst__name">{{ s.name }}</span>
          </div>
        }
      </div>
      <span class="cst__sr" role="status">{{ summary() }}</span>
    }
  `,
  styleUrl: './jarvis-chantier-steps.component.scss',
})
export class JarvisChantierStepsComponent {
  /** Le travail dont le chantier est montré, ou `null`. */
  readonly work = input<JarvisWork | null>(null);

  readonly stars = computed(() => starsOf(this.work()?.steps ?? []));

  /** Le trait qui relie les étoiles, dans le repère du cœur (0-100, débord compris par la boîte). */
  readonly links = computed(() => this.stars().map((s) => `${s.x.toFixed(1)},${s.y.toFixed(1)}`).join(' '));

  readonly summary = computed(() => {
    const steps = this.work()?.steps ?? [];
    const done = steps.filter((s) => s.state === 'DONE').length;
    return steps.length ? `${this.work()?.name ?? ''} : ${done} sur ${steps.length}` : '';
  });

  color(state: JarvisStep['state']): string {
    return STAR_COLORS[state];
  }
}
