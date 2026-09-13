import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { RadarAliasView } from '../../core/models/radar-subject.models';
import { RadarSubjectAliasesComponent } from './radar-subject-aliases.component';

/** Les autres noms d'un sujet (F-99 / SF-99-06). */
describe('RadarSubjectAliasesComponent', () => {
  let fixture: ComponentFixture<RadarSubjectAliasesComponent>;
  let component: RadarSubjectAliasesComponent;

  const aliases: RadarAliasView[] = [
    { id: 'a0', alias: 'Double auth', origin: 'MERGE', rejected: false },
    { id: 'a9', alias: 'Contrat Okta', origin: 'SPLIT', rejected: true },
  ];

  function build(locked = false): HTMLElement {
    TestBed.configureTestingModule({ imports: [RadarSubjectAliasesComponent], providers: [provideNoopAnimations()] });
    fixture = TestBed.createComponent(RadarSubjectAliasesComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('aliases', aliases);
    fixture.componentRef.setInput('locked', locked);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('sépare les alias acceptés des consignes', () => {
    const root = build();
    expect(root.querySelector('.subject-aliases__accepted')?.textContent).toContain('Double auth');
    expect(root.querySelector('.subject-aliases__rejected')?.textContent).toContain('Contrat Okta');
    expect(root.querySelector('.subject-aliases__accepted')?.textContent).not.toContain('Contrat Okta');
  });

  it('retirer émet l’alias ; ajouter émet le nom nettoyé, jamais un nom vide', () => {
    const root = build();
    const removed: RadarAliasView[] = [];
    const added: string[] = [];
    component.remove.subscribe((alias) => removed.push(alias));
    component.add.subscribe((name) => added.push(name));

    (root.querySelector('.subject-aliases__rejected .subject-aliases__remove') as HTMLButtonElement).click();
    expect(removed).toEqual([aliases[1]]);

    (root.querySelector('.subject-aliases__open') as HTMLButtonElement).click();
    fixture.detectChanges();
    component.draft.set('   ');
    fixture.detectChanges();
    expect((root.querySelector('.subject-aliases__save') as HTMLButtonElement).disabled).toBeTrue();

    component.draft.set('  Chantier Okta ');
    component.submit(new Event('submit'));
    expect(added).toEqual(['Chantier Okta']);
    expect(component.adding()).toBeFalse();
  });

  it('un sujet fusionné se lit sans geste', () => {
    const root = build(true);
    expect(root.querySelector('.subject-aliases__remove')).toBeNull();
    expect(root.querySelector('.subject-aliases__open')).toBeNull();
    expect(root.textContent).toContain('Double auth');
  });
});
