import { Component, OnInit, computed, inject } from '@angular/core';

import { JarvisSceneBus } from './jarvis-scene-bus.service';

/**
 * **La scène en cours** (F-234 / SF-234-02, D3, D14) — une seule à la fois. Aujourd'hui toutes les actions passent par
 * la **scène générique** (S-GEN) : nom lisible de l'action (registre, sinon nom humanisé), rafale regroupée (« × 3 »),
 * entité en surbrillance (la cible reconnue, sinon le sujet courant), détail d'une ligne masqué. L'autorisation (S2)
 * se distingue par son liseré orange et reste devant tant qu'elle attend.
 *
 * <p>Décorative pour un lecteur d'écran (`aria-hidden`) : l'équivalent accessible est le fil d'Ariane et l'état dit
 * de Jarvis (SF-234-03). Aucune couleur nouvelle (navy, crème, orange du design system).</p>
 */
@Component({
  selector: 'app-jarvis-scene',
  template: `
    @if (scene(); as s) {
      <div class="scene" [class.scene--pinned]="s.pinned" aria-hidden="true" [attr.data-scene]="s.id">
        <span class="scene__pulse"></span>
        <div class="scene__body">
          <p class="scene__title">
            {{ s.libelle }}
            @if (showCount()) { <span class="scene__count">× {{ s.count }}</span> }
          </p>
          @if (s.entity) {
            <p class="scene__entity"><span class="scene__dot"></span>{{ s.entity.label }}</p>
          }
          @if (s.detail) {
            <p class="scene__detail">{{ s.detail }}</p>
          }
        </div>
      </div>
    }
  `,
  styles: `
    :host { display: block; width: 100%; min-height: 0; }
    .scene {
      display: flex; gap: var(--cg-space-2); align-items: flex-start;
      padding: var(--cg-space-2) var(--cg-space-3);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent);
      border-radius: 12px;
      background: color-mix(in srgb, var(--cg-navy-2) 70%, transparent);
      animation: scene-in 240ms ease-out;
    }
    .scene--pinned { border-color: var(--cg-accent); }
    .scene__pulse {
      flex: none; width: 10px; height: 10px; margin-top: 6px; border-radius: 50%;
      background: var(--cg-accent); animation: scene-pulse 1.2s ease-out infinite;
    }
    .scene__body { min-width: 0; flex: 1; }
    .scene__title {
      margin: 0; font-family: var(--cg-font-heading); font-size: 15px; font-weight: 600; color: var(--cg-cream);
    }
    .scene__count { margin-left: var(--cg-space-1); color: var(--cg-orange-2); font-weight: 600; }
    .scene__entity {
      display: flex; align-items: center; gap: 6px; margin: 4px 0 0;
      font-size: 14px; color: var(--cg-orange-2); font-weight: 600;
    }
    .scene__dot { width: 6px; height: 6px; border-radius: 50%; background: var(--cg-orange-2); }
    .scene__detail {
      margin: 4px 0 0; font-family: var(--cg-font-mono); font-size: 12px;
      color: color-mix(in srgb, var(--cg-cream) 75%, transparent);
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    @keyframes scene-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    @keyframes scene-pulse {
      0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--cg-accent) 60%, transparent); }
      100% { box-shadow: 0 0 0 10px transparent; }
    }
    @media (prefers-reduced-motion: reduce) {
      .scene, .scene__pulse { animation: none; }
    }
  `,
})
export class JarvisSceneComponent implements OnInit {
  private readonly bus = inject(JarvisSceneBus);

  /** L'univers est lu à l'ouverture de l'écran (les noms se reconnaissent sur lui). */
  ngOnInit(): void {
    this.bus.load();
  }

  readonly scene = computed(() => this.bus.state().scene);
  /** « × n » seulement quand le libellé ne dit pas déjà le nombre (pluriel du registre). */
  readonly showCount = computed(() => {
    const s = this.scene();
    return !!s && s.count > 1 && !s.libelle.includes(String(s.count));
  });
}
