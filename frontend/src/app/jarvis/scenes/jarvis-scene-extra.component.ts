import { Component, computed, inject, input } from '@angular/core';

import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { SceneView } from './jarvis-scene-reader';

/** Les scènes rendues ici (vague C du cadrage F-234). */
export const EXTRA_SCENES: ReadonlySet<string> = new Set(['S6', 'S7', 'S5b', 'S10', 'S12', 'S13', 'S15', 'S17']);

/**
 * **Les scènes d'usage moyen** (F-234 / SF-234-08, SF-234-09) — page web, passation, mémoire, essaim, le point, veille
 * ou routine, atelier de création, regarder une vidéo. Composant à part : la scène principale garde un style sous le
 * budget par composant. Décoratif (`aria-hidden` porté par la carte parente) ; aucune couleur nouvelle.
 */
@Component({
  selector: 'app-jarvis-scene-extra',
  template: `
    @switch (scene().id) {
      @case ('S6') {
        <!-- SF-234-08 — S6 : la barre d'adresse (la requête, ou le domaine seul d'une page lue). -->
        <p class="address" [attr.data-web]="scene().data?.web">
          <span class="address__icon" aria-hidden="true"></span>{{ scene().data?.address ?? scene().detail }}
        </p>
      }
      @case ('S10') {
        <!-- SF-234-08 — S10 : le fil glisse d'un sujet à l'autre. -->
        <p class="handoff">
          @if (scene().data?.fromLabel) { <span class="handoff__from">{{ scene().data?.fromLabel }}</span> }
          <span class="handoff__thread" aria-hidden="true"></span>
          <span class="handoff__to">{{ scene().data?.toLabel ?? scene().detail }}</span>
        </p>
      }
      @case ('S12') {
        <!-- SF-234-08 — S12 : « tour 34 » remonte ; la carte du savoir ; une règle proposée s'inscrit. -->
        @if (scene().data?.repere) { <p class="memory"><span class="memory__badge">{{ scene().data?.repere }}</span></p> }
        @if (scene().detail) { <p class="scene__detail">{{ scene().detail }}</p> }
      }
      @case ('S13') {
        <!-- SF-234-08 — S13 : une orbe satellite par sous-agent, avec sa question. -->
        <ul class="swarm">
          @for (q of scene().data?.satellites ?? []; track $index; let i = $index) {
            <li class="swarm__item" [style.--i]="i"><span class="swarm__orb" aria-hidden="true"></span>{{ q }}</li>
          }
        </ul>
      }
      @case ('S15') {
        <!-- SF-234-08 — S15 : le point — tous les sujets du client s'allument en vague (constellation). -->
        <p class="scene__detail">{{ pointLabel() }}</p>
      }
      @case ('S17') {
        <!-- SF-234-08 — S17 : une horloge s'attache au sujet. -->
        <p class="clockline"><span class="clockline__icon" aria-hidden="true"></span>{{ scene().detail ?? scene().entity?.label }}</p>
      }
      @case ('S7') {
        <!-- SF-234-09 — S7 : l'atelier de création — la page, le diagramme, le document ou la vidéo prend forme. -->
        <div class="craft" [attr.data-creation]="scene().data?.creation">
          <span class="craft__shape" aria-hidden="true"><i></i><i></i><i></i></span>
          <div class="craft__lines">
            @if (scene().data?.title) { <p class="craft__title"><span>{{ scene().data?.title }}</span></p> }
            @if (scene().data?.checks) {
              <p class="craft__progress">
                @for (c of dots(scene().data!.checks!); track $index) { <span class="craft__dot" aria-hidden="true"></span> }
                suivi {{ scene().data?.checks }}
              </p>
            }
          </div>
        </div>
      }
      @case ('S5b') {
        <!-- SF-234-09 — S5b : la pellicule défile, les plans s'extraient. -->
        <div class="reel">
          <span class="reel__strip" aria-hidden="true"></span>
          <div class="craft__lines">
            @if (scene().data?.film) { <p class="reel__film">{{ scene().data?.film }}</p> }
            @if (scene().data?.extract) { <p class="reel__extract">{{ scene().data?.extract }}</p> }
          </div>
        </div>
      }
    }
  `,
  styles: `
    :host { display: block; min-width: 0; }
    .scene__detail {
      margin: 4px 0 0; font-family: var(--cg-font-mono); font-size: 12px;
      color: color-mix(in srgb, var(--cg-cream) 75%, transparent);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    @keyframes scene-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    .address {
      display: flex; align-items: center; gap: 8px; margin: 6px 0 0; padding: 4px 10px; border-radius: 14px;
      font-family: var(--cg-font-mono); font-size: 12px; color: var(--cg-cream); min-width: 0;
      background: var(--cg-navy); border: 1px solid color-mix(in srgb, var(--cg-cream) 25%, transparent);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .address__icon {
      flex: none; width: 10px; height: 10px; border-radius: 50%; border: 1.5px solid var(--cg-orange-2);
    }
    .address[data-web='search'] .address__icon { border-radius: 50% 50% 50% 0; }
    .handoff { display: flex; align-items: center; gap: 6px; margin: 6px 0 0; font-size: 14px; min-width: 0; }
    .handoff__from { color: color-mix(in srgb, var(--cg-cream) 70%, transparent); white-space: nowrap; }
    .handoff__to { color: var(--cg-orange-2); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .handoff__thread {
      flex: 1; min-width: 24px; max-width: 80px; height: 2px; border-radius: 1px;
      background: linear-gradient(90deg, transparent, var(--cg-orange-2));
      animation: thread-slide 700ms ease-out;
    }
    .memory { margin: 6px 0 0; }
    .memory__badge {
      display: inline-block; padding: 2px 10px; border-radius: 10px; font-family: var(--cg-font-mono); font-size: 12px;
      color: var(--cg-navy); background: var(--cg-cream); animation: memory-up 500ms ease-out;
    }
    .swarm { list-style: none; margin: 6px 0 0; padding: 0; display: grid; gap: 3px; }
    .swarm__item {
      display: flex; align-items: center; gap: 6px; font-size: 13px; min-width: 0; white-space: nowrap;
      overflow: hidden; text-overflow: ellipsis; color: color-mix(in srgb, var(--cg-cream) 85%, transparent);
      animation: scene-in 300ms ease-out backwards; animation-delay: calc(var(--i, 0) * 80ms);
    }
    .swarm__orb {
      flex: none; width: 9px; height: 9px; border-radius: 50%;
      background: radial-gradient(circle at 35% 35%, var(--cg-cream), var(--cg-accent));
    }
    .clockline { display: flex; align-items: center; gap: 8px; margin: 6px 0 0; font-size: 14px; color: var(--cg-cream); }
    .clockline__icon {
      position: relative; flex: none; width: 12px; height: 12px; border-radius: 50%; border: 1.5px solid var(--cg-orange-2);
    }
    .clockline__icon::after {
      content: ''; position: absolute; left: 5px; top: 2px; width: 1.5px; height: 4px; background: var(--cg-orange-2);
    }
    @keyframes thread-slide { from { transform: scaleX(0); transform-origin: left; } to { transform: none; } }
    @keyframes memory-up { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }
    .craft, .reel { display: flex; gap: 10px; align-items: center; margin-top: 6px; min-width: 0; }
    .craft__shape {
      position: relative; flex: none; width: 30px; height: 36px; border-radius: 3px; display: grid; align-content: center;
      gap: 4px; padding: 0 5px; border: 1.5px solid color-mix(in srgb, var(--cg-cream) 70%, transparent);
    }
    .craft__shape i {
      display: block; height: 2px; border-radius: 1px; background: color-mix(in srgb, var(--cg-cream) 60%, transparent);
      animation: craft-line 1.4s ease-out infinite; transform-origin: left;
    }
    .craft__shape i:nth-child(2) { animation-delay: 200ms; width: 80%; }
    .craft__shape i:nth-child(3) { animation-delay: 400ms; width: 60%; }
    .craft[data-creation='diagram'] .craft__shape { border-radius: 50%; }
    .craft[data-creation='image'] .craft__shape { border-color: var(--cg-orange-2); }
    .craft[data-creation='video'] .craft__shape, .craft[data-creation='audio'] .craft__shape { width: 40px; height: 28px; }
    .craft[data-creation='pdf'] .craft__shape { border-color: var(--cg-orange-2); border-top-right-radius: 10px; }
    .craft__lines { min-width: 0; }
    .craft__title { margin: 0; font-family: var(--cg-font-heading); font-size: 15px; color: var(--cg-cream); overflow: hidden; }
    .craft__title span {
      display: inline-block; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
      vertical-align: bottom; animation: craft-type 900ms steps(24, end);
    }
    .craft__progress {
      display: flex; align-items: center; gap: 4px; margin: 4px 0 0; font-family: var(--cg-font-mono); font-size: 12px;
      color: color-mix(in srgb, var(--cg-cream) 75%, transparent);
    }
    .craft__dot { width: 6px; height: 6px; border-radius: 50%; background: var(--cg-orange-2); }
    .reel__strip {
      flex: none; width: 46px; height: 30px; border-radius: 3px; border: 1.5px solid color-mix(in srgb, var(--cg-cream) 70%, transparent);
      background: repeating-linear-gradient(90deg, color-mix(in srgb, var(--cg-cream) 55%, transparent) 0 3px, transparent 3px 9px) top / 100% 4px repeat-x,
        repeating-linear-gradient(90deg, color-mix(in srgb, var(--cg-cream) 55%, transparent) 0 3px, transparent 3px 9px) bottom / 100% 4px repeat-x;
      animation: reel-roll 1.2s linear infinite;
    }
    .reel__film { margin: 0; font-family: var(--cg-font-mono); font-size: 13px; color: var(--cg-orange-2); font-weight: 600;
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .reel__extract { margin: 2px 0 0; font-size: 13px; color: color-mix(in srgb, var(--cg-cream) 80%, transparent); }
    @keyframes craft-line { from { transform: scaleX(0); } 60%, to { transform: none; } }
    @keyframes craft-type { from { max-width: 0; } to { max-width: 100%; } }
    @keyframes reel-roll { from { background-position: 0 0, 0 100%; } to { background-position: 9px 0, -9px 100%; } }
    @media (prefers-reduced-motion: reduce) {
      .handoff__thread, .memory__badge, .swarm__item { animation: none; }
      .craft__shape i, .craft__title span, .reel__strip { animation: none; }
    }
  `,
})
export class JarvisSceneExtraComponent {
  private readonly bus = inject(JarvisSceneBus);

  readonly scene = input.required<SceneView>();

  /** SF-234-08 — S15 : « 6 sujets chez CAGIP ». */
  readonly pointLabel = computed(() => {
    const universe = this.bus.universe();
    const subjectId = this.bus.subjectId();
    const client = universe?.clients.find((c) => c.subjects.some((x) => x.id === subjectId)) ?? universe?.clients[0];
    if (!client) {
      return 'Tour d’horizon des sujets';
    }
    const n = client.subjects.filter((x) => !x.hostTerminal).length;
    return `${n} sujet${n > 1 ? 's' : ''} chez ${client.name}`;
  });

  /** SF-234-09 — un point par suivi de rendu (6 au plus). */
  dots(n: number): number[] {
    return Array.from({ length: Math.min(6, Math.max(0, n)) }, (_, i) => i);
  }
}
