import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';

import { VigiePerson } from '../../core/models/vigie.models';
import { DIRECTORY_PAGE } from './radar-directory';
import { RadarDirectoryComponent } from './radar-directory.component';

/** L'annuaire du Radar, l'écran (F-103 / SF-103-04). */
describe('RadarDirectoryComponent', () => {
  let fixture: ComponentFixture<RadarDirectoryComponent>;

  const paul: VigiePerson = {
    id: 'p1', displayName: 'Paul Martin', jobTitle: 'Manager sécurité', lastInteractionAt: '2026-09-12T10:00:00Z',
    subjects: [
      { subjectId: 's2', subjectName: 'LDAP', state: 'CLOSED', role: 'EXPERT' },
      { subjectId: 's1', subjectName: 'MFA prestataires', state: 'NEW', role: 'DECIDES' },
    ],
  };
  const sophie: VigiePerson = { id: 'p2', displayName: 'Sophie Laurent', jobTitle: null, lastInteractionAt: null, subjects: [] };

  function build(people: VigiePerson[]): HTMLElement {
    TestBed.configureTestingModule({
      imports: [RadarDirectoryComponent],
      providers: [provideRouter([]), provideNoopAnimations()],
    });
    fixture = TestBed.createComponent(RadarDirectoryComponent);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.componentRef.setInput('people', people);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it("écrit chaque personne, ses sujets en lien vers leur page, le rôle et l'état", () => {
    const root = build([sophie, paul]);

    const persons = root.querySelectorAll('.radar-directory__person');
    expect(persons.length).toBe(2);
    // Interaction la plus récente d'abord.
    expect(text(persons[0].querySelector('.radar-directory__name'))).toBe('Paul Martin');
    expect(text(persons[0].querySelector('.radar-directory__job'))).toBe('Manager sécurité');
    expect(text(persons[0].querySelector('.radar-directory__count'))).toBe('2 sujets');
    expect(text(persons[0].querySelector('.radar-directory__when'))).toContain('dernier échange le 12 septembre');

    const subjects = persons[0].querySelectorAll('.radar-directory__subject');
    expect(text(subjects[0].querySelector('.radar-directory__subject-link'))).toBe('MFA prestataires');
    expect(subjects[0].querySelector('a')?.getAttribute('href')).toBe('/vigie/h1/sujets/s1');
    expect(text(subjects[0].querySelector('.radar-directory__role'))).toBe('décide');
    const state = subjects[0].querySelector('.radar-directory__state');
    expect(text(state)).toBe('nouveau');
    expect(state?.classList).toContain('radar-state--blue');
    // Le sujet clos vient après.
    expect(text(subjects[1].querySelector('.radar-directory__subject-link'))).toBe('LDAP');

    expect(persons[1].querySelector('.radar-directory__subjects')).toBeNull();
    expect(text(persons[1].querySelector('.radar-directory__count'))).toBe('0 sujet');
  });

  it('filtre, et dit quand personne ne correspond', () => {
    const root = build([sophie, paul]);
    const input = root.querySelector('input') as HTMLInputElement;

    input.value = 'mfa';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(root.querySelectorAll('.radar-directory__person').length).toBe(1);

    input.value = 'inconnu';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(text(root.querySelector('.radar-directory__none'))).toBe('Personne ne correspond.');
  });

  it('au-delà de la première page, « Afficher les k autres » révèle la suite', () => {
    const many = Array.from({ length: DIRECTORY_PAGE + 3 }, (_, i) =>
      ({ id: `p${i}`, displayName: `Personne ${String(i).padStart(3, '0')}`, jobTitle: null,
        lastInteractionAt: null, subjects: [] }) as VigiePerson);
    const root = build(many);

    expect(root.querySelectorAll('.radar-directory__person').length).toBe(DIRECTORY_PAGE);
    const more = root.querySelector('.radar-directory__more') as HTMLButtonElement;
    expect(text(more)).toBe('Afficher les 3 autres');

    more.click();
    fixture.detectChanges();
    expect(root.querySelectorAll('.radar-directory__person').length).toBe(DIRECTORY_PAGE + 3);
    expect(root.querySelector('.radar-directory__more')).toBeNull();
  });
});
