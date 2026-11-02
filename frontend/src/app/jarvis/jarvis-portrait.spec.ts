import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import {
  JarvisPortraitClient, Portrait, PortraitSectionKey, counterLabel, draftChars, portraitErrorMessage, portraitTokens,
} from './jarvis-portrait';
import { JarvisPortraitComponent } from './jarvis-portrait.component';

const KEYS: [PortraitSectionKey, string, boolean][] = [
  ['QUI', 'Qui vous êtes', false], ['ENTOURAGE', 'Votre entourage', true],
  ['MOMENT', 'Ce qui compte en ce moment', true], ['GOUTS', 'Vos goûts', false],
  ['HUMOUR', 'L’humour qui vous plaît', false], ['INTERDITS', 'À ne pas aborder', false],
  ['DATES', 'Dates qui comptent', false],
];

function portrait(overrides: Partial<Portrait> = {}): Portrait {
  return {
    exists: true, enabled: true, version: 3,
    sections: KEYS.map(([key, title, sensitive]) => ({
      key, title, sensitive, text: key === 'QUI' ? 'Chef de projet' : '', updatedAt: key === 'QUI' ? '2025-01-02T10:00:00Z' : null,
      stale: key === 'QUI',
    })),
    souvenirs: [{ id: 's-1', number: 1, text: 'Ma fille est née en mars', sensitive: true, notedOn: '2026-10-01', source: 'VOIX', stale: false }],
    size: { chars: 38, tokens: 10, maxTokens: 1500, maxChars: 6000 },
    lastInterviewAt: null, interviewOffers: 0,
    ...overrides,
  };
}

/** F-241 / SF-241-01 — fonctions pures du portrait. */
describe('jarvis-portrait (fonctions)', () => {
  it('compte les jetons comme la passerelle (≈ caractères / 4) et affiche le compteur', () => {
    expect(portraitTokens(0)).toBe(0);
    expect(portraitTokens(1)).toBe(1);
    expect(portraitTokens(6000)).toBe(1500);
    expect(draftChars({ QUI: 'abcd', GOUTS: 'ef' }, [{ text: 'ghij' }])).toBe(10);
    expect(counterLabel(400)).toContain('100 / 1');
    expect(counterLabel(400)).toContain('jetons');
  });

  it('rend le message du refus de la passerelle, sinon un message neutre', () => {
    expect(portraitErrorMessage({ error: { message: 'Ma mémoire est pleine.' } })).toBe('Ma mémoire est pleine.');
    expect(portraitErrorMessage({ status: 503 })).toContain('indisponible');
    expect(portraitErrorMessage(null)).toContain('pas pu');
  });
});

