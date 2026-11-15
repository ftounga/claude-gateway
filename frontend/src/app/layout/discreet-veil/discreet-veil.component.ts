import { Component, DestroyRef, inject } from '@angular/core';
import { DiscreetVeilService } from '../../core/services/discreet-veil.service';

/**
 * **Le voile discret** (F-259 / SF-259-01) : une page de documentation grise et banale posée par-dessus l'écran.
 * Aucun logo, aucun nom de produit ni de client, pas de « verrouillé » : un regard de passage n'y voit rien à
 * retenir. Seul un clic (ou un toucher) l'enlève, et ce clic ne traverse pas jusqu'à l'écran dessous.
 *
 * <p>Monté par la coquille et par l'écran Jarvis : sa présence branche l'écoute du raccourci et de l'inactivité.</p>
 */
@Component({
  selector: 'app-discreet-veil',
  template: `
    @if (veil.veiled()) {
      <div class="veil" data-testid="discreet-veil"
        (pointerdown)="hold($event)" (mousedown)="hold($event)" (contextmenu)="hold($event)"
        (click)="lift($event)">
        <header class="veil__bar">
          <span class="veil__crumbs">Accueil › Configuration › Paramètres généraux</span>
          <span class="veil__search">Rechercher</span>
        </header>
        <div class="veil__body">
          <nav class="veil__toc">
            <p class="veil__toc-title">Sommaire</p>
            <ul>
              <li>Introduction</li>
              <li>Prise en main</li>
              <li>Configuration</li>
              <li class="veil__toc-current">Paramètres généraux</li>
              <li>Formats de fichiers</li>
              <li>Glossaire</li>
              <li>Questions fréquentes</li>
            </ul>
          </nav>
          <article class="veil__page">
            <h1>Paramètres généraux</h1>
            <p>
              Cette page décrit les paramètres généraux et leur valeur par défaut. Les modifications prennent effet
              au prochain démarrage. Pour les réglages avancés, voir la section Configuration.
            </p>
            <h2>Valeurs par défaut</h2>
            <table>
              <thead>
                <tr><th>Paramètre</th><th>Valeur</th><th>Description</th></tr>
              </thead>
              <tbody>
                <tr><td>Langue</td><td>Français</td><td>Langue des menus et des messages.</td></tr>
                <tr><td>Format de date</td><td>JJ/MM/AAAA</td><td>Format utilisé à l'affichage et à l'export.</td></tr>
                <tr><td>Séparateur décimal</td><td>Virgule</td><td>Appliqué aux nombres et aux montants.</td></tr>
                <tr><td>Taille de page</td><td>A4</td><td>Format retenu pour l'impression.</td></tr>
                <tr><td>Fuseau horaire</td><td>Europe/Paris</td><td>Référence des dates enregistrées.</td></tr>
              </tbody>
            </table>
            <h2>Remarques</h2>
            <p>
              Les valeurs ci-dessus s'appliquent à tous les documents créés après la modification. Les documents
              existants conservent leurs paramètres d'origine.
            </p>
            <p>Dernière mise à jour : voir l'historique des versions.</p>
          </article>
        </div>
      </div>
    }
  `,
  styles: `
    .veil {
      position: fixed;
      inset: 0;
      /* Au-dessus de tout, dialogues et calques Material compris. */
      z-index: 2147483000;
      display: flex;
      flex-direction: column;
      overflow: hidden;
      overscroll-behavior: contain;
      background: var(--cg-bg);
      color: var(--cg-text-primary);
      font-family: var(--cg-font-body);
      font-size: 14px;
      line-height: 1.6;
      cursor: default;
      user-select: none;
      -webkit-user-select: none;
      touch-action: none;
    }

    .veil__bar {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--cg-space-3, 12px);
      padding: 10px 24px;
      background: var(--cg-surface);
      border-bottom: 1px solid var(--cg-divider);
      color: var(--cg-text-secondary);
      font-size: 13px;
    }

    .veil__crumbs {
      min-width: 0;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .veil__search {
      flex: 0 0 auto;
      padding: 4px 12px;
      min-width: 160px;
      border: 1px solid var(--cg-divider);
      border-radius: 4px;
      background: var(--cg-bg);
    }

    .veil__body {
      flex: 1 1 auto;
      display: flex;
      min-height: 0;
    }

    .veil__toc {
      flex: 0 0 220px;
      padding: 24px;
      border-right: 1px solid var(--cg-divider);
      color: var(--cg-text-secondary);

      ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }

      li {
        padding: 4px 0;
      }
    }

    .veil__toc-title {
      margin: 0 0 8px;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }

    .veil__toc-current {
      color: var(--cg-text-primary);
      font-weight: 600;
    }

    .veil__page {
      flex: 1 1 auto;
      max-width: 760px;
      min-width: 0;
      padding: 24px 32px;

      h1 {
        margin: 0 0 12px;
        font-family: var(--cg-font-body);
        font-size: 24px;
        font-weight: 600;
        color: var(--cg-text-primary);
      }

      h2 {
        margin: 24px 0 8px;
        font-family: var(--cg-font-body);
        font-size: 17px;
        font-weight: 600;
        color: var(--cg-text-primary);
      }

      p {
        margin: 0 0 12px;
        color: var(--cg-text-secondary);
      }

      table {
        width: 100%;
        border-collapse: collapse;
        background: var(--cg-surface);
        border: 1px solid var(--cg-divider);
      }

      th,
      td {
        padding: 8px 12px;
        border-bottom: 1px solid var(--cg-divider);
        text-align: left;
        vertical-align: top;
      }

      th {
        background: var(--cg-surface-2);
        font-weight: 600;
      }
    }

    @media (max-width: 720px) {
      .veil__toc {
        display: none;
      }

      .veil__page {
        padding: 16px;
      }

      .veil__search {
        min-width: 0;
      }
    }
  `,
})
export class DiscreetVeilComponent {
  protected readonly veil = inject(DiscreetVeilService);

  constructor() {
    this.veil.attach();
    inject(DestroyRef).onDestroy(() => this.veil.detach());
  }

  /** Appui sur le voile : retenu, ni focus déplacé ni geste transmis dessous. */
  protected hold(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
  }

  /** Clic (ou toucher) : le voile s'enlève, le clic s'arrête là. */
  protected lift(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.veil.unveil();
  }
}
