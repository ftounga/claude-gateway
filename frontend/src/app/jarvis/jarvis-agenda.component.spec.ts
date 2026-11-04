import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { JarvisAgendaComponent } from './jarvis-agenda.component';
import {
  CalendarItem, CalendarWindow, JarvisCalendarClient, agendaDays, agendaPieceView, friseSlot, nextItem, parisDay,
  parisHour,
} from './jarvis-calendar';

function item(over: Partial<CalendarItem> = {}): CalendarItem {
  return {
    id: 'e1', feedLabel: 'Pro', uid: 'u1', start: '2026-11-04T13:00:00Z', end: '2026-11-04T14:00:00Z', allDay: false,
    title: 'Point devis Helios', location: 'Visio', status: 'CONFIRMED', ...over,
  };
}

function win(items: CalendarItem[]): CalendarWindow {
  return { from: '2026-11-03T00:00:00Z', to: '2026-11-06T00:00:00Z', items, updatedAt: '2026-11-04T08:15:00Z', feeds: 2 };
}

describe('jarvis-calendar (agenda, fonctions)', () => {
  it('lit la vue d’un ordre d’écran, et rien d’autre', () => {
    expect(agendaPieceView(['agenda:jour:2026-11-04'])).toEqual({ mode: 'jour', date: '2026-11-04' });
    expect(agendaPieceView(['dep:1', 'agenda:semaine:2026-11-02'])).toEqual({ mode: 'semaine', date: '2026-11-02' });
    expect(agendaPieceView(['agenda:mois:2026-11-04'])).toBeNull();
    expect(agendaPieceView(['courriel:x'])).toBeNull();
    expect(agendaPieceView(null)).toBeNull();
  });

  it('une semaine va jusqu’au dimanche', () => {
    expect(agendaDays({ mode: 'semaine', date: '2026-11-04' })).toEqual(
      ['2026-11-04', '2026-11-05', '2026-11-06', '2026-11-07', '2026-11-08']);
    expect(agendaDays({ mode: 'jour', date: '2026-11-04' })).toEqual(['2026-11-04']);
  });

  it('heure et journée de Paris, à travers le changement d’heure', () => {
    expect(parisHour('2026-10-26T08:00:00Z')).toBe('09:00');
    expect(parisHour('2026-10-19T07:00:00Z')).toBe('09:00');
    expect(parisDay('2026-11-04T23:30:00Z')).toBe('2026-11-05');
  });

  it('le prochain : celui en cours d’abord, jamais une journée entière', () => {
    const items = [
      item({ id: 'a', start: '2026-11-04T08:00:00Z', end: '2026-11-04T09:00:00Z' }),
      item({ id: 'b', start: '2026-11-04T13:00:00Z', end: '2026-11-04T14:00:00Z' }),
      item({ id: 'c', allDay: true, start: '2026-11-03T23:00:00Z', end: '2026-11-04T23:00:00Z' }),
    ];
    expect(nextItem(items, new Date('2026-11-04T08:30:00Z'))?.id).toBe('a');
    expect(nextItem(items, new Date('2026-11-04T10:00:00Z'))?.id).toBe('b');
    expect(nextItem(items, new Date('2026-11-04T15:00:00Z'))).toBeNull();
  });

  it('la frise place un rendez-vous dans ses bornes', () => {
    const slot = friseSlot(item(), '2026-11-04', 7 * 60, 21 * 60);
    expect(slot.left).toBeCloseTo(((14 - 7) / 14) * 100, 1);
    expect(slot.width).toBeCloseTo((1 / 14) * 100, 1);
    const late = friseSlot(item({ start: '2026-11-04T21:30:00Z', end: '2026-11-04T22:30:00Z' }), '2026-11-04', 420, 1260);
    expect(late.left + late.width).toBeLessThanOrEqual(101.5);
  });
});

