import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { RadarNews } from '../../core/models/radar.models';
import { RadarService } from '../../core/services/radar.service';
import { RadarNewsComponent } from './radar-news.component';

/** Donner la nouvelle (F-104 / SF-104-02). */
describe('RadarNewsComponent', () => {
  let fixture: ComponentFixture<RadarNewsComponent>;
  let component: RadarNewsComponent;
  let radar: jasmine.SpyObj<RadarService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let changed: number;

  const written: RadarNews = {
    understanding: 'Je note : pilote MFA en octobre ; sujet LDAP clos.',
    changes: [
      { kind: 'SET_NEXT_STEP', subjectId: 's1', subjectName: 'MFA', correctionId: 'c1',
        sentence: 'Prochaine étape de « MFA » : Pilote en octobre' },
      { kind: 'CLOSE', subjectId: 's2', subjectName: 'LDAP', correctionId: 'c2', sentence: 'Sujet « LDAP » clos' },
    ],
    evidenceId: 'e1', source: 'USER_NOTE', mail: null, stoppedEarly: false,
  };

  function build(): HTMLElement {
    radar = jasmine.createSpyObj<RadarService>('RadarService', ['giveNews', 'undoNews']);
    radar.giveNews.and.returnValue(of(written));
    radar.undoNews.and.returnValue(of({ evidenceId: 'e1', undone: 2 }));
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [RadarNewsComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: RadarService, useValue: radar },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(RadarNewsComponent);
    component = fixture.componentInstance;
    changed = 0;
    component.changed.subscribe(() => changed++);
    fixture.componentRef.setInput('hostId', 'h1');
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const text = (el: Element | null) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  it('vide : le bouton est désactivé ; un courriel collé est annoncé avant l\'envoi', () => {
    const root = build();
    const send = root.querySelector('.radar-news__send') as HTMLButtonElement;
    expect(send.disabled).toBeTrue();

    component.text.set('De : Sophie\nEnvoyé : lundi 9 septembre 2026 14:32\n\nBonjour');
    fixture.detectChanges();
    expect(text(root.querySelector('.radar-news__hint--mail'))).toContain('Courriel reconnu');
    expect(send.disabled).toBeFalse();
  });

  it("donner la nouvelle : ce que le Radar a compris, les changements, le champ vidé, l'onglet relu", () => {
    const root = build();
    component.text.set('  Paul m\'a dit que le pilote MFA glisse à octobre.  ');
    component.give();
    fixture.detectChanges();

    expect(radar.giveNews).toHaveBeenCalledOnceWith('h1', 'Paul m\'a dit que le pilote MFA glisse à octobre.');
    expect(text(root.querySelector('.radar-news__understood'))).toBe(written.understanding);
    const links = Array.from(root.querySelectorAll('.radar-news__changes a')) as HTMLAnchorElement[];
    expect(links.length).toBe(2);
    expect(links[0].getAttribute('href')).toBe('/vigie/h1/sujets/s1');
    expect(component.text()).toBe('');
    expect(changed).toBe(1);
  });

  it('annuler la nouvelle : tout est défait, la réponse disparaît, l\'onglet relu', () => {
    const root = build();
    component.text.set('le sujet LDAP est clos');
    component.give();
    fixture.detectChanges();

    const undo = Array.from(root.querySelectorAll('.radar-news__answer-actions button'))
      .find((b) => text(b).includes('Annuler cette nouvelle')) as HTMLButtonElement;
    undo.click();
    fixture.detectChanges();

    expect(radar.undoNews).toHaveBeenCalledOnceWith('h1', 'e1');
    expect(root.querySelector('.radar-news__answer')).toBeNull();
    expect(changed).toBe(2);
    expect(snackBar.open).toHaveBeenCalledWith('Nouvelle annulée : le Radar a tout défait.', 'Fermer', jasmine.any(Object));
  });

  it('courriel collé : la mention du courriel ; rien à noter : pas de bouton Annuler, onglet non relu', () => {
    const root = build();
    radar.giveNews.and.returnValue(of({
      understanding: 'Rien à noter : aucun sujet du Radar n\'est concerné.', changes: [], evidenceId: null,
      source: 'PASTED_MAIL', mail: { sender: 'Julie Martin', sentAt: '2026-09-09T12:32:00Z', subject: 'SSO', datedFromMail: true },
      stoppedEarly: false,
    }));
    component.text.set('De : Julie\nEnvoyé : 9/9/2026 14:32\n\nMerci');
    component.give();
    fixture.detectChanges();

    expect(text(root.querySelector('.radar-news__mail'))).toContain('Courriel de Julie Martin');
    expect(text(root.querySelector('.radar-news__answer-actions'))).not.toContain('Annuler cette nouvelle');
    expect(changed).toBe(0);
  });

  it('402 et 503 disent leur cause ; le texte est gardé', () => {
    const root = build();
    radar.giveNews.and.returnValue(throwError(() => new HttpErrorResponse({ status: 402 })));
    component.text.set('le MFA avance');
    component.give();
    fixture.detectChanges();
    expect(text(root.querySelector('.radar-news__error'))).toContain('quota');
    expect(component.text()).toBe('le MFA avance');

    radar.giveNews.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    component.give();
    fixture.detectChanges();
    expect(text(root.querySelector('.radar-news__error'))).toContain('indisponible');
  });
});
