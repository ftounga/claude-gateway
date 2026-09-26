import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { AtelierTerminalComponent } from './atelier-terminal.component';
import { MailService } from '../../core/services/mail.service';
import { PagesService } from '../../core/services/pages.service';

/**
 * **Le terminal ne déborde JAMAIS à droite sur téléphone** (F-158 / SF-158-10, correctif P0).
 *
 * <p>C'est LE garde-fou qui manquait. Les specs CSSOM (`terminal-contenu-responsive.spec.ts`)
 * n'inspectaient que la présence d'une règle — jamais le layout réel — de sorte que les fixes
 * « passaient » sans corriger. Ici on MESURE, dans un vrai DOM ChromeHeadless :
 * `host.scrollWidth <= host.clientWidth` (pas de débordement horizontal) avec un contenu
 * volontairement très large (ligne mono insécable), dans un hôte de 360 px puis 400 px.</p>
 *
 * <p>La fenêtre Karma mesure 1440×900 (F-98) : les règles `@media (max-width: 819px)` de la feuille
 * mobile n'y sont pas actives. On les applique donc au layout réel — extraction des règles déjà
 * injectées par Angular dans `document.styleSheets`, ré-application sans la garde media — puis on
 * mesure. C'est une mesure de LAYOUT, pas de CSSOM. On ne touche pas la config Karma globale.</p>
 */
