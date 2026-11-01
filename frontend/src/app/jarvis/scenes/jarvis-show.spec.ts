import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { renderMarkdown } from '../../shared/markdown.pipe';
import { OralStream, oralPhrase } from '../jarvis-oral';
import { JarvisConstellationComponent } from './jarvis-constellation.component';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader } from './jarvis-scene-reader';
import { NameIndex } from './jarvis-name-matcher';
import { isShowDismiss, parseShowLine, resolveShow, stripShowLines } from './jarvis-show';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-13 — « Montrer » : la ligne et sa résolution', () => {
  it('lit les six filtres fermés, avec ou sans accents, et un client facultatif', () => {
    expect(parseShowLine('🖥 montrer: clients')).toEqual({ filter: 'clients', client: null });
    expect(parseShowLine('🖥️ Montrer : connectés')).toEqual({ filter: 'connectes', client: null });
    expect(parseShowLine('🖥 montrer: déconnectés')?.filter).toBe('deconnectes');
    expect(parseShowLine('🖥 montrer: chantiers-bloqués CAGIP')).toEqual({ filter: 'chantiers-bloques', client: 'cagip' });
    expect(parseShowLine('🖥 montrer: etapes-en-cours')?.filter).toBe('etapes-en-cours');
    expect(parseShowLine('🖥 montrer: à-envoyer')?.filter).toBe('a-envoyer');
  });

  it('un filtre inconnu ou une autre ligne ne montre rien', () => {
    expect(parseShowLine('🖥 montrer: la météo')).toBeNull();
    expect(parseShowLine('🖥 affiche: clients')).toBeNull();
    expect(parseShowLine('montrer: clients')).toBeNull();
  });

  it('connectés ⇒ seuls les postes connectés, halo vert ; déconnectés ⇒ éteints', () => {
    const on = resolveShow(testUniverse(), { filter: 'connectes', client: null })!;
    expect(on.items.map((i) => i.node.key)).toEqual(['h-cagip']);
    expect(on.items[0].connected).toBeTrue();
    expect(on.title).toBe('1 poste connecté sur 2');
    const off = resolveShow(testUniverse(), { filter: 'deconnectes', client: null })!;
    expect(off.items.map((i) => i.node.key)).toEqual(['h-eden']);
    expect(off.items[0].connected).toBeFalse();
    expect(resolveShow(testUniverse(), { filter: 'clients', client: null })!.title).toBe('2 clients · 1 connecté');
  });

  it('chantiers bloqués ⇒ le losange et la personne attendue ; étapes en cours ⇒ l\'étape', () => {
    const blocked = resolveShow(testUniverse(), { filter: 'chantiers-bloques', client: null })!;
    expect(blocked.items.map((i) => i.node.key)).toEqual(['s-forge#3']);
    expect(blocked.items[0].note).toBe('attend Zahi');
    const steps = resolveShow(testUniverse(), { filter: 'etapes-en-cours', client: 'cagip' })!;
    expect(steps.items[0].note).toBe('étape 3/5 — Basculer');
    expect(resolveShow(testUniverse(), { filter: 'chantiers-bloques', client: 'edenred' })!.items).toEqual([]);
    expect(resolveShow(testUniverse(), { filter: 'clients', client: 'inconnu' })).toBeNull();
  });

  it('à envoyer ⇒ les destinataires, le compteur de l\'univers', () => {
    const toSend = resolveShow(testUniverse(), { filter: 'a-envoyer', client: null })!;
    expect(toSend.items.map((i) => i.node.label)).toEqual(['Zahi Ben']);
    expect(toSend.title).toBe('2 messages à envoyer');
  });

  it('« c\'est bon », « merci » referment ; une demande ne referme pas', () => {
    expect(isShowDismiss('C\'est bon.')).toBeTrue();
    expect(isShowDismiss('merci')).toBeTrue();
    expect(isShowDismiss('montre-moi les chantiers')).toBeFalse();
  });
});

describe('F-234 / SF-234-13 — la ligne n\'est ni dite, ni affichée, ni lue comme des noms', () => {
  const reply = '🎙 Voici vos clients connectés, Monsieur.\n🖥 montrer: connectes\nCAGIP est en ligne.';

  it('jamais dite', () => {
    expect(oralPhrase('🖥 montrer: clients\nVous avez deux clients.')).toBe('Vous avez deux clients.');
    const oral = new OralStream();
    expect(oral.push(reply).join(' ')).not.toContain('montrer');
  });

  it('jamais affichée (détail Jarvis, fil du terminal)', () => {
    expect(stripShowLines(reply)).not.toContain('montrer');
    expect(renderMarkdown(reply)).not.toContain('montrer');
    expect(renderMarkdown(reply)).toContain('CAGIP est en ligne.');
    expect(renderMarkdown('Il faut montrer: la page')).toContain('montrer');
  });

  it('le lecteur la retient (sans allumer de nom), le tour suivant l\'efface', () => {
    const reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
    reader.text('🖥 montrer: chantiers-bloques CAGIP\n', 0);
    expect(reader.state().show).toEqual({ filter: 'chantiers-bloques', client: 'cagip' });
    expect(reader.state().lit).toEqual([]);
    reader.startTurn();
    expect(reader.state().show).toBeNull();
    reader.text('🖥 montrer: clients', 10);
    reader.done(20);
    expect(reader.state().show?.filter).toBe('clients');
    expect(reader.clearShow()).toBeTrue();
    expect(reader.state().show).toBeNull();
  });
});

@Component({
  imports: [JarvisConstellationComponent, JarvisSceneComponent],
  template: `<app-jarvis-constellation [subjectId]="subject()"><button class="orb">orbe</button></app-jarvis-constellation>
             <app-jarvis-scene />`,
})
class HostComponent {
  readonly subject = signal<string | null>('s-forge');
}

describe('F-234 / SF-234-13 — la constellation se réorganise', () => {
  let bus: JarvisSceneBus;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.setSubject('s-forge');
  });

  afterEach(() => bus.ngOnDestroy());

  it('connectés : halo vert sur le poste connecté, halo éteint ailleurs, le reste estompé ; liste écrite', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    bus.turnStarted();
    bus.text('🎙 Voici.\n🖥 montrer: clients\n');
    bus.done();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__map')?.classList).toContain('is-showing');
    expect(el.querySelector('.cst__client[data-key="h-cagip"] .cst__halo:not(.cst__halo--off)')).not.toBeNull();
    expect(el.querySelector('.cst__client[data-key="h-eden"] .cst__halo--off')).not.toBeNull();
    expect(el.querySelector('.cst__subject[data-key="s-doc"]')?.classList).not.toContain('is-shown');
    expect(el.querySelector('.cst__crumbs')?.textContent).toContain('2 clients · 1 connecté');
    expect(el.querySelector('[data-scene="S20"]')?.textContent).toContain('Edenred');
  });

  it('chantiers bloqués : le losange au premier plan ; « c\'est bon » ⇒ retour à l\'état normal', () => {
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    bus.turnStarted();
    bus.text('🖥 montrer: chantiers-bloques\n');
    bus.done();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('.cst__mark.is-shown[data-kind="chantier"] text')?.textContent).toContain('Ch. 3 — Bascule DNS');
    expect(el.querySelector('.cst__subject[data-key="s-forge"]')?.classList).toContain('is-shown');
    expect(bus.dismissShow()).toBeTrue();
    fixture.detectChanges();
    expect(el.querySelector('.cst__map')?.classList).not.toContain('is-showing');
    expect(el.querySelector('[data-scene="S20"]')).toBeNull();
  });
});
