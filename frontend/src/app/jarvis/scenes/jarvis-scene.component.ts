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
          @switch (s.id) {
            @case ('S3') {
              <!-- SF-234-05 — S3 : la commande en filigrane, ses 2 dernières lignes (masquées, D11). -->
              <div class="term">
                @if (s.detail) { <p class="term__cmd"><span aria-hidden="true">$ </span>{{ s.detail }}</p> }
                @for (line of s.data?.lines ?? []; track $index) { <p class="term__line">{{ line }}</p> }
              </div>
            }
            @case ('S4') {
              <!-- SF-234-05 — S4 : les tickets en éventail, avec leurs clés. -->
              @if (s.data?.tickets?.length) {
                <div class="tickets">
                  @for (key of s.data!.tickets!; track key; let i = $index) {
                    <span class="ticket" [style.--i]="i">{{ key }}</span>
                  }
                </div>
              } @else if (s.detail) {
                <p class="scene__detail">{{ s.detail }}</p>
              }
            }
            @case ('S5') {
              <!-- SF-234-05 — S5 : les feuillets s'empilent ; la loupe pour une recherche. -->
              @if (s.data?.mode === 'search') {
                <p class="lens"><span class="lens__glass" aria-hidden="true"></span>{{ s.data?.pattern ?? s.detail }}</p>
              } @else if (s.data?.files?.length) {
                <div class="sheets" [attr.data-mode]="s.data?.mode">
                  @for (file of s.data!.files!; track file; let last = $last) {
                    <span class="sheet" [class.is-last]="last">{{ file }}</span>
                  }
                </div>
              } @else if (s.detail) {
                <p class="scene__detail">{{ s.detail }}</p>
              }
            }
            @default {
              @if (s.detail) {
                <p class="scene__detail">{{ s.detail }}</p>
              }
            }
          }
        </div>
      </div>
    } @else {
      @if (focus(); as f) {
      <!-- SF-234-13 — « Montrer » : la liste écrite de ce que la constellation met au premier plan. -->
      <div class="scene scene--show" aria-hidden="true" data-scene="S20">
        <div class="scene__body">
          <p class="scene__title">{{ f.title }}</p>
          <ul class="show">
            @for (item of f.items; track item.node.key) {
              <li class="show__item">
                <span class="show__dot" [class.is-on]="item.connected === true"
                      [class.is-off]="item.connected === false" [attr.data-kind]="item.node.kind"></span>
                <span class="show__name">{{ item.node.label }}</span>
                @if (item.note) { <span class="show__note">· {{ item.note }}</span> }
              </li>
            }
            @if (f.more > 0) { <li class="show__item show__more">et {{ f.more }} de plus</li> }
          </ul>
        </div>
      </div>
      }
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
    .term { margin-top: 4px; font-family: var(--cg-font-mono); font-size: 12px; overflow: hidden; }
    .term__cmd, .term__line { margin: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .term__cmd { color: color-mix(in srgb, var(--cg-cream) 85%, transparent); }
    .term__line { color: color-mix(in srgb, var(--cg-cream) 60%, transparent); animation: term-up 200ms ease-out; }
    .tickets { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 6px; }
    .ticket {
      font-family: var(--cg-font-mono); font-size: 12px; font-weight: 600; color: var(--cg-cream);
      padding: 4px 8px; border-radius: 6px; border: 1px solid color-mix(in srgb, var(--cg-cream) 30%, transparent);
      background: var(--cg-navy); transform: rotate(calc((var(--i, 0) - 1) * 2deg));
      animation: scene-in 240ms ease-out backwards; animation-delay: calc(var(--i, 0) * 60ms);
    }
    .sheets { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
    .sheet {
      font-family: var(--cg-font-mono); font-size: 12px; max-width: 100%; overflow: hidden; text-overflow: ellipsis;
      white-space: nowrap; padding: 2px 6px; border-radius: 3px 8px 3px 3px;
      color: color-mix(in srgb, var(--cg-cream) 70%, transparent);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 18%, transparent);
    }
    .sheet.is-last { color: var(--cg-cream); border-color: var(--cg-orange-2); }
    .sheets[data-mode='write'] .sheet.is-last { border-style: dashed; }
    .lens {
      display: flex; align-items: center; gap: 8px; margin: 6px 0 0; font-family: var(--cg-font-mono); font-size: 12px;
      color: var(--cg-cream); overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
    }
    .lens__glass {
      flex: none; width: 10px; height: 10px; border-radius: 50%; border: 2px solid var(--cg-orange-2); position: relative;
    }
    .lens__glass::after {
      content: ''; position: absolute; width: 2px; height: 6px; right: -4px; bottom: -6px;
      background: var(--cg-orange-2); transform: rotate(-45deg);
    }
    @keyframes term-up { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    .show { list-style: none; margin: 4px 0 0; padding: 0; display: grid; gap: 2px; }
    .show__item {
      display: flex; align-items: baseline; gap: 6px; min-width: 0; font-size: 14px;
      color: color-mix(in srgb, var(--cg-cream) 85%, transparent);
    }
    .show__name { font-weight: 600; color: var(--cg-orange-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .show__note { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .show__dot {
      flex: none; width: 8px; height: 8px; border-radius: 50%; background: var(--cg-accent); align-self: center;
    }
    .show__dot[data-kind='chantier'] { border-radius: 1px; transform: rotate(45deg); }
    .show__dot[data-kind='person'] { border-radius: 2px; }
    .show__dot.is-on { background: var(--cg-terminal-success); }
    .show__dot.is-off { background: transparent; border: 1px dashed color-mix(in srgb, var(--cg-cream) 45%, transparent); }
    .show__more { font-style: italic; }
    @keyframes scene-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: none; } }
    @keyframes scene-pulse {
      0% { box-shadow: 0 0 0 0 color-mix(in srgb, var(--cg-accent) 60%, transparent); }
      100% { box-shadow: 0 0 0 10px transparent; }
    }
    @media (prefers-reduced-motion: reduce) {
      .scene, .scene__pulse, .term__line, .ticket { animation: none; }
      .ticket { transform: none; }
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
  /** SF-234-13 — ce que le PO a demandé à voir (affiché quand aucune action n'est en cours). */
  readonly focus = computed(() => this.bus.focus());
  /** « × n » seulement quand le libellé ne dit pas déjà le nombre (pluriel du registre). */
  readonly showCount = computed(() => {
    const s = this.scene();
    return !!s && s.count > 1 && !s.libelle.includes(String(s.count));
  });
}
