import { ChangeDetectionStrategy, Component, afterNextRender, inject, viewChild } from '@angular/core';

import { BANC_ACTIVITY, bancPlan, bancScreen } from './jarvis-banc-scenes';
import { JARVIS_BANC, JarvisStage3dComponent } from './jarvis-stage-3d.component';
import { MOTION_DURATIONS, ease, holoCssVars } from './jarvis-motion';
import { JarvisCockpitComponent } from './hud/jarvis-cockpit.component';
import { JarvisReplayComponent } from './hud/jarvis-replay.component';
import { JarvisNightComponent } from './hud/jarvis-night.component';
import { VeilleService } from '../../core/services/veille.service';
import { JarvisWatch, JarvisWatchSummary } from '../../core/models/veille.models';
import { trackOf } from './hud/jarvis-replay';
import { testUniverse } from '../scenes/jarvis-universe.fixture';
import { OPEN_FOLDS, writeFolds } from './hud/jarvis-cockpit-layout';
import { ShowFilter } from '../scenes/jarvis-show';
import { Observable, of } from 'rxjs';
import { AtelierMessage } from '../../core/models/atelier.models';
import { JARVIS_ACTIVITY, JARVIS_DAY_HISTORY, JARVIS_WATCHES } from './hud/jarvis-cockpit.component';
import { JarvisPieceGalleryComponent } from '../pieces/jarvis-piece-gallery.component';
import { JarvisPieceClient } from '../pieces/jarvis-piece-client';
import { JarvisPieceStore } from '../pieces/jarvis-piece-store';
import { BANC_PIECE_CLIENT, stageBancPieces } from '../pieces/jarvis-piece-banc';
import { JarvisCardDetailComponent } from '../cards/jarvis-card-detail.component';
import { bancCardDetail, stageBancCards } from '../cards/jarvis-card-banc';

/**
 * F-236 / SF-236-01 — **la page du banc de captures** (`/jarvis-banc?banc=<scène>&t=<ms>`), <b>développement
 * seulement</b> (la route n'est appariée que si `isDevMode()`). Elle pose la scène 3D seule, sans session ni appel
 * réseau, pour que `frontend/scripts/jarvis-3d-captures.sh` produise des captures reproductibles.
 *
 * <p>SF-236-15 — les scènes `cockpit*` posent aussi le cockpit PC (panneaux latéraux, filament).</p>
 */