describe('AtelierTerminalComponent — largeur réelle (F-158 / SF-158-10)', () => {
  let fixture: ComponentFixture<AtelierTerminalComponent>;
  let host: HTMLElement;
  let injected: HTMLStyleElement | null = null;

  beforeEach(async () => {
    const mail = jasmine.createSpyObj<MailService>('MailService', ['email']);
    mail.email.and.returnValue(of({}) as never);
    const pages = jasmine.createSpyObj<PagesService>('PagesService', ['get']);
    pages.get.and.returnValue(of({}) as never);

    await TestBed.configureTestingModule({
      imports: [AtelierTerminalComponent, NoopAnimationsModule],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MailService, useValue: mail },
        { provide: PagesService, useValue: pages },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(AtelierTerminalComponent);
    fixture.componentInstance.projectName = 'mon-projet';
    fixture.detectChanges();

    host = fixture.nativeElement as HTMLElement;
    // Ancré au document pour un layout réel, quelle que soit la racine de test.
    document.body.appendChild(host);
  });

  afterEach(() => {
    injected?.remove();
    injected = null;
    fixture.destroy();
  });

  /**
   * Applique au layout réel les règles `@media (max-width: 819px)` DU TERMINAL déjà injectées par
   * Angular (émulation) dans le document, sans la garde media — de sorte que le patron mobile agisse
   * réellement à un viewport Karma de 1440 px. Filtrées sur `terminal` pour n'affecter que le
   * composant (jamais les feuilles globales ou Material).
   */
  function applyTerminalMobileRules(): void {
    let css = '';
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try {
        rules = sheet.cssRules;
      } catch {
        continue; // feuille cross-origin : ignorée
      }
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSMediaRule && /max-width:\s*819px/.test(rule.media.mediaText)) {
          for (const inner of Array.from(rule.cssRules)) {
            if (inner.cssText.includes('terminal')) {
              css += inner.cssText + '\n';
            }
          }
        }
      }
    }
    injected = document.createElement('style');
    injected.textContent = css;
    document.head.appendChild(injected);
  }

  /**
   * Injecte une ligne mono INSÉCABLE très large comme SŒUR du fil (à l'image d'une rangée de
   * contrôle : target/mode/host-state), mesure, puis nettoie. Rend vrai si la PAGE (l'hôte) déborde
   * horizontalement à la largeur donnée.
   */
  function pageOverflowsAt(width: number): boolean {
    host.style.width = `${width}px`;
    const view = host.querySelector('.terminal-view') as HTMLElement;
    expect(view).withContext('.terminal-view absente').toBeTruthy();

    const probe = document.createElement('div');
    probe.className = 'sf15810-probe';
    probe.style.whiteSpace = 'nowrap';
    // ~1080 caractères, aucun espace ni trait d'union : aucune opportunité de coupure.
    probe.textContent = 'jarclaudeRUNNERinsecable'.repeat(45);
    view.appendChild(probe);

    void host.getBoundingClientRect(); // force un reflow
    const overflow = host.scrollWidth > host.clientWidth;
    probe.remove();
    return overflow;
  }

  it('à 360 px, une ligne mono très large ne fait PAS défiler la page (containment de la vue)', () => {
    applyTerminalMobileRules();
    expect(pageOverflowsAt(360))
      .withContext('la vue devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('à 400 px, une ligne mono très large ne fait PAS défiler la page', () => {
    applyTerminalMobileRules();
    expect(pageOverflowsAt(400))
      .withContext('la vue devrait clore l\'axe horizontal — host.scrollWidth <= host.clientWidth')
      .toBe(false);
  });

  it('sanity : SANS le patron mobile, la même ligne mono FAIT déborder (le test mesure bien le layout)', () => {
    // Aucun `applyTerminalMobileRules()` : les règles de base n'ont aucun containment → débordement réel.
    // Prouve que l'assertion mesure un vrai layout (et non une tautologie).
    expect(pageOverflowsAt(360)).toBe(true);
  });

  // ------------------------------------------------------------------ F-158 / SF-158-11
  // Les RANGÉES DE CONTRÔLE (target / mode / état du poste) tiennent dans l'écran à 360 px et
  // sont pleine largeur, pas seulement clippées par le containment de SF-158-10.
  describe('rangées de contrôle (SF-158-11)', () => {
    /** Rend un tour avec target + mode + état du poste (avec commande de relance), à `width` px. */
    function renderControls(width: number): void {
      const c = fixture.componentInstance;
      c.executionTarget = 'RUNNER'; // affiche `.terminal-target` et `.terminal-host-state`
      c.mode = 'ANSWER_PLAN'; // affiche `.terminal-mode`
      c.hostName = 'mac-de-bureau'; // hostKnown() = true
      c.runnerStatus = { connected: false, paired: true, lastSeenAt: null }; // resumeAvailable() = true
      fixture.detectChanges();
      host.style.width = `${width}px`;
      void host.getBoundingClientRect();
    }

    it('à 360 px, un tour avec target + mode + état du poste ne fait PAS défiler la page', () => {
      applyTerminalMobileRules();
      renderControls(360);
      expect(host.scrollWidth)
        .withContext('les rangées de contrôle devraient tenir dans 360 px')
        .toBeLessThanOrEqual(host.clientWidth);
    });

    it('à 360 px, une commande de relance très longue ENROULE dans la rangée d\'état du poste', () => {
      applyTerminalMobileRules();
      renderControls(360);
      const code = host.querySelector('.terminal-host-state code') as HTMLElement;
      expect(code).withContext('commande de relance absente').toBeTruthy();
      // Chaîne INSÉCABLE très longue (aucun tiret ni espace : pas d'opportunité de coupure naturelle) :
      // sans `word-break: break-all` (SF-158-11) elle déborde la rangée ; avec, elle enroule.
      code.textContent = 'usrlocallibclauderunnerjar'.repeat(25);
      const row = host.querySelector('.terminal-host-state') as HTMLElement;
      void row.getBoundingClientRect();
      expect(row.scrollWidth)
        .withContext('la rangée d\'état du poste devrait enrouler la commande longue')
        .toBeLessThanOrEqual(row.clientWidth);
    });

    it('à 360 px, les toggle-groups target et mode remplissent la largeur de leur rangée', () => {
      applyTerminalMobileRules();
      renderControls(360);
      for (const [rowSel, groupSel] of [
        ['.terminal-target', '.terminal-target-toggle'],
        ['.terminal-mode', '.terminal-mode-toggle'],
      ]) {
        const row = host.querySelector(rowSel) as HTMLElement;
        const group = host.querySelector(groupSel) as HTMLElement;
        expect(group).withContext(`${groupSel} absent`).toBeTruthy();
        const cs = getComputedStyle(row);
        const inner = row.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
        // `width:100%` → le groupe occupe toute la largeur de contenu de sa rangée (tolérance 2 px).
        expect(Math.abs(group.getBoundingClientRect().width - inner))
          .withContext(`${groupSel} devrait être pleine largeur`)
          .toBeLessThanOrEqual(2);
      }
    });
  });

  // ------------------------------------------------------------------ F-158 / SF-158-12
  // En-tête compact + menu ⋯ sur téléphone ; bandeau d'actions COMPLET et AUCUN ⋯ en desktop.
  // La bascule est pilotée par le signal `isNarrow` (matchMedia), pas par le CSS : le desktop
  // (fenêtre Karma 1440 px) ne dépend jamais de la feuille mobile.
  describe('en-tête compact + menu ⋯ (SF-158-12)', () => {
    it('à largeur desktop (isNarrow=false), le bandeau d\'actions complet est présent et le menu ⋯ absent', () => {
      // Fenêtre Karma 1440 px : matchMedia('(max-width:819px)') est faux → mode desktop par défaut.
      expect(fixture.componentInstance.isNarrow())
        .withContext('la fenêtre Karma (1440 px) doit être en mode desktop')
        .toBe(false);
      fixture.detectChanges();
      expect(host.querySelector('.terminal-overflow'))
        .withContext('aucun bouton ⋯ ne doit être rendu en desktop')
        .toBeNull();
      expect(host.querySelector('.terminal-bar-actions .terminal-restart'))
        .withContext('le bandeau complet (Nouveau départ) reste en clair en desktop')
        .toBeTruthy();
      expect(host.querySelector('.terminal-bar-actions .terminal-guard'))
        .withContext('le bandeau complet (Valider les commandes) reste en clair en desktop')
        .toBeTruthy();
    });

    it('en mode téléphone (isNarrow=true), le menu ⋯ remplace le bandeau en clair', () => {
      fixture.componentInstance.isNarrow.set(true);
      fixture.detectChanges();
      expect(host.querySelector('.terminal-overflow'))
        .withContext('le bouton ⋯ est présent sur téléphone')
        .toBeTruthy();
      // Le bandeau desktop (rendu sous `@if (!isNarrow())`) quitte le DOM : ses actions vivent
      // désormais dans le panneau du menu ⋯, ouvert à la demande — aucune n'est supprimée.
      expect(host.querySelector('.terminal-bar-actions .terminal-restart'))
        .withContext('le bandeau en clair est retiré sur téléphone (actions déplacées dans ⋯)')
        .toBeNull();
    });

    for (const width of [360, 400]) {
      it(`à ${width} px, l'en-tête compact ne fait PAS déborder la page`, () => {
        fixture.componentInstance.isNarrow.set(true);
        fixture.detectChanges();
        applyTerminalMobileRules();
        host.style.width = `${width}px`;
        void host.getBoundingClientRect();
        expect(host.scrollWidth)
          .withContext(`l'en-tête compact devrait tenir dans ${width} px`)
          .toBeLessThanOrEqual(host.clientWidth);
      });
    }
  });

  // ------------------------------------------------------------------ F-158 / SF-158-13
  // Composeur ancré en bas + décisions d'autorisation empilées pleine largeur : rien ne déborde à
  // 360/400 px, et les décisions sont bien en colonne.
  describe('composeur ancré + décisions empilées (SF-158-13)', () => {
    /** Rend une demande d'autorisation en attente (fait apparaître `.terminal-ask` + ses boutons). */
    function renderPendingDecision(): void {
      fixture.componentInstance.pendingConfirmation = {
        toolUseId: 't1',
        tool: 'bash',
        detail: 'terraform plan -chdir=dev',
        source: 'HOSTED_SANDBOX',
        answering: false,
        denying: false,
        reason: '',
        deadline: null,
        timeoutMs: null,
      };
      fixture.detectChanges();
    }

    for (const width of [360, 400]) {
      it(`à ${width} px, le composeur ne fait PAS déborder la page`, () => {
        applyTerminalMobileRules();
        host.style.width = `${width}px`;
        void host.getBoundingClientRect();
        const input = host.querySelector('.terminal-input') as HTMLElement;
        expect(input).withContext('.terminal-input absent').toBeTruthy();
        expect(host.scrollWidth)
          .withContext(`le composeur devrait tenir dans ${width} px`)
          .toBeLessThanOrEqual(host.clientWidth);
      });

      it(`à ${width} px, les boutons de décision sont empilés (colonne) et ne débordent pas`, () => {
        renderPendingDecision();
        applyTerminalMobileRules();
        host.style.width = `${width}px`;
        void host.getBoundingClientRect();
        const acts = host.querySelector('.terminal-ask-actions') as HTMLElement;
        expect(acts).withContext('.terminal-ask-actions absent').toBeTruthy();
        expect(getComputedStyle(acts).flexDirection)
          .withContext('les décisions doivent être empilées en colonne')
          .toBe('column');
        expect(host.scrollWidth)
          .withContext(`les décisions empilées devraient tenir dans ${width} px`)
          .toBeLessThanOrEqual(host.clientWidth);
      });
    }
  });

  // ------------------------------------------------------------------ F-158 / SF-158-14
  // Peau du fil : la sortie DÉFILE DANS SA BOÎTE (jamais la page) et la demande est une carte.
  describe('peau du fil — boîte de sortie + carte de la demande (SF-158-14)', () => {
    /** Rend un vrai tour (demande utilisateur + bloc bash avec une sortie très large). */
    function renderTurn(): void {
      const c = fixture.componentInstance;
      c.messages = [
        { id: 'u1', role: 'USER', content: 'Regarde ce que Daoud a poussé', actions: [] },
        {
          id: 'a1',
          role: 'ASSISTANT',
          content: '',
          actions: [],
          terminal: [
            {
              tool: 'bash',
              command: 'git log --oneline',
              toolUseId: null,
              threadId: null,
              // Ligne INSÉCABLE très large : sans la boîte à défilement propre, elle déborderait.
              output: 'bucket_data_ingestion_tfstate_dev_terraform_'.repeat(30),
              hasOutput: true,
              error: false,
              expanded: true,
            },
          ],
        },
      ] as never;
      fixture.detectChanges();
    }

    for (const width of [360, 400]) {
      it(`à ${width} px, la sortie défile DANS sa boîte et ne fait PAS déborder la page`, () => {
        renderTurn();
        applyTerminalMobileRules();
        host.style.width = `${width}px`;
        void host.getBoundingClientRect();
        const out = host.querySelector('.terminal-output') as HTMLElement;
        expect(out).withContext('.terminal-output absente').toBeTruthy();
        expect(getComputedStyle(out).overflowX)
          .withContext('la boîte de sortie doit défiler chez elle (overflow-x:auto)')
          .toBe('auto');
        expect(out.scrollWidth)
          .withContext('la sortie très large doit dépasser DANS sa boîte')
          .toBeGreaterThan(out.clientWidth);
        expect(host.scrollWidth)
          .withContext('la page ne doit pas défiler horizontalement')
          .toBeLessThanOrEqual(host.clientWidth);
      });
    }

    it('à 360 px, la demande de l\'utilisateur est une carte boxée (fond non transparent)', () => {
      renderTurn();
      applyTerminalMobileRules();
      host.style.width = '360px';
      void host.getBoundingClientRect();
      const req = host.querySelector('.terminal-prompt-line') as HTMLElement;
      expect(req).withContext('.terminal-prompt-line absente').toBeTruthy();
      expect(getComputedStyle(req).backgroundColor)
        .withContext('la carte de la demande doit avoir un fond (creux navy)')
        .not.toBe('rgba(0, 0, 0, 0)');
    });
  });

  // ------------------------------------------------------------------ F-158 / SF-158-15
  // PEAU SOMBRE « PRO » : sur téléphone, le fond du fil passe au navy profond de la maquette
  // (`--cg-navy` = #0B1020 = rgb(11,16,32)), le chrome en surface navy-2, les sous-agents en carte
  // bordée arrondie et la demande d'autorisation en carte à rayon >= 12. Le DESKTOP reste inchangé
  // (le fond `.terminal-view` reste `--cg-primary` = rgb(26,58,92) sans la feuille mobile).
  describe('peau sombre pro du fil (SF-158-15)', () => {
    const NAVY = 'rgb(11, 16, 32)'; // --cg-navy #0B1020
    const PRIMARY = 'rgb(26, 58, 92)'; // --cg-primary #1A3A5C

    /** Rend un tour avec un LOT d'explorations parallèles (>= 2 `explore` adjacents → `.terminal-subagents`). */
    function renderSubAgents(): void {
      const c = fixture.componentInstance;
      c.messages = [
        {
          id: 'a1',
          role: 'ASSISTANT',
          content: '',
          actions: [],
          terminal: [
            { tool: 'explore', command: 'où est défini le backend Terraform ?', toolUseId: null, threadId: null, output: '', hasOutput: false, error: false, expanded: false },
            { tool: 'explore', command: 'quels comptes AWS sont référencés ?', toolUseId: null, threadId: null, output: '', hasOutput: false, error: false, expanded: false },
          ],
        },
      ] as never;
      fixture.detectChanges();
    }

    /** Rend une demande d'autorisation en attente (fait apparaître `.terminal-ask`). */
    function renderPendingDecision(): void {
      fixture.componentInstance.pendingConfirmation = {
        toolUseId: 't1',
        tool: 'bash',
        detail: 'terraform plan -chdir=dev',
        source: 'HOSTED_SANDBOX',
        answering: false,
        denying: false,
        reason: '',
        deadline: null,
        timeoutMs: null,
      };
      fixture.detectChanges();
    }

    it('à 360 px, le fond du fil interactif est le navy profond de la maquette (--cg-navy)', () => {
      applyTerminalMobileRules();
      host.style.width = '360px';
      void host.getBoundingClientRect();
      const view = host.querySelector('.terminal-view') as HTMLElement;
      expect(view).withContext('.terminal-view absente').toBeTruthy();
      expect(getComputedStyle(view).backgroundColor)
        .withContext('le fond du fil doit passer au navy profond (--cg-navy), pas rester le primary daté')
        .toBe(NAVY);
    });

    it('DESKTOP (1440 px, sans la feuille mobile) : le fond du fil reste --cg-primary — peau inchangée', () => {
      // Aucun applyTerminalMobileRules() : la fenêtre Karma est à 1440 px, les règles @media 819px
      // ne s'appliquent pas → le desktop garde son fond primary. Garde-fou de non-régression desktop.
      const view = host.querySelector('.terminal-view') as HTMLElement;
      expect(getComputedStyle(view).backgroundColor)
        .withContext('le desktop ne doit JAMAIS hériter de la peau mobile (fond primary conservé)')
        .toBe(PRIMARY);
    });

    it('à 360 px, le lot de sous-agents est une CARTE bordée arrondie (fond non transparent, rayon > 0)', () => {
      renderSubAgents();
      applyTerminalMobileRules();
      host.style.width = '360px';
      void host.getBoundingClientRect();
      const subs = host.querySelector('.terminal-subagents') as HTMLElement;
      expect(subs).withContext('.terminal-subagents absente (lot d\'explorations non rendu)').toBeTruthy();
      const cs = getComputedStyle(subs);
      expect(cs.backgroundColor)
        .withContext('la carte des sous-agents doit avoir une surface (navy-2)')
        .not.toBe('rgba(0, 0, 0, 0)');
      expect(parseFloat(cs.borderTopLeftRadius))
        .withContext('la carte des sous-agents doit être arrondie (maquette .subs)')
        .toBeGreaterThan(0);
    });

    it('à 360 px, la demande d\'autorisation est une carte à rayon >= 12 (maquette .ask)', () => {
      renderPendingDecision();
      applyTerminalMobileRules();
      host.style.width = '360px';
      void host.getBoundingClientRect();
      const ask = host.querySelector('.terminal-ask') as HTMLElement;
      expect(ask).withContext('.terminal-ask absente').toBeTruthy();
      expect(parseFloat(getComputedStyle(ask).borderTopLeftRadius))
        .withContext('la carte de décision doit être franchement arrondie (>= 12 px)')
        .toBeGreaterThanOrEqual(12);
    });
  });

  // ------------------------------------------------------------------ F-158 / SF-158-16
  // LE FIL DU TERMINAL « RAILED » (rail « Vos questions », F-126) NE DÉBORDE PLUS SUR MOBILE.
  //
  // Cause racine (mesurée en Chrome à 390 px) : l'override mobile de `.terminal-scrollback--railed`
  // utilisait `grid-template-columns: 1fr` — or `1fr` = `minmax(auto, 1fr)`, dont le minimum de piste
  // est le min-content de ses items. Le fil (`.terminal-thread`) porte `min-width: 0` et ne dilate
  // donc PAS la piste ; mais le RAIL (`.terminal-qrail`, sœur de grille sans `min-width: 0`) le fait :
  // une longue ligne insécable dans une question s'affiche dans `.terminal-qrail__label` dont le
  // `-webkit-line-clamp` NE plafonne PAS son min-content, ce qui gonfle l'unique piste (mesuré ~6400 px)
  // et étire le FIL avec elle (les deux items partagent la piste), d'où des messages coupés à droite.
  // Le correctif — `minmax(0, 1fr)` — fixe le minimum de piste à 0 : la colonne suit la largeur du
  // conteneur, le fil reste borné, le contenu se replie / défile dans ses propres boîtes.
  //
  // POURQUOI CE TEST ET PAS LES PRÉCÉDENTS : ils mesuraient `.terminal-scrollback` (le conteneur
  // clippant, borné par `overflow-x: hidden`) — jamais `.terminal-thread` (le FIL, lui dilaté à la
  // largeur de la piste). Ici on mesure LE FIL. Rouge avec `1fr`, vert avec `minmax(0, 1fr)`.
  describe('fil railed — la grille mobile ne déborde plus (SF-158-16)', () => {
    /**
     * Rend un vrai tour AVEC rail « Vos questions » : une question de l'utilisateur (⇒ `userQuestions`
     * non vide ⇒ classe `--railed` posée) portant une longue ligne insécable (elle alimente le rail),
     * plus un bloc dont la commande est une longue ligne de code insécable (une « ligne de code dans
     * un message »). Le contenu est volontairement sans espace ni tiret : aucune coupure naturelle.
     */
    function renderRailedWideTurn(): void {
      const c = fixture.componentInstance;
      c.readOnly = false;
      c.messages = [
        {
          id: 'u1',
          role: 'USER',
          content: 'Regarde ' + 'bucket_data_ingestion_tfstate_dev_terraform_'.repeat(20),
          actions: [],
        },
        {
          id: 'a1',
          role: 'ASSISTANT',
          content: '',
          actions: [],
          terminal: [
            {
              tool: 'bash',
              command: 'cat ' + 'usrlocallibclauderunnerjar'.repeat(20),
              toolUseId: null,
              threadId: null,
              output: '',
              hasOutput: false,
              error: false,
              expanded: false,
            },
          ],
        },
      ] as never;
      fixture.detectChanges();
    }

    it('à 390 px, avec le rail et un contenu large, le FIL (.terminal-thread) tient dans l\'écran', () => {
      renderRailedWideTurn();
      applyTerminalMobileRules();
      host.style.width = '390px';
      void host.getBoundingClientRect();

      const scrollback = host.querySelector('.terminal-scrollback') as HTMLElement;
      expect(scrollback).withContext('.terminal-scrollback absente').toBeTruthy();
      expect(scrollback.classList.contains('terminal-scrollback--railed'))
        .withContext('le rail « Vos questions » doit être actif (grille --railed)')
        .toBe(true);

      const thread = host.querySelector('.terminal-thread') as HTMLElement;
      expect(thread).withContext('.terminal-thread absent').toBeTruthy();
      // LE cœur du correctif : sous `1fr` le fil était dilaté (~6400 px) par le rail ; sous
      // `minmax(0, 1fr)` il reste borné à la largeur de l'écran.
      expect(thread.clientWidth)
        .withContext('le FIL doit rester dans 390 px (piste minmax(0,1fr), pas 1fr)')
        .toBeLessThanOrEqual(390);
    });

    it('à 390 px, aucun enfant direct du fil ne déborde au-delà de la largeur du fil', () => {
      renderRailedWideTurn();
      applyTerminalMobileRules();
      host.style.width = '390px';
      void host.getBoundingClientRect();
      const thread = host.querySelector('.terminal-thread') as HTMLElement;
      for (const child of Array.from(thread.children) as HTMLElement[]) {
        // Aucun enfant direct du fil ne dépasse l'écran (390 px, tolérance 1 px sous-pixel). Sous `1fr`
        // le fil était étiré à ~6400 px et ses enfants avec ; sous `minmax(0,1fr)` ils se replient.
        expect(child.offsetWidth)
          .withContext(`un enfant direct du fil déborde de l'écran (${child.className || child.tagName})`)
          .toBeLessThanOrEqual(391);
      }
    });

    it('DESKTOP (1440 px, sans la feuille mobile) : la grille railed garde ses deux pistes (…232px)', () => {
      // Aucun applyTerminalMobileRules() : à 1440 px les règles @media 819px ne s'appliquent pas →
      // le desktop garde `grid-template-columns: minmax(0, 1fr) 232px`. Garde-fou de non-régression.
      renderRailedWideTurn();
      const scrollback = host.querySelector('.terminal-scrollback') as HTMLElement;
      expect(scrollback.classList.contains('terminal-scrollback--railed')).toBe(true);
      const tracks = getComputedStyle(scrollback).gridTemplateColumns.trim().split(/\s+/);
      expect(tracks.length)
        .withContext('le desktop doit garder DEUX pistes (fil + rail 232px), pas une seule')
        .toBe(2);
      expect(tracks[1])
        .withContext('la seconde piste (le rail) doit rester 232px en desktop')
        .toBe('232px');
    });
  });

  // ------------------------------------------------------------------ F-158 / SF-158-23
  // ALLER AU FOND À L'ENTRÉE + SUIVRE LE FLUX, sur mobile comme desktop.
  //
  // L'hôte est ancré à `document.body` (beforeEach) → `scrollHeight` est réel, donc le déclencheur de
  // `ngAfterViewChecked` (la hauteur du contenu a changé) se produit vraiment. On espionne
  // `Element.prototype.scrollIntoView` et on vérifie que c'est bien LA SENTINELLE DE FOND
  // (`.terminal-bottom-sentinel`) qu'on amène dans le champ de vision — ce que l'ancien
  // `el.scrollTop = el.scrollHeight` ne faisait PAS (et qui était un no-op sur mobile, où
  // `.terminal-scrollback` n'est pas le conteneur défilant). `scrollIntoView` défile l'ancêtre
  // défilant réel (fenêtre sur mobile, scrollback sur desktop) : le correctif tient des deux côtés.
  describe('aller au fond à l\'entrée et suivre le flux (SF-158-23)', () => {
    /** Rend le terminal avec `n` messages assistant, en défilant vraiment (hôte ancré au document). */
    function renderMessages(n: number): void {
      const c = fixture.componentInstance;
      c.readOnly = false;
      const messages = [];
      for (let i = 0; i < n; i += 1) {
        messages.push({ id: `u${i}`, role: 'USER', content: `Question ${i}`, actions: [] });
        messages.push({ id: `a${i}`, role: 'ASSISTANT', content: `Réponse ${i}`.repeat(30), actions: [] });
      }
      c.messages = messages as never;
      fixture.detectChanges();
    }

    /** Vrai si un appel à scrollIntoView a visé la sentinelle de fond. */
    function scrolledSentinelInto(spy: jasmine.Spy): boolean {
      return spy.calls.all().some(
        (call) => (call.object as HTMLElement)?.classList?.contains('terminal-bottom-sentinel'),
      );
    }

    it('la sentinelle de fond est le DERNIER enfant du fil', () => {
      renderMessages(3);
      const thread = host.querySelector('.terminal-thread') as HTMLElement;
      const last = thread.lastElementChild as HTMLElement;
      expect(last)
        .withContext('le fil n\'a pas d\'enfant')
        .toBeTruthy();
      expect(last.classList.contains('terminal-bottom-sentinel'))
        .withContext('la sentinelle doit être le dernier enfant de .terminal-thread')
        .toBe(true);
    });

    it('À L\'ENTRÉE : le 1er rendu avec des messages amène la sentinelle de fond dans le champ de vision', () => {
      const spy = spyOn(Element.prototype, 'scrollIntoView');
      renderMessages(12); // long fil : sans le fix, la vue resterait en haut sur mobile
      expect(scrolledSentinelInto(spy))
        .withContext('à l\'entrée, on doit scrollIntoView la sentinelle de fond (saut au dernier message)')
        .toBe(true);
    });

    it('SUIVI DU FLUX : un nouveau message ré-amène la sentinelle de fond dans le champ de vision', () => {
      renderMessages(4); // premier rendu (avant l'espion : on isole l'effet du nouveau contenu)
      const spy = spyOn(Element.prototype, 'scrollIntoView');
      const c = fixture.componentInstance;
      c.messages = [
        ...(c.messages as never[]),
        { id: 'a-new', role: 'ASSISTANT', content: 'Nouveau contenu qui arrive'.repeat(30), actions: [] },
      ] as never;
      fixture.detectChanges();
      expect(scrolledSentinelInto(spy))
        .withContext('à l\'arrivée de contenu, le flux doit suivre (scrollIntoView de la sentinelle)')
        .toBe(true);
    });

    it('le saut est INSTANTANÉ (block:end, aucun behavior smooth)', () => {
      const spy = spyOn(Element.prototype, 'scrollIntoView');
      renderMessages(6);
      const sentinelCall = spy.calls.all().find(
        (call) => (call.object as HTMLElement)?.classList?.contains('terminal-bottom-sentinel'),
      );
      expect(sentinelCall).withContext('aucun scrollIntoView sur la sentinelle').toBeTruthy();
      const options = sentinelCall!.args[0] as ScrollIntoViewOptions | undefined;
      expect(options?.block).withContext('la sentinelle doit être amenée au bas (block:end)').toBe('end');
      expect(options?.behavior)
        .withContext('aucune animation smooth : saut instantané à l\'entrée')
        .not.toBe('smooth');
    });
  });
});
