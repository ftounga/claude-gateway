import { TestBed } from '@angular/core/testing';

import { NameIndex } from './jarvis-name-matcher';
import { JarvisSceneBus } from './jarvis-scene-bus.service';
import { JarvisSceneComponent } from './jarvis-scene.component';
import { JarvisSceneReader } from './jarvis-scene-reader';
import { resolveScene } from './jarvis-scene-registry';
import { testUniverse } from './jarvis-universe.fixture';

describe('F-234 / SF-234-07 — S1 messages à envoyer', () => {
  let reader: JarvisSceneReader;

  beforeEach(() => {
    reader = new JarvisSceneReader(new NameIndex(testUniverse()), 's-forge');
    reader.startTurn();
  });

  it('bloc ```mail avec « À : Zahi » ⇒ enveloppe, nœud Zahi, +1 à la fin ; corps jamais dans l\'état', () => {
    reader.text('🎙 Je prépare le mail.\n```mail attente=dns-ttl\nÀ : Zahi\n', 0);
    let scene = reader.state().scene!;
    expect(scene.id).toBe('S1');
    expect(scene.data).toEqual(jasmine.objectContaining({ envelope: 'mail', to: 'Zahi', finished: false, proof: false }));
    expect(reader.state().lit.some((l) => l.node.kind === 'person')).toBeTrue();
    reader.text('Objet : Compte forge\nBonjour Zahi, le mot de passe est secret123.\n```\n', 100);
    scene = reader.state().scene!;
    expect(scene.data?.subject).toBe('Compte forge');
    expect(scene.data?.finished).toBeTrue();
    expect(reader.state().toSendAdded).toBe(1);
    expect(JSON.stringify(reader.state())).not.toContain('secret123');
    expect(JSON.stringify(reader.state())).not.toContain('Bonjour');
  });

  it('```message preuves=… ⇒ bulle et objectif photo', () => {
    reader.text('```message preuves=p1,p2\nÀ : Équipe OPS\n', 0);
    expect(reader.state().scene?.data).toEqual(jasmine.objectContaining({ envelope: 'message', proof: true }));
  });

  it('email_me ⇒ « pour vous », capturer_preuves ⇒ preuve jointe (pas une commande)', () => {
    expect(resolveScene('email_me').scene).toBe('S1');
    reader.action({ type: 'email_me', path: 'Courriel · Compte rendu du jour' }, 0);
    expect(reader.state().scene?.data).toEqual(jasmine.objectContaining({ forMe: true, subject: 'Compte rendu du jour' }));
    reader.action({ type: 'bash', path: 'captures de preuve : Recette paiement' }, 5000);
    reader.tick(5000);
    const scene = reader.state().scene!;
    expect(scene.id).toBe('S1');
    expect(scene.libelle).toBe('Preuves capturées');
    expect(scene.data).toEqual(jasmine.objectContaining({ proof: true, subject: 'Recette paiement' }));
  });
});

describe('F-234 / SF-234-07 — rendu', () => {
  let bus: JarvisSceneBus;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [JarvisSceneComponent] });
    bus = TestBed.inject(JarvisSceneBus);
    bus.setUniverse(testUniverse());
    bus.useClock(() => 0);
  });

  afterEach(() => bus.ngOnDestroy());

  it('l\'enveloppe, le destinataire, l\'objet ; « À envoyer (3) » +1 à la fin du bloc', () => {
    const fixture = TestBed.createComponent(JarvisSceneComponent);
    bus.turnStarted();
    bus.text('```mail\nÀ : Zahi\nObjet : Compte forge\nCorps privé\n');
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    expect(el.querySelector('[data-scene="S1"] .letter__to')?.textContent).toContain('À : Zahi');
    expect(el.querySelector('.letter__subject')?.textContent).toContain('Compte forge');
    expect(el.querySelector('.tosend')?.textContent).toContain('À envoyer (2)');
    bus.text('```\n');
    fixture.detectChanges();
    expect(el.querySelector('.tosend')?.textContent).toContain('À envoyer (3)');
    expect(el.querySelector('.tosend__plus')).not.toBeNull();
    expect(el.textContent).not.toContain('Corps privé');
    bus.aborted();
  });
});