@Component({
  selector: 'app-jarvis-banc',
  imports: [JarvisStage3dComponent, JarvisCockpitComponent, JarvisReplayComponent, JarvisNightComponent,
    JarvisPieceGalleryComponent, JarvisCardDetailComponent],
  template: `
    <app-jarvis-stage-3d #stage [withCore]="true" [planOverride]="staged.plan" [voiceLevel]="voice"
                         [labelLayer]="labels" [insets]="cockpit ? cockpitRef()?.insets() ?? noInsets : noInsets" />
    <div #labels class="jv3d-labels"></div>
    @if (cockpit) {
      <app-jarvis-cockpit #cockpitRef [stage]="stage" [signals]="screen" [shownFilter]="shownFilter" />
    }
    @if (scene === 'veilleur') {
      <app-jarvis-night [ambient]="nightAmbient" />
    }
    @if (pieces) {
      <!-- F-237 / SF-237-14 — les pièces du banc : galerie, héros, avant / après, planche (aucun réseau). -->
      <app-jarvis-piece-gallery [threeD]="scene === 'pieces-galerie' || scene === 'pieces-proactif'"
                                [subjectLabel]="'Edenred'" />
    }
    @if (cards) {
      <!-- F-248 / SF-248-10 — les pancartes du banc : bloc, éventail, mur, plein écran, comparaison (aucun réseau). -->
      <app-jarvis-piece-gallery [threeD]="!flatCards" [subjectLabel]="null" />
      @if (cardDetail; as d) {
        <app-jarvis-card-detail [card]="d.card" [detail]="d.detail" [other]="d.other" />
      }
    }
    @if (scene === 'cockpit-replay') {
      <app-jarvis-replay #replayRef />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  // SF-236-17 — le banc ne lit jamais le réseau : la journée vient d'un historique simulé.
  providers: [{ provide: JARVIS_DAY_HISTORY, useValue: () => bancHistory() },
    // SF-236-24 — le veilleur du banc : trois veilles simulées, aucun appel.
    { provide: VeilleService, useValue: { listForAccount: () => bancWatches() } },
    // SF-236-25 — l'horloge des veilles du cockpit : les mêmes veilles simulées.
    { provide: JARVIS_WATCHES, useValue: () => bancWatches() },
    // SF-236-26 — la carte d'activité du banc (aucun appel).
    { provide: JARVIS_ACTIVITY, useValue: () => of(BANC_ACTIVITY) },
    // F-237 / SF-237-14 — les contenus des pièces du banc sont dessinés sur place.
    { provide: JarvisPieceClient, useValue: BANC_PIECE_CLIENT }],
  host: { '[style]': 'vars' },
  styleUrls: ['./jarvis-labels.scss'],
  styles: [':host { display: block; min-height: 100dvh; background: #0B1020; }'],
})
export class JarvisBancComponent {
  protected readonly scene = inject(JARVIS_BANC)?.scene ?? 'coeur';
  private readonly t = inject(JARVIS_BANC)?.t ?? 0;
  protected readonly vars = holoCssVars();
  protected readonly staged = bancPlan(this.scene,
    typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches);
  protected readonly voice = (): number => this.staged.voice;
  protected readonly cockpit = this.scene.startsWith('cockpit');
  /** F-237 / SF-237-14 — les scènes `pieces-*` posent la galerie des pièces. */
  protected readonly pieces = this.scene.startsWith('pieces');
  /** F-248 / SF-248-10 — les scènes `pancartes-*` posent un jeu de pancartes. */
  protected readonly cards = this.scene.startsWith('pancartes');
  protected readonly flatCards = this.scene === 'pancartes-plat';
  protected readonly cardDetail = bancCardDetail(this.scene);
  private readonly pieceStore = inject(JarvisPieceStore);
  protected readonly screen = bancScreen(this.scene);
  protected readonly noInsets = { left: 0, right: 0 };
  protected readonly shownFilter: ShowFilter | null = this.scene === 'cockpit-journee' ? 'chantiers-bloques' : null;
  protected readonly cockpitRef = viewChild<JarvisCockpitComponent>('cockpitRef');
  protected readonly replayRef = viewChild<JarvisReplayComponent>('replayRef');
  /** SF-236-24 — le veilleur du banc : un poste muet vient de réveiller l'écran. */
  protected readonly nightAmbient = { hostDown: { clientKey: 'h-eden', name: 'Edenred' }, liveSubjects: new Set<string>(),
    calling: null, cost: null, alert: null };

  constructor() {
    if (this.pieces) {
      stageBancPieces(this.pieceStore, this.scene);
    }
    if (this.cards) {
      stageBancCards(this.pieceStore, this.scene);
    }
    if (this.cockpit) {
      writeFolds(this.scene === 'cockpit-replie' ? { left: true, right: true } : OPEN_FOLDS);
    }
    if (this.scene === 'cockpit-filament') {
      // Le filament de la capture : la ligne du poste « CAGIP » survolée, une fois la 3D affichée.
      afterNextRender(() => setTimeout(() => {
        const item = document.querySelector<HTMLElement>('.ck__item[data-key="h-cagip"]');
        item?.dispatchEvent(new MouseEvent('mouseenter'));
      }, 1500));
    }
    if (this.scene === 'cockpit-personnes') {
      // SF-236-18 — deux tableaux détachés, posés dans l'univers à côté de CAGIP.
      afterNextRender(() => setTimeout(() => {
        this.cockpitRef()?.detach('envoyer');
        this.cockpitRef()?.detach('personnes');
      }, 1500));
    }
    if (this.scene === 'cockpit-inspecteur' || this.scene === 'cockpit-recherche') {
      // SF-236-19 — la fiche du chantier 3 ; la recherche « forge » au clavier.
      afterNextRender(() => setTimeout(() => {
        const cockpit = this.cockpitRef();
        cockpit?.inspectNode('s-forge#3', 'chantier');
        if (this.scene === 'cockpit-recherche') {
          cockpit?.setQuery('forge');
        }
      }, 1500));
    }
    if (this.scene === 'cockpit-repli-anim') {
      // SF-236-21 — séquence T-P4 : le panneau gauche se replie, posé à l'instant t du banc (courbe `glisse`).
      afterNextRender(() => setTimeout(() => {
        const p = ease('glisse', Math.min(1, this.t / MOTION_DURATIONS.sidePanel));
        const aside = document.querySelector<HTMLElement>('.ck--left');
        const body = aside?.querySelector<HTMLElement>('.ck__body');
        if (aside && body) {
          aside.style.transition = 'none';
          body.style.transition = 'none';
          aside.style.transform = `translateX(calc((-100% + 28px) * ${p.toFixed(3)}))`;
          body.style.opacity = (1 - p).toFixed(3);
        }
      }, 1500));
    }
    if (this.scene === 'cockpit-replay') {
      // SF-236-22 — le tour de 9 h 15 rejoué à ×4, en pause à l'instant t du banc.
      afterNextRender(() => setTimeout(() => bancHistory().subscribe((messages) => {
        const track = trackOf(messages, 'u3', testUniverse(), 's-forge');
        const replay = this.replayRef();
        if (track && replay) {
          replay.play([track]);
          replay.setSpeed(4);
          replay.togglePause();
        }
      }), 1500));
    }
    if (this.scene === 'cockpit-activite') {
      // SF-236-26 — la section « Activité · 7 jours » ouverte (les anneaux 3D viennent du plan du banc).
      afterNextRender(() => setTimeout(() => this.cockpitRef()?.toggleActivity(), 1500));
    }
    if (this.scene === 'cockpit-vues') {
      // SF-236-20 — au téléphone, le volet « Aujourd'hui » ouvert depuis « ☰ Vues ».
      afterNextRender(() => setTimeout(() => this.cockpitRef()?.openView('journee'), 1500));
    }
    if (this.scene === 'cockpit-chronologies') {
      // SF-236-17 — la journée chargée (historique simulé, aucun appel) et un tour touché.
      afterNextRender(() => setTimeout(() => {
        this.cockpitRef()?.loadDay();
        setTimeout(() => document.querySelectorAll<HTMLElement>('.ck__turn')[2]?.click(), 100);
      }, 1500));
    }
  }
}

/** SF-236-17 — la journée du banc : cinq tours du jour, outils variés (familles), dont un dit à Jarvis. */
export function bancHistory(): Observable<AtelierMessage[]> {
  const at = (h: number, m: number) => {
    const d = new Date();
    d.setHours(h, m, 0, 0);
    return d.toISOString();
  };
  const turn = (id: string, h: number, m: number, ask: string, reply: string, tools: string[], voice = false) => [
    { id: `u${id}`, role: 'USER', content: ask, createdAt: at(h, m), via: voice ? 'JARVIS' : null },
    { id: `a${id}`, role: 'ASSISTANT', content: reply, createdAt: at(h, m + 1),
      terminal: { blocks: tools.map((tool) => ({ tool, command: null, toolUseId: null, output: '', hasOutput: false,
        error: false })), omittedBlocks: 0, inputTokens: 0, outputTokens: 0, activeSeconds: 0 } },
  ] as AtelierMessage[];
  return of([
    ...turn('1', 8, 10, 'Où en est la bascule DNS ?', 'Étape 2 sur 5 faite. Zahi doit valider le TTL.',
      ['read_file', 'bash', 'bash']),
    ...turn('2', 8, 40, 'Prépare le message pour Zahi', 'Le brouillon est prêt dans À envoyer.',
      ['read_file', 'email_draft'], true),
    ...turn('3', 9, 15, 'Vérifie les pods de forge', 'Trois pods tournent, aucun redémarrage.',
      ['bash', 'bash', 'bash', 'grep', 'read_file', 'bash']),
    ...turn('4', 10, 50, 'Publie la page d’architecture', 'Page publiée.', ['read_file', 'publish_page']),
    ...turn('5', 11, 5, 'Fais le point', 'Deux tickets bloqués, une attente.', ['memory_search', 'bash']),
  ]);
}

/** SF-236-24 — les veilles du banc : demain 7 h 30, 9 h, 12 h (passages connus du serveur). */
export function bancWatches(): Observable<JarvisWatchSummary[]> {
  const at = (h: number, m: number) => {
    const d = new Date();
    d.setDate(d.getDate() + 1);
    d.setHours(h, m, 0, 0);
    return d.toISOString();
  };
  const w = (id: string, name: string, subject: string, next: string, runTimes: string[]): JarvisWatchSummary => ({
    workspaceName: subject,
    watch: { id, name, active: true, proposed: false, nextRunAt: next, runTimes } as unknown as JarvisWatch });
  return of([w('w1', 'Tickets FORGE', 'Migration Forge', at(7, 30), ['07:30', '13:30']),
    w('w2', 'Incidents', 'Runner', at(9, 0), ['09:00', '17:00']), w('w3', 'Revue des PR', 'Doc archi', at(12, 0),
      ['12:00'])]);
}
