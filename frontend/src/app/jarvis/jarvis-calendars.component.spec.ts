import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { CalendarFeed, JarvisCalendarClient, calendarErrorMessage, calendarStatusLabel } from './jarvis-calendar';
import { JarvisCalendarsComponent } from './jarvis-calendars.component';

function feed(over: Partial<CalendarFeed> = {}): CalendarFeed {
  return {
    id: 'f1', label: 'Pro', status: 'OK', secretLast4: 'a1b2', eventCount: 12, lastOkAt: '2026-11-04T08:15:00Z',
    lastError: null, createdAt: '2026-11-04T08:00:00Z', ...over,
  };
}

describe('jarvis-calendar (fonctions)', () => {
  it('libellés d’état et messages d’erreur', () => {
    expect(calendarStatusLabel('OK')).toBe('Lu');
    expect(calendarStatusLabel('BROKEN')).toBe('Adresse refusée');
    expect(calendarErrorMessage({ error: { message: 'Google ne reconnaît pas cette adresse' } })).toContain('Google');
    expect(calendarErrorMessage({ status: 503 })).toContain('ne répond pas');
    expect(calendarErrorMessage(null)).toContain('pas pu');
  });
});

describe('JarvisCalendarsComponent (F-243 / SF-243-01)', () => {
  let client: jasmine.SpyObj<JarvisCalendarClient>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snack: jasmine.SpyObj<MatSnackBar>;
  let fixture: ComponentFixture<JarvisCalendarsComponent>;

  function create(list: CalendarFeed[] = []): HTMLElement {
    client.list.and.returnValue(of(list));
    fixture = TestBed.createComponent(JarvisCalendarsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    client = jasmine.createSpyObj<JarvisCalendarClient>('JarvisCalendarClient', ['list', 'link', 'test', 'remove']);
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snack = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    TestBed.configureTestingModule({
      imports: [JarvisCalendarsComponent],
      providers: [
        provideNoopAnimations(),
        { provide: JarvisCalendarClient, useValue: client },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snack },
      ],
    });
  });

  it('liste les agendas avec état, …last4, nombre de rendez-vous et « à jour à », jamais l’adresse', () => {
    const el = create([feed(), feed({ id: 'f2', label: 'Perso', status: 'BROKEN',
      lastError: 'Google ne reconnaît plus cette adresse (réinitialisée ?).' })]);
    const items = el.querySelectorAll('.jc__feed');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('…a1b2');
    expect(items[0].textContent).toContain('12 rendez-vous');
    expect(items[0].textContent).toContain('à jour à');
    expect(items[1].querySelector('.jc__status--ko')?.textContent).toContain('Adresse refusée');
    expect(items[1].textContent).toContain('réinitialisée');
    expect(el.textContent).not.toContain('calendar.google.com/calendar/ical');
  });

  it('l’adresse se saisit masquée (c’est un secret) et le pas-à-pas renvoie à l’aide Google', () => {
    const el = create([]);
    expect((el.querySelector('.jc__url') as HTMLInputElement).type).toBe('password');
    expect((el.querySelector('.jc__link') as HTMLAnchorElement).href).toContain('support.google.com/calendar/answer/37648');
    expect(el.querySelectorAll('.jc__steps li').length).toBe(3);
  });

  it('ajouter : envoie adresse et libellé, vide le champ, recharge', () => {
    const el = create([]);
    client.link.and.returnValue(of(feed()));
    const url = el.querySelector('.jc__url') as HTMLInputElement;
    url.value = ' https://calendar.google.com/calendar/ical/x/private-abc/basic.ics ';
    client.list.and.returnValue(of([feed()]));
    fixture.componentInstance.link(url, 'Pro');
    fixture.detectChanges();
    expect(client.link).toHaveBeenCalledWith('https://calendar.google.com/calendar/ical/x/private-abc/basic.ics', 'Pro');
    expect(url.value).toBe('');
    expect(el.querySelectorAll('.jc__feed').length).toBe(1);
  });

  it('un refus s’affiche clairement, et le champ est vidé', () => {
    const el = create([]);
    client.link.and.returnValue(throwError(() => ({ status: 400,
      error: { error: 'calendar_feed_gone', message: 'Google ne reconnaît pas cette adresse.' } })));
    const url = el.querySelector('.jc__url') as HTMLInputElement;
    url.value = 'https://calendar.google.com/calendar/ical/x/basic.ics';
    fixture.componentInstance.link(url, '');
    fixture.detectChanges();
    expect(el.querySelector('.jc__error')?.textContent).toContain('Google ne reconnaît pas');
    expect(url.value).toBe('');
  });

  it('champ vide : rien n’est envoyé', () => {
    create([]);
    fixture.componentInstance.link(document.createElement('input'), 'Pro');
    expect(client.link).not.toHaveBeenCalled();
  });

  it('retirer passe par une confirmation', () => {
    create([feed()]);
    client.remove.and.returnValue(of(undefined));
    dialog.open.and.returnValue({ afterClosed: () => of(false) } as never);
    fixture.componentInstance.remove(feed());
    expect(client.remove).not.toHaveBeenCalled();
    dialog.open.and.returnValue({ afterClosed: () => of(true) } as never);
    fixture.componentInstance.remove(feed());
    expect(client.remove).toHaveBeenCalledWith('f1');
    expect(fixture.componentInstance.feeds().length).toBe(0);
  });

  it('tester remplace la ligne par l’état rendu', () => {
    create([feed()]);
    client.test.and.returnValue(of(feed({ status: 'BROKEN', lastError: 'refusée' })));
    fixture.componentInstance.test(feed());
    expect(fixture.componentInstance.feeds()[0].status).toBe('BROKEN');
  });
});
