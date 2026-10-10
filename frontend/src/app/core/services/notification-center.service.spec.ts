import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';
import { Subject } from 'rxjs';

import {
  NotificationCenterService,
  NotificationItem,
  NotificationView,
  displayedTerminal,
  notificationLabel,
  relativeTime,
} from './notification-center.service';

describe('NotificationCenterService (F-185 / SF-185-04)', () => {
  let service: NotificationCenterService;
  let http: HttpTestingController;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let action: Subject<void>;
  let router: Router;

  const item = (id: string, workspaceId: string | null, read = false): NotificationItem => ({
    id,
    event: 'TURN_DONE',
    title: 'Une réponse est prête',
    subject: 'alarm4tech',
    workspaceId,
    createdAt: '2026-10-10T12:00:00Z',
    read,
  });

  const view = (...items: NotificationItem[]): NotificationView => ({
    unread: items.filter((i) => !i.read).length,
    items,
  });

  beforeEach(() => {
    action = new Subject<void>();
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    snackBar.open.and.returnValue({ onAction: () => action.asObservable() } as never);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    service = TestBed.inject(NotificationCenterService);
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
  });

  afterEach(() => {
    service.stop();
    http.verify();
  });

  function poll(v: NotificationView): void {
    service.refresh();
    http.expectOne('/api/notifications').flush(v);
  }

  it('libellés : « titre — sujet », temps relatif, terminal affiché', () => {
    expect(notificationLabel({ title: 'Une réponse est prête', subject: 'alarm4tech' }))
      .toBe('Une réponse est prête — alarm4tech');
    expect(notificationLabel({ title: 'Une réponse est prête', subject: null })).toBe('Une réponse est prête');
    const now = Date.parse('2026-10-10T12:00:00Z');
    expect(relativeTime('2026-10-10T11:59:40Z', now)).toBe("à l'instant");
    expect(relativeTime('2026-10-10T11:55:00Z', now)).toBe('il y a 5 min');
    expect(relativeTime('2026-10-10T09:00:00Z', now)).toBe('il y a 3 h');
    expect(relativeTime('2026-10-09T09:00:00Z', now)).toBe('hier');
    expect(relativeTime('2026-10-06T09:00:00Z', now)).toBe('il y a 4 j');
    expect(displayedTerminal('/atelier/w-1?x=1')).toBe('w-1');
    expect(displayedTerminal('/atelier/w-1/fichiers')).toBe('w-1');
    expect(displayedTerminal('/forge')).toBeNull();
  });

  it('le premier relevé alimente la cloche sans bandeau', () => {
    poll(view(item('n1', 'w-1')));
    expect(service.unread()).toBe(1);
    expect(service.items().length).toBe(1);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('une nouveauté d\'un autre terminal se dit dans un bandeau, avec [Ouvrir]', () => {
    poll(view());
    poll(view(item('n2', 'w-2')));
    expect(snackBar.open).toHaveBeenCalledWith('Une réponse est prête — alarm4tech', 'Ouvrir', jasmine.any(Object));

    const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);
    action.next();
    http.expectOne('/api/notifications/n2/read').flush(null);
    expect(navigate).toHaveBeenCalledWith('/atelier/w-2');
  });

  it('pas de bandeau pour le terminal affiché, ni pour une notification déjà lue', async () => {
    spyOnProperty(router, 'url', 'get').and.returnValue('/atelier/w-1');
    poll(view());
    poll(view(item('n3', 'w-1'), item('n4', 'w-3', true)));
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('ouvrir une notification la marque lue et ouvre son terminal', () => {
    poll(view(item('n5', 'w-5')));
    const navigate = spyOn(router, 'navigateByUrl').and.resolveTo(true);

    service.open(service.items()[0]);

    http.expectOne('/api/notifications/n5/read').flush(null);
    expect(service.unread()).toBe(0);
    expect(service.items()[0].read).toBeTrue();
    expect(navigate).toHaveBeenCalledWith('/atelier/w-5');
  });

  it('tout marquer comme lu', () => {
    poll(view(item('n6', 'w-1'), item('n7', 'w-2')));
    service.markAllRead();
    http.expectOne('/api/notifications/read-all').flush(null);
    expect(service.unread()).toBe(0);
    expect(service.items().every((i) => i.read)).toBeTrue();
  });

  it('un relevé en échec garde le dernier état, sans rien dire', () => {
    poll(view(item('n8', 'w-1')));
    service.refresh();
    http.expectOne('/api/notifications').flush('boom', { status: 500, statusText: 'KO' });
    expect(service.unread()).toBe(1);
    expect(snackBar.open).not.toHaveBeenCalled();
  });
});
