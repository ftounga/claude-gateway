import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';

import { TabAlertService } from './tab-alert.service';

/**
 * Faux document bouchonné : on contrôle `hidden`, le `title` et le `<link rel="icon">` pour
 * vérifier le signal in-tab sans dépendre de la vraie fenêtre Karma.
 */
class FakeLink {
  private attrs: Record<string, string | null> = { href: '/original.png' };
  getAttribute(name: string): string | null {
    return this.attrs[name] ?? null;
  }
  setAttribute(name: string, value: string): void {
    this.attrs[name] = value;
  }
  removeAttribute(name: string): void {
    this.attrs[name] = null;
  }
}

class FakeDoc {
  hidden = true;
  title = 'Claude Portal';
  link: FakeLink | null = new FakeLink();
  visibilityHandler: (() => void) | null = null;

  querySelector(sel: string): FakeLink | null {
    return sel === 'link[rel="icon"]' ? this.link : null;
  }
  addEventListener(type: string, handler: () => void): void {
    if (type === 'visibilitychange') {
      this.visibilityHandler = handler;
    }
  }
  becomeVisible(): void {
    this.hidden = false;
    this.visibilityHandler?.();
  }
}

describe('TabAlertService', () => {
  let doc: FakeDoc;
  let service: TabAlertService;

  function build(): void {
    TestBed.configureTestingModule({
      providers: [{ provide: DOCUMENT, useValue: doc }],
    });
    service = TestBed.inject(TabAlertService);
  }

  beforeEach(() => {
    doc = new FakeDoc();
  });

  it('allume titre + favicon quand un tour se termine et que l\'onglet est caché', () => {
    doc.hidden = true;
    build();

    service.signalTurnDone();

    expect(doc.title).toContain('Réponse prête');
    expect(doc.title).toContain('Claude Portal');
    expect(doc.link!.getAttribute('href')).toContain('data:image/svg+xml');
  });

  it('écrit « Autorisation demandée » pour une demande en attente (onglet caché)', () => {
    doc.hidden = true;
    build();

    service.signalAwaitingAuthorization();

    expect(doc.title).toContain('Autorisation demandée');
  });

  it('ne fait RIEN quand l\'onglet est au premier plan', () => {
    doc.hidden = false;
    build();

    service.signalTurnDone();
    service.signalAwaitingAuthorization();

    expect(doc.title).toBe('Claude Portal');
    expect(doc.link!.getAttribute('href')).toBe('/original.png');
  });

  it('rétablit titre + favicon d\'origine au clear()', () => {
    doc.hidden = true;
    build();
    service.signalTurnDone();

    service.clear();

    expect(doc.title).toBe('Claude Portal');
    expect(doc.link!.getAttribute('href')).toBe('/original.png');
  });

  it('rétablit tout au retour au premier plan (visibilitychange)', () => {
    doc.hidden = true;
    build();
    service.signalAwaitingAuthorization();
    expect(doc.title).toContain('Autorisation demandée');

    doc.becomeVisible();

    expect(doc.title).toBe('Claude Portal');
    expect(doc.link!.getAttribute('href')).toBe('/original.png');
  });

  it('ne casse pas sans <link rel="icon"> : le titre s\'allume quand même', () => {
    doc.hidden = true;
    doc.link = null;
    build();

    expect(() => service.signalTurnDone()).not.toThrow();
    expect(doc.title).toContain('Réponse prête');

    service.clear();
    expect(doc.title).toBe('Claude Portal');
  });

  it('clear() est sans effet si aucun signal n\'a été levé', () => {
    doc.hidden = true;
    build();

    service.clear();

    expect(doc.title).toBe('Claude Portal');
    expect(doc.link!.getAttribute('href')).toBe('/original.png');
  });
});