/** F-241 / SF-241-01 — le volet « Mon portrait ». */
describe('JarvisPortraitComponent', () => {
  let client: jasmine.SpyObj<JarvisPortraitClient>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;
  let fixture: ComponentFixture<JarvisPortraitComponent>;

  function create(p: Portrait = portrait()): HTMLElement {
    client.get.and.returnValue(of(p));
    fixture = TestBed.createComponent(JarvisPortraitComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    client = jasmine.createSpyObj<JarvisPortraitClient>('JarvisPortraitClient',
      ['get', 'update', 'eraseAll', 'addSouvenir', 'patchSouvenir', 'deleteSouvenir', 'integrate']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [JarvisPortraitComponent],
      providers: [
        provideNoopAnimations(),
        { provide: JarvisPortraitClient, useValue: client },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
  });

  it('affiche les sept sections (sensible, à revoir, date), les souvenirs numérotés et le compteur', () => {
    const el = create();
    expect(el.querySelectorAll('.jp__section').length).toBe(7);
    expect(el.textContent).toContain('Votre entourage');
    expect(el.querySelectorAll('.jp__tag--stale').length).toBe(1);
    expect(el.textContent).toContain('sensible');
    expect(el.textContent).toContain('S1 — Ma fille est née en mars');
    expect(el.querySelector('.jp__counter')?.textContent).toContain('/ 1');
  });

  it('enregistre seulement les sections modifiées', () => {
    create();
    client.update.and.returnValue(of(portrait()));
    const component = fixture.componentInstance;
    component.edit('GOUTS', 'Le jazz');
    expect(component.dirty()).toBeTrue();
    component.save();
    expect(client.update).toHaveBeenCalledWith({ GOUTS: 'Le jazz' });
  });

  it('refuse d’enregistrer au-delà de 1 500 jetons, sans appel', () => {
    create();
    const component = fixture.componentInstance;
    component.edit('QUI', 'x'.repeat(6100));
    fixture.detectChanges();
    expect(component.over()).toBeTrue();
    component.save();
    expect(client.update).not.toHaveBeenCalled();
    expect((fixture.nativeElement as HTMLElement).querySelector('.jp__warn')).not.toBeNull();
  });

  it('efface un souvenir puis relit le portrait', () => {
    create();
    client.deleteSouvenir.and.returnValue(of(undefined));
    client.get.and.returnValue(of(portrait({ souvenirs: [] })));
    fixture.componentInstance.forget(portrait().souvenirs[0]);
    expect(client.deleteSouvenir).toHaveBeenCalledWith('s-1');
    expect(fixture.componentInstance.portrait()?.souvenirs.length).toBe(0);
  });

  it('« Tout effacer » demande une confirmation : refusée, rien ; acceptée, tout part', () => {
    create();
    client.eraseAll.and.returnValue(of(undefined));
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as never);
    fixture.componentInstance.eraseAll();
    expect(client.eraseAll).not.toHaveBeenCalled();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    fixture.componentInstance.eraseAll();
    expect(client.eraseAll).toHaveBeenCalled();
  });

  it('dit la mémoire pleine renvoyée par la passerelle', () => {
    const el = create();
    client.addSouvenir.and.returnValue(throwError(() => ({ status: 409, error: { message: 'Ma mémoire est pleine, faites le tri dans les réglages.' } })));
    const input = document.createElement('input');
    input.value = 'un de trop';
    fixture.componentInstance.add(input);
    expect(snack.open).toHaveBeenCalledWith('Ma mémoire est pleine, faites le tri dans les réglages.', 'OK', jasmine.any(Object));
    expect(el).toBeTruthy();
  });

  it('SF-241-02 — un brouillon d’entretien est surligné ; rien n’est écrit avant « Valider »', () => {
    client.get.and.returnValue(of(portrait()));
    fixture = TestBed.createComponent(JarvisPortraitComponent);
    fixture.componentRef.setInput('draft', { sections: { GOUTS: 'Le jazz' }, souvenirs: [{ text: 'Naissance en mars', sensitive: true, source: 'ENTRETIEN' }] });
    fixture.detectChanges();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.jp__draft')?.textContent).toContain('Naissance en mars');
    expect(el.querySelectorAll('.jp__section--draft').length).toBe(1);
    expect(client.update).not.toHaveBeenCalled();
    expect(client.addSouvenir).not.toHaveBeenCalled();
    let done: boolean | null = null;
    fixture.componentInstance.draftDone.subscribe((v) => (done = v));
    client.update.and.returnValue(of(portrait()));
    client.addSouvenir.and.returnValue(of(portrait().souvenirs[0]));
    fixture.componentInstance.validateDraft();
    expect(client.update).toHaveBeenCalledWith({ GOUTS: 'Le jazz' }, undefined, true);
    expect(client.addSouvenir).toHaveBeenCalledWith('Naissance en mars', true, 'ENTRETIEN');
    expect(done).toBeTrue();
  });

  it('SF-241-02 — « Abandonner » ne laisse aucune trace', () => {
    client.get.and.returnValue(of(portrait()));
    fixture = TestBed.createComponent(JarvisPortraitComponent);
    fixture.componentRef.setInput('draft', { sections: { GOUTS: 'Le jazz' }, souvenirs: [] });
    fixture.detectChanges();
    let done: boolean | null = null;
    fixture.componentInstance.draftDone.subscribe((v) => (done = v));
    fixture.componentInstance.abandonDraft();
    expect(done).toBeFalse();
    expect(client.update).not.toHaveBeenCalled();
    expect(client.addSouvenir).not.toHaveBeenCalled();
  });

  it('coupe l’interrupteur « Jarvis se sert de mon portrait »', () => {
    create();
    client.update.and.returnValue(of(portrait({ enabled: false })));
    fixture.componentInstance.toggleEnabled(false);
    expect(client.update).toHaveBeenCalledWith(null, false);
    expect(fixture.componentInstance.portrait()?.enabled).toBeFalse();
  });
});