describe('JarvisAgendaComponent (F-243 / SF-243-02)', () => {
  let client: jasmine.SpyObj<JarvisCalendarClient>;
  let fixture: ComponentFixture<JarvisAgendaComponent>;

  function create(mode: 'jour' | 'semaine', items: CalendarItem[], now = '2026-11-04T09:00:00Z'): HTMLElement {
    client.events.and.returnValue(of(win(items)));
    fixture = TestBed.createComponent(JarvisAgendaComponent);
    fixture.componentRef.setInput('view', { mode, date: '2026-11-04' });
    fixture.componentRef.setInput('now', () => new Date(now));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    client = jasmine.createSpyObj<JarvisCalendarClient>('JarvisCalendarClient', ['events']);
    TestBed.configureTestingModule({
      imports: [JarvisAgendaComponent],
      providers: [{ provide: JarvisCalendarClient, useValue: client }],
    });
  });

  it('la journée : le prochain mis en avant avec son mot, « à jour à », la frise', () => {
    const el = create('jour', [
      item({ id: 'a', title: 'Point hebdo', start: '2026-11-04T07:00:00Z', end: '2026-11-04T07:30:00Z' }),
      item({ id: 'b' }),
    ]);
    const rows = el.querySelectorAll('.jag__item');
    expect(rows.length).toBe(2);
    expect(rows[1].classList).toContain('jag__item--next');
    expect(rows[1].textContent).toContain('14:00–15:00');
    expect(rows[1].textContent).toContain('prochain');
    expect(el.querySelectorAll('.jag__block--next').length).toBe(1);
    expect(el.querySelector('.jag__meta')?.textContent).toContain('à jour à 09:15');
    // La lecture couvre la journée avec une marge.
    const [from, to] = client.events.calls.mostRecent().args;
    expect(from < '2026-11-04T00:00:00Z').toBeTrue();
    expect(to > '2026-11-05T00:00:00Z').toBeTrue();
  });

  it('la semaine : une ligne par jour, journée entière et jour vide', () => {
    const el = create('semaine', [
      item({ id: 'b' }),
      item({ id: 'c', allDay: true, title: 'Séminaire', start: '2026-11-05T23:00:00Z', end: '2026-11-06T23:00:00Z' }),
    ]);
    expect(el.querySelectorAll('.jag__day').length).toBe(5);
    expect(el.querySelector('.jag__title')?.textContent).toContain('Cette semaine');
    expect(el.querySelectorAll('.jag__allday')[0].textContent).toContain('Séminaire');
    expect(el.querySelectorAll('.jag__empty').length).toBe(3);
  });

  it('F-243 / SF-243-04 — un rendez-vous proposé s’affiche en pointillé, jamais comme le prochain', () => {
    client.events.and.returnValue(of({ ...win([item({ id: 'b' })]),
      proposals: [item({ id: 'p1', title: 'Point Helios proposé', start: '2026-11-04T10:00:00Z',
        end: '2026-11-04T11:00:00Z', status: 'PROPOSED', feedLabel: 'proposé' })] }));
    fixture = TestBed.createComponent(JarvisAgendaComponent);
    fixture.componentRef.setInput('view', { mode: 'jour', date: '2026-11-04' });
    fixture.componentRef.setInput('now', () => new Date('2026-11-04T09:00:00Z'));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const proposed = el.querySelector('.jag__item--proposed');
    expect(proposed?.textContent).toContain('Point Helios proposé');
    expect(proposed?.textContent).toContain('proposé');
    expect(el.querySelectorAll('.jag__block--proposed').length).toBe(1);
    expect(el.querySelector('.jag__item--next')?.textContent).toContain('Point devis Helios');
  });

  it('une journée vide le dit', () => {
    const el = create('jour', []);
    expect(el.querySelector('.jag__empty')?.textContent).toContain('Rien de prévu');
  });

  it('à 360 px, rien ne déborde horizontalement', () => {
    const el = create('jour', [item({ title: 'Un titre de rendez-vous extrêmement long '.repeat(4) })]);
    const panel = el.querySelector('.jag') as HTMLElement;
    panel.style.position = 'static';
    panel.style.width = '360px';
    fixture.detectChanges();
    expect(panel.scrollWidth).toBeLessThanOrEqual(panel.clientWidth + 1);
  });
});
