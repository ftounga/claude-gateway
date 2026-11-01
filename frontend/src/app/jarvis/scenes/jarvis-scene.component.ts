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
/** Le chantier d'une scène, tel que dessiné. */
export interface SceneChantier {
  number: number;
  title: string;
  phaseLabel: string | null;
  done: number;
  total: number;
}

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
            @case ('S9') {
              <!-- SF-234-06 — S9 : le losange du chantier, sa phase, l'étape qui se coche, done/total. -->
              @if (chantierOf(s.data?.chantierKey); as c) {
                <p class="chantier">
                  <span class="chantier__diamond" aria-hidden="true"></span>
                  <span class="chantier__name">Ch. {{ c.number }} — {{ c.title }}</span>
                  @if (c.phaseLabel) { <span class="chantier__phase">{{ c.phaseLabel }}</span> }
                </p>
                <div class="progress" [attr.data-done]="c.done" [attr.data-total]="c.total">
                  <span class="progress__bar"><span class="progress__fill" [style.width.%]="c.total ? (100 * c.done) / c.total : 0"></span></span>
                  <span class="progress__count">{{ c.done }}/{{ c.total }}</span>
                </div>
              }
              @if (s.data?.step || s.data?.status) {
                <p class="stepline" [class.is-done]="s.data?.status === 'faite' || s.data?.status === 'vérifiée'">
                  <span class="stepline__tick" aria-hidden="true"></span>
                  @if (s.data?.step) { Étape {{ s.data?.step }} }
                  @if (s.data?.status) { <span class="stepline__status">{{ s.data?.status }}</span> }
                </p>
              } @else if (s.detail && !chantierOf(s.data?.chantierKey)) {
                <p class="scene__detail">{{ s.detail }}</p>
              }
            }
            @case ('S11') {
              <!-- SF-234-06 — S11 : la personne s'attache au chantier (trait pointillé) ou s'en détache. -->
              <p class="attente" [class.is-off]="s.data?.attached === false">
                <span class="attente__person" aria-hidden="true"></span>
                <span class="attente__name">{{ s.data?.person ?? 'Quelqu’un' }}</span>
                @if (s.data?.category) { <span class="attente__cat">· {{ s.data?.category }}</span> }
                <span class="attente__link" aria-hidden="true"></span>
                @if (chantierOf(s.data?.chantierKey); as c) {
                  <span class="attente__chantier">Ch. {{ c.number }}</span>
                }
              </p>
            }
            @case ('S2') {
              <!-- SF-234-06 — S2 : le « oui » attendu, compte à rebours ; la seule scène qui demande. -->
              @if (s.detail) { <p class="ask__detail">{{ s.detail }}</p> }
              <p class="ask__hint">
                {{ s.data?.question ? 'Répondez à l’écran du terminal.' : 'Dites « oui » — sinon je ne fais rien.' }}
                @if (s.data?.remaining !== undefined) {
                  <span class="ask__clock">{{ clock(s.data!.remaining!) }}</span>
                }
              </p>
            }
            @case ('S14') {
              <!-- SF-234-06 — S14 : une puce par point de route, pleine quand il est traité. -->
              <ul class="route">
                @for (q of s.data?.questions ?? []; track $index) {
                  <li class="route__item" [attr.data-state]="q.state">
                    <span class="route__dot" aria-hidden="true"></span>{{ q.libelle }}
                  </li>
                }
              </ul>
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
    .chantier { display: flex; align-items: center; gap: 6px; margin: 4px 0 0; min-width: 0; font-size: 14px; }
    .chantier__diamond {
      flex: none; width: 9px; height: 9px; background: var(--cg-accent); transform: rotate(45deg);
    }
    .chantier__name {
      color: var(--cg-orange-2); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .chantier__phase {
      flex: none; font-size: 12px; padding: 1px 6px; border-radius: 8px; color: var(--cg-cream);
      border: 1px solid color-mix(in srgb, var(--cg-cream) 30%, transparent);
    }
    .progress { display: flex; align-items: center; gap: 8px; margin-top: 4px; }
    .progress__bar {
      flex: 1; height: 4px; border-radius: 2px; overflow: hidden;
      background: color-mix(in srgb, var(--cg-cream) 15%, transparent);
    }
    .progress__fill { display: block; height: 100%; background: var(--cg-terminal-success); transition: width 400ms ease; }
    .progress__count { font-family: var(--cg-font-mono); font-size: 12px; color: var(--cg-cream); }
    .stepline {
      display: flex; align-items: center; gap: 6px; margin: 4px 0 0; font-size: 14px;
      color: color-mix(in srgb, var(--cg-cream) 85%, transparent);
    }
    .stepline__tick {
      flex: none; width: 12px; height: 12px; border-radius: 50%;
      border: 1.5px solid color-mix(in srgb, var(--cg-cream) 50%, transparent);
    }
    .stepline.is-done .stepline__tick {
      border-color: var(--cg-terminal-success); background: var(--cg-terminal-success);
      animation: scene-in 300ms ease-out;
    }
    .stepline__status { font-weight: 600; }
    .stepline.is-done .stepline__status { color: var(--cg-terminal-success); }
    .attente { display: flex; align-items: center; gap: 6px; margin: 4px 0 0; font-size: 14px; flex-wrap: wrap; }
    .attente__person {
      flex: none; width: 10px; height: 10px; border-radius: 3px; background: var(--cg-accent);
    }
    .attente__name { color: var(--cg-orange-2); font-weight: 600; }
    .attente__cat { color: color-mix(in srgb, var(--cg-cream) 80%, transparent); }
    .attente__link {
      flex: none; width: 24px; border-top: 1.5px dashed color-mix(in srgb, var(--cg-cream) 50%, transparent);
    }
    .attente__chantier { font-family: var(--cg-font-mono); font-size: 12px; color: var(--cg-cream); }
    .attente.is-off { opacity: 0.6; }
    .attente.is-off .attente__link { width: 10px; }
    .attente.is-off .attente__name { text-decoration: line-through; }
    .ask__detail { margin: 4px 0 0; font-size: 14px; color: var(--cg-cream); }
    .ask__hint {
      display: flex; justify-content: space-between; gap: 8px; margin: 4px 0 0; font-size: 13px;
      color: color-mix(in srgb, var(--cg-cream) 80%, transparent);
    }
    .ask__clock { font-family: var(--cg-font-mono); color: var(--cg-orange-2); font-weight: 600; }
    .route { list-style: none; margin: 4px 0 0; padding: 0; display: grid; gap: 2px; }
    .route__item {
      display: flex; align-items: center; gap: 6px; font-size: 13px; min-width: 0;
      color: color-mix(in srgb, var(--cg-cream) 85%, transparent);
      white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .route__dot {
      flex: none; width: 9px; height: 9px; border-radius: 50%;
      border: 1.5px solid color-mix(in srgb, var(--cg-cream) 55%, transparent);
    }
    .route__item[data-state='EN_COURS'] .route__dot {
      border-color: var(--cg-orange-2); background: linear-gradient(90deg, var(--cg-orange-2) 50%, transparent 50%);
    }
    .route__item[data-state='TERMINEE'] .route__dot { border-color: var(--cg-terminal-success); background: var(--cg-terminal-success); }
    .route__item[data-state='NON_ABOUTIE'] { text-decoration: line-through; opacity: 0.7; }
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
      .progress__fill { transition: none; }
      .stepline.is-done .stepline__tick { animation: none; }
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

  /**
   * SF-234-06 — le chantier d'une scène, lu sur l'univers (titre, phase, `done/total` + les étapes faites pendant le
   * tour) ; sans clé, le chantier au focus du sujet courant.
   */
  chantierOf(key: string | null | undefined): SceneChantier | null {
    const universe = this.bus.universe();
    const subjectId = key ? key.split('#')[0] : this.bus.subjectId();
    const number = key ? Number(key.split('#')[1]) : null;
    for (const client of universe?.clients ?? []) {
      const subject = client.subjects.find((x) => x.id === subjectId);
      if (!subject) {
        continue;
      }
      const chantier = number !== null
        ? subject.chantiers.find((c) => c.number === number)
        : subject.chantiers.find((c) => c.focus) ?? (subject.chantiers.length === 1 ? subject.chantiers[0] : undefined);
      if (!chantier) {
        return null;
      }
      const added = this.bus.state().stepsDone[`${subject.id}#${chantier.number}`] ?? 0;
      return {
        number: chantier.number, title: chantier.title, phaseLabel: chantier.phaseLabel,
        done: Math.min(chantier.total, chantier.done + added), total: chantier.total,
      };
    }
    return null;
  }

  /** « 0:42 ». */
  clock(seconds: number): string {
    const s = Math.max(0, Math.floor(seconds));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  }
  /** SF-234-13 — ce que le PO a demandé à voir (affiché quand aucune action n'est en cours). */
  readonly focus = computed(() => this.bus.focus());
  /** « × n » seulement quand le libellé ne dit pas déjà le nombre (pluriel du registre). */
  readonly showCount = computed(() => {
    const s = this.scene();
    return !!s && s.count > 1 && !s.libelle.includes(String(s.count));
  });
}
