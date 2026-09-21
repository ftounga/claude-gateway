import { Component, OnInit, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';

import { PageFrameComponent } from '../shared/pages/page-frame.component';

/** Forme d'un jeton de partage : 43 caractères base64url. */
const SHARE_TOKEN = /^[A-Za-z0-9_-]{43}$/;

/**
 * **Une page partagée, ouverte sans compte** (F-109 / SF-109-05) — `/p/:token`, route **publique**. Un bandeau
 * sobre et la page dans son bac à sable ; aucun appel à l'API de l'application. Un lien révoqué, expiré ou
 * inconnu est dit par la gateway elle-même, dans le cadre.
 */
@Component({
  selector: 'app-shared-page',
  imports: [PageFrameComponent],
  template: `
    <section class="shared-page">
      <header class="shared-page__bar">
        <!-- F-110 / SF-110-06 : ni logo ni nom d'outil. Cette page est ouverte par un CLIENT, et
             rien de ce qui lui parvient ne doit désigner l'outil qui l'a produite. Le bandeau garde
             ce qui lui est utile : ce qu'est ce lien, et qu'il ne durera pas. -->
        <span class="shared-page__label">Page partagée</span>
        <span class="shared-page__hint">Lien révocable, à durée limitée</span>
      </header>
      @if (url(); as frameUrl) {
        <app-page-frame class="shared-page__frame" [url]="frameUrl" pageTitle="partagée"></app-page-frame>
      } @else {
        <p class="shared-page__invalid">Ce lien n'est pas ou plus valide.</p>
      }
    </section>
  `,
  styles: `
    .shared-page {
      display: flex;
      flex-direction: column;
      height: 100vh;
      background: var(--cg-bg);
    }

    .shared-page__bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--cg-space-2);
      padding: var(--cg-space-2) var(--cg-space-3);
      background: var(--cg-surface);
      border-bottom: 1px solid var(--cg-divider);
    }

    .shared-page__label {
      font-family: var(--cg-font-heading);
      font-weight: 600;
    }

    .shared-page__hint {
      font-size: 12px;
      color: var(--cg-text-secondary);
    }

    .shared-page__frame {
      flex: 1;
      min-height: 0;
    }

    .shared-page__invalid {
      padding: var(--cg-space-4);
      color: var(--cg-text-secondary);
    }
  `,
})
export class SharedPageComponent implements OnInit {

  private readonly title = inject(Title);

  /**
   * Le titre de l'onglet, posé à l'ouverture (F-110 / SF-110-06).
   *
   * <p>Sans cela, le client lit dans son navigateur le titre de l'application entière — qui nomme
   * l'outil. Le titre du document est la trace la plus facile à oublier, et l'une des plus
   * visibles.</p>
   */
  ngOnInit(): void {
    this.title.setTitle('Page partagée');
  }

  private readonly token = toSignal(inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('token') ?? '')),
    { initialValue: '' });

  /** L'adresse de lecture — jamais posée pour un jeton qui n'a pas la forme d'un lien de partage. */
  readonly url = computed(() => (SHARE_TOKEN.test(this.token()) ? `/api/p/${this.token()}/` : null));
}
