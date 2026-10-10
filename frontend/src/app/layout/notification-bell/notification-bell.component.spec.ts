import { ComponentFixture, TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { provideNoopAnimations } from '@angular/platform-browser/animations';

import { NotificationBellComponent } from './notification-bell.component';
import { NotificationCenterService, NotificationItem } from '../../core/services/notification-center.service';

describe('NotificationBellComponent (F-185 / SF-185-04)', () => {
  let fixture: ComponentFixture<NotificationBellComponent>;
  const items = signal<NotificationItem[]>([]);
  let center: {
    unread: ReturnType<typeof computed<number>>;
    items: typeof items;
    start: jasmine.Spy;
    open: jasmine.Spy;
    markAllRead: jasmine.Spy;
  };

  const item = (id: string, read: boolean): NotificationItem => ({
    id, event: 'QUESTION_ASKED', title: 'Une question vous attend', subject: 'alarm4tech',
    workspaceId: 'w-1', createdAt: new Date().toISOString(), read,
  });

  function build(list: NotificationItem[]): void {
    items.set(list);
    center = {
      unread: computed(() => items().filter((i) => !i.read).length),
      items,
      start: jasmine.createSpy('start'),
      open: jasmine.createSpy('open'),
      markAllRead: jasmine.createSpy('markAllRead'),
    };
    TestBed.configureTestingModule({
      imports: [NotificationBellComponent],
      providers: [provideNoopAnimations(), { provide: NotificationCenterService, useValue: center }],
    });
    fixture = TestBed.createComponent(NotificationBellComponent);
    fixture.detectChanges();
  }

  function openMenu(): void {
    (fixture.nativeElement.querySelector('.bell-trigger') as HTMLButtonElement).click();
    fixture.detectChanges();
  }

  function overlay(): HTMLElement {
    return document.querySelector('.cdk-overlay-container') as HTMLElement;
  }

  afterEach(() => {
    overlay()?.replaceChildren();
  });

  it('démarre le relevé et compte les non-lus dans la pastille', () => {
    build([item('a', false), item('b', false), item('c', true)]);
    expect(center.start).toHaveBeenCalled();
    const trigger = fixture.nativeElement.querySelector('.bell-trigger') as HTMLElement;
    expect(trigger.getAttribute('aria-label')).toBe('Notifications, 2 non lues');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('2');
  });

  it('au-delà de 9 non-lus, la pastille dit « 9+ »', () => {
    build(Array.from({ length: 12 }, (_, i) => item(`n${i}`, false)));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('9+');
  });

  it('liste « titre — sujet », les non-lus en gras, et ouvre au clic', () => {
    build([item('a', false), item('b', true)]);
    openMenu();
    const rows = overlay().querySelectorAll('.bell-item');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Une question vous attend — alarm4tech');
    expect(rows[0].classList).toContain('bell-item--unread');
    expect(rows[1].classList).not.toContain('bell-item--unread');

    (rows[0] as HTMLButtonElement).click();
    expect(center.open).toHaveBeenCalledWith(items()[0]);
  });

  it('« Tout marquer comme lu » quand il y a des non-lus', () => {
    build([item('a', false)]);
    openMenu();
    (overlay().querySelector('.bell-read-all') as HTMLButtonElement).click();
    expect(center.markAllRead).toHaveBeenCalled();
  });

  it('vide : « Rien ne vous attend. »', () => {
    build([]);
    openMenu();
    expect(overlay().textContent).toContain('Rien ne vous attend.');
    expect(overlay().querySelector('.bell-read-all')).toBeNull();
  });
});
