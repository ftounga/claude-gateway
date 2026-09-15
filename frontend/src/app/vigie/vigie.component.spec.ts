import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, ParamMap, Router, convertToParamMap, provideRouter } from '@angular/router';
import { BehaviorSubject, of, throwError } from 'rxjs';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';

import { RunnerHost, RunnerHostOverview, WorkspaceDetail } from '../core/models/atelier.models';
import { VigieRadarCounts } from '../core/models/vigie.models';
import { RadarBrief } from '../core/models/radar.models';
import { AtelierService } from '../core/services/atelier.service';
import { RadarService } from '../core/services/radar.service';
import { MailService } from '../core/services/mail.service';
import { EMPTY } from 'rxjs';
import { VigieService } from '../core/services/vigie.service';
import { PagesService } from '../core/services/pages.service';
import { ExportService } from '../core/services/export.service';
import { TeamsLink, TeamsLinkService } from '../atelier/teams/teams-link.service';
import { RunnerPairingDialogComponent } from '../atelier/runner/runner-pairing-dialog.component';
import { AddClientDialogComponent } from './add-client-dialog/add-client-dialog.component';
import { RemoveClientDialogComponent } from './remove-client-dialog/remove-client-dialog.component';
import { CloseMissionDialogComponent } from './close-mission-dialog/close-mission-dialog.component';
import { RadarExporter } from './radar-export/radar-export';
import { RadarVerificationDialogComponent } from './radar-verification/radar-verification-dialog.component';
import { VIGIE_REFRESH_MS, VigieComponent } from './vigie.component';

/** La Vigie, l'écran (F-106 / SF-106-02). */
describe('VigieComponent', () => {
  let fixture: ComponentFixture<VigieComponent>;
  let component: VigieComponent;
  let atelier: jasmine.SpyObj<AtelierService>;
  let vigie: jasmine.SpyObj<VigieService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let teamsLinks: jasmine.SpyObj<TeamsLinkService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;
  let radar: jasmine.SpyObj<RadarService>;
  let exporter: jasmine.SpyObj<RadarExporter>;
  let pages: jasmine.SpyObj<PagesService>;
  let router: Router;
  let params$: BehaviorSubject<ParamMap>;
  let query$: BehaviorSubject<ParamMap>;
  /** Ce que rend chaque dialogue, par composant. */
  let dialogResults: Map<unknown, unknown>;

  const client = (id: string, name: string, extra: Partial<RunnerHostOverview> = {}): RunnerHostOverview => ({
    id, name, connected: true, activeProjects: 0, createdAt: '2026-09-10T08:00:00Z',
    lastSeenAt: new Date().toISOString(), missionStatus: 'ACTIVE', spaces: ['FORGE', 'VIGIE'],
    projects: [{ id: `${id}-w`, name: 'projet-secret', calls: 0, active: false }],
    ...extra,
  });

  const linked: TeamsLink = {
    state: 'LINKED', label: 'Teams relié', sentence: '', remedy: '', browser: 'Chrome', healthVerdict: '',
    recognizedFields: 5, expectedFields: 5, missingFields: [], observedApiVersions: [], conclusive: true,
  };

  const noCounts: VigieRadarCounts = { followUpsDue: 0, blockedSubjects: 0, lastSync: null };

  const emptyBrief: RadarBrief = {
    generatedAt: '2026-09-15T07:00:00Z', since: '2026-09-14T07:00:00Z', sentences: [],
    counts: { toDoByMe: 0, followUpsDue: 0, introductions: 0, subjectsFollowed: 0, blockedSubjects: 0, toHandle: 0 },
    running: null, lastSync: null, coverageComplete: false,
    coverageWarning: 'Aucune synchro encore : le Radar se remplira à la première synchro du soir.', coverageLines: [],
  };

  const briefOf = (counts: VigieRadarCounts): RadarBrief => ({
    ...emptyBrief,
    counts: { ...emptyBrief.counts, followUpsDue: counts.followUpsDue, blockedSubjects: counts.blockedSubjects,
      toHandle: counts.toHandle ?? 0 },
    running: counts.lastSync?.status === 'RUNNING' ? { ...counts.lastSync, trigger: 'SCHEDULED', scheduledFor: null, summary: null } : null,
    lastSync: counts.lastSync && counts.lastSync.status !== 'RUNNING'
      ? { ...counts.lastSync, trigger: 'SCHEDULED', scheduledFor: null, summary: null } : null,
  });

  function build(options: {
    hosts?: RunnerHostOverview[];
    entitled?: boolean;
    hostRef?: string | null;
    tab?: string | null;
    counts?: Record<string, VigieRadarCounts>;
  } = {}): HTMLElement {
    atelier = jasmine.createSpyObj<AtelierService>('AtelierService',
      ['teamsAccess', 'runnerHostsOverview', 'openTeamsTerminal', 'setHostMissionStatus']);
    atelier.setHostMissionStatus.and.returnValue(of({ id: 'h1', missionStatus: 'CLOSED' } as unknown as RunnerHost));
    exporter = jasmine.createSpyObj<RadarExporter>('RadarExporter', ['download']);
    atelier.openTeamsTerminal.and.returnValue(of({ id: 'wtt1', name: 'Terminal Teams' } as WorkspaceDetail));
    teamsLinks = jasmine.createSpyObj<TeamsLinkService>('TeamsLinkService', ['getLink']);
    teamsLinks.getLink.and.returnValue(of(linked));
    atelier.teamsAccess.and.returnValue(of({ entitled: options.entitled ?? true }));
    atelier.runnerHostsOverview.and.returnValue(of(options.hosts ?? [client('h1', 'EDENRED')]));
    vigie = jasmine.createSpyObj<VigieService>('VigieService',
      ['radarCounts', 'people', 'activate', 'remove', 'purgeRadar', 'hostSpaces', 'readiness']);
    vigie.readiness.and.returnValue(of({ checks: [], canStart: false, teamsSignInRequired: false }));
    vigie.radarCounts.and.callFake((hostId: string) => of(options.counts?.[hostId] ?? noCounts));
    vigie.people.and.returnValue(of([]));
    vigie.activate.and.returnValue(of({ hostId: 'h1', name: 'EDENRED', missionStatus: 'ACTIVE', spaces: ['FORGE', 'VIGIE'] }));
    vigie.remove.and.returnValue(of({ hostId: 'h1', name: 'EDENRED', missionStatus: 'ACTIVE', spaces: ['FORGE'] }));
    vigie.purgeRadar.and.returnValue(of({}));
    pages = jasmine.createSpyObj<PagesService>('PagesService', ['list', 'removePlace']);
    pages.list.and.returnValue(of([]));
    pages.removePlace.and.returnValue(of(undefined));
    dialogResults = new Map();
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    dialog.open.and.callFake(((component: unknown) =>
      ({ afterClosed: () => of(dialogResults.get(component)) })) as never);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    radar = jasmine.createSpyObj<RadarService>('RadarService',
      ['brief', 'syncNow', 'cancelSync', 'threadRules', 'addThreadRule', 'removeThreadRule', 'board', 'schedule']);
    // Le résumé d'un client dit les mêmes compteurs que la lecture de la Vigie : c'est la même source.
    radar.brief.and.callFake((hostId: string) => of(briefOf(options.counts?.[hostId] ?? noCounts)));
    radar.board.and.returnValue(of({ toDo: [], subjects: [], waiting: [] }));
    radar.schedule.and.returnValue(of({ enabled: true, clientAuthorizedAt: '2026-09-10T08:00:00Z', syncTime: '22:00',
      timeZone: 'Europe/Paris', nextSyncAt: null, missedSlotAt: null, running: null }));
    params$ = new BehaviorSubject(convertToParamMap(options.hostRef ? { hostRef: options.hostRef } : {}));
    query$ = new BehaviorSubject(convertToParamMap(options.tab ? { onglet: options.tab } : {}));

    TestBed.configureTestingModule({
      imports: [VigieComponent],
      providers: [
        provideRouter([]),
        provideNoopAnimations(),
        { provide: AtelierService, useValue: atelier },
        { provide: VigieService, useValue: vigie },
        { provide: TeamsLinkService, useValue: teamsLinks },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
        { provide: RadarService, useValue: radar },
        // F-110 / SF-110-01 : la ligne des courriels du client se tait ici (lecture sans réponse).
        { provide: MailService, useValue: jasmine.createSpyObj<MailService>('MailService', { address: EMPTY }) },
        { provide: RadarExporter, useValue: exporter },
        { provide: PagesService, useValue: pages },
        { provide: ExportService, useValue: jasmine.createSpyObj<ExportService>('ExportService', ['triggerDownload']) },
        { provide: ActivatedRoute, useValue: { snapshot: {}, paramMap: params$, queryParamMap: query$ } },
      ],
    });
    router = TestBed.inject(Router);
    spyOn(router, 'navigate').and.resolveTo(true);
    fixture = TestBed.createComponent(VigieComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  /** Le texte du bandeau, espaces insécables ramenés à des espaces. */
  const fleetText = (root: HTMLElement) =>
    (root.querySelector('.forge-fleet')?.textContent ?? '').replace(/\u00a0/g, ' ');

  it("sans droit : l'encart de la Vigie, et aucune lecture des clients", () => {
    const root = build({ entitled: false });

    const pitch = root.querySelector('.vigie__not-entitled app-space-pitch, app-space-pitch.vigie__not-entitled');
    expect(pitch?.textContent).toContain('La Vigie');
    expect(pitch?.textContent).toContain('Essai de deux semaines');
    expect(root.querySelector('.forge-fleet__refresh')).toBeNull();
    expect(atelier.runnerHostsOverview).not.toHaveBeenCalled();
    expect(root.querySelector('app-forge-rail')).toBeNull();
  });

  it('lit la vue de la Vigie, et montre la colonne de la Forge avec les mots de la Vigie', () => {
    const root = build({ hosts: [client('h1', 'EDENRED'), client('h2', 'FREE', { connected: false })] });

    expect(atelier.runnerHostsOverview).toHaveBeenCalledWith('VIGIE');
    const rail = root.querySelector('app-forge-rail') as HTMLElement;
    expect(rail.querySelector('aside')?.getAttribute('aria-label')).toBe('Clients');
    expect(rail.querySelector<HTMLInputElement>('input')?.placeholder).toBe('Filtrer les clients');
    expect(rail.querySelector('.forge-rail__count')).toBeNull();
    expect(root.querySelector('.forge-fleet__title')?.textContent).toContain('Vigie');
    expect(fleetText(root)).toContain('1 client en ligne sur 2');
    // Un seul client ouvert.
    expect(root.querySelectorAll('.vigie__client').length).toBe(1);
  });

  it('le filtre ne retient pas un client par le nom de ses projets', () => {
    build({ hosts: [client('h1', 'EDENRED')] });

    component.filter.set('projet-secret');

    expect(component.groups().length).toBe(0);
  });

  it('dit relances dues, sujets bloqués et dernière synchro ; les relances font « À regarder »', () => {
    const root = build({
      hosts: [client('h1', 'EDENRED'), client('h2', 'FREE')],
      counts: {
        h2: { followUpsDue: 2, blockedSubjects: 1,
          lastSync: { id: 's', status: 'SUCCEEDED', startedAt: '2026-09-12T20:00:00Z', finishedAt: '2026-09-12T20:10:00Z' } },
      },
    });

    const fleet = fleetText(root);
    expect(fleet).toContain('2 relances dues');
    expect(fleet).toContain('1 sujet bloqué');
    expect(root.querySelector('.vigie__sync')?.textContent).toContain('synchro');
    expect(component.groups()[0].key).toBe('attention');
    expect(component.groups()[0].rows[0].ref).toBe('h2');
    expect(root.querySelector('.forge-rail__flag')?.textContent?.trim()).toBe('2 relances');
    // Sans client désigné, la Vigie ouvre ce qui attend.
    expect(component.selectedRef()).toBe('h2');
  });

  it('« à traiter » sur l\'onglet Radar et dans le bandeau ; un résumé relu les met à jour (F-102 / SF-102-03)', () => {
    const root = build({
      hosts: [client('h1', 'EDENRED'), client('h2', 'FREE')],
      hostRef: 'h1',
      counts: {
        h1: { followUpsDue: 0, blockedSubjects: 0, toHandle: 5, lastSync: null },
        h2: { followUpsDue: 0, blockedSubjects: 0, toHandle: 2, lastSync: null },
      },
    });

    const radarTab = () => root.querySelector('.poste__tab[data-tab="radar"]') as HTMLElement;
    expect(radarTab().querySelector('.poste__tab-flag')?.textContent?.trim()).toBe('5 à traiter');
    expect(fleetText(root)).toContain('7 à traiter');

    component.onBrief('h1', { ...emptyBrief,
      counts: { ...emptyBrief.counts, toHandle: 1, followUpsDue: 1 },
      lastSync: { id: 'y1', status: 'SUCCEEDED', startedAt: '2026-09-14T20:00:00Z', finishedAt: '2026-09-14T20:30:00Z',
        trigger: 'SCHEDULED', scheduledFor: null, summary: null } });
    fixture.detectChanges();
    expect(radarTab().querySelector('.poste__tab-flag')?.textContent?.trim()).toBe('1 à traiter');
    expect(fleetText(root)).toContain('3 à traiter');
    expect(fleetText(root)).toContain('1 relance due');
    expect(component.radarCounts()['h1'].lastSync?.id).toBe('y1');

    component.onBrief('h1', emptyBrief);
    fixture.detectChanges();
    expect(radarTab().querySelector('.poste__tab-flag')).toBeNull();
  });

  it('ouvre le client désigné par l’URL, et le client par défaut sinon', () => {
    build({ hosts: [client('h1', 'EDENRED'), client('h2', 'FREE')], hostRef: 'h2' });
    expect(component.selectedHost()?.name).toBe('FREE');

    params$.next(convertToParamMap({ hostRef: 'inconnu' }));
    expect(component.selectedRef()).toBe('h1');
  });

  it('change de client en gardant l’onglet', () => {
    build({ hosts: [client('h1', 'EDENRED'), client('h2', 'FREE')] });

    component.selectHost(component.groups()[0].rows[1]);

    expect(router.navigate).toHaveBeenCalledWith(['/vigie', 'h2'], { queryParamsHandling: 'preserve' });
  });

  it("montre cinq onglets (Pages : F-109), le Radar par défaut avec l'onglet du Radar du client (F-102)", () => {
    const root = build();

    expect(Array.from(root.querySelectorAll('.poste__tab')).map((t) => t.textContent?.trim()))
      .toEqual(['Radar', 'Conversations', 'Réunions', 'Personnes', 'Pages']);
    expect(component.activeTab()).toBe('radar');
    expect(root.querySelector('.vigie__radar-empty')).toBeNull();
    expect(root.querySelector('app-radar-board')).not.toBeNull();
    expect(radar.brief).toHaveBeenCalledWith('h1');
    expect(radar.board).toHaveBeenCalledWith('h1');
  });

  it("?onglet=personnes lit l'annuaire une fois ; vide, il dit comment il se remplit", () => {
    const root = build({ tab: 'personnes' });

    expect(vigie.people).toHaveBeenCalledOnceWith('h1');
    expect(root.querySelector('.vigie__people-empty')?.textContent).toContain("l'annuaire se remplit");
    component.refresh();
    expect(vigie.people).toHaveBeenCalledTimes(2);
  });

  it("écrit chaque personne de l'annuaire, et dit un annuaire illisible", () => {
    const root = build({ tab: 'personnes' });
    component.people.set({ h1: [{ id: 'p1', displayName: 'Paul Martin', jobTitle: 'DSI',
      lastInteractionAt: '2026-09-12T10:00:00Z',
      subjects: [{ subjectId: 's1', subjectName: 'MFA', state: 'ADVANCING', role: 'DECIDER' }] }] });
    fixture.detectChanges();

    // F-103 / SF-103-04 : l'onglet délègue à l'annuaire, qui mène à la page du sujet.
    expect(root.querySelector('app-radar-directory.vigie__people')).not.toBeNull();
    const person = root.querySelector('.radar-directory__person')?.textContent ?? '';
    expect(person).toContain('Paul Martin');
    expect(person).toContain('DSI');
    expect(person).toContain('1 sujet');
    expect(root.querySelector('.radar-directory__subject-link')?.getAttribute('href')).toBe('/vigie/h1/sujets/s1');

    component.people.set({ h1: 'error' });
    fixture.detectChanges();
    expect(root.querySelector('.vigie__people-error')?.textContent).toContain("n'a pas pu être lu");
  });

  it("un clic d'onglet met l'URL à jour", () => {
    build();

    component.selectTab('reunions');

    expect(router.navigate).toHaveBeenCalledWith([], jasmine.objectContaining({
      queryParams: { onglet: 'reunions' }, queryParamsHandling: 'merge',
    }));
  });

  it('aucun client : l’encart propose d’activer ou de connecter', () => {
    const root = build({ hosts: [] });

    expect(root.querySelector('.vigie__empty')?.textContent).toContain('Aucun client dans la Vigie');
  });

  it("sans accès Forge : l'encart d'accès, et le sondage n'est pas armé", fakeAsync(() => {
    const root = build();
    atelier.runnerHostsOverview.and.returnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    component.refresh();
    fixture.detectChanges();

    expect(root.querySelector('.vigie__forbidden')).not.toBeNull();
    atelier.runnerHostsOverview.calls.reset();
    tick(VIGIE_REFRESH_MS * 2);
    expect(atelier.runnerHostsOverview).not.toHaveBeenCalled();
    discardPeriodicTasks();
  }));

  it('le sondage relit la vue, jamais les compteurs du Radar', fakeAsync(() => {
    build();
    expect(vigie.radarCounts).toHaveBeenCalledTimes(1);

    tick(VIGIE_REFRESH_MS);

    expect(atelier.runnerHostsOverview).toHaveBeenCalledTimes(2);
    expect(vigie.radarCounts).toHaveBeenCalledTimes(1);
    component.refresh();
    expect(vigie.radarCounts).toHaveBeenCalledTimes(2);
    discardPeriodicTasks();
  }));

  it('ajouter un client : activé, il est ouvert', () => {
    build();
    dialogResults.set(AddClientDialogComponent, { kind: 'activated', hostId: 'h7' });

    component.addClient();

    expect(router.navigate).toHaveBeenCalledWith(['/vigie', 'h7'], { queryParamsHandling: 'preserve' });
    expect(atelier.runnerHostsOverview).toHaveBeenCalledTimes(2);
  });

  // ---- F-100 / SF-100-06 : la vérification guidée ----

  it("activer un client ouvre la vérification guidée sur ce client", () => {
    build();
    dialogResults.set(AddClientDialogComponent, { kind: 'activated', hostId: 'h7', hostName: 'CAGIP' });

    component.addClient();

    const call = dialog.open.calls.all().find((c) => c.args[0] === RadarVerificationDialogComponent);
    expect(call?.args[1]?.data).toEqual({ hostId: 'h7', hostName: 'CAGIP' });
  });

  it("l'en-tête du client dit la synchro du soir (F-100 / SF-100-07)", () => {
    const root = build({ hostRef: 'h1' });

    expect(radar.schedule).toHaveBeenCalledWith('h1');
    expect(root.querySelector('.vigie__schedule')?.textContent).toContain('Synchro du soir à 22:00');
  });

  it("l'en-tête du client relance la vérification guidée", () => {
    const root = build({ hostRef: 'h1' });

    (root.querySelector('.vigie__verify') as HTMLButtonElement).click();

    expect(dialog.open.calls.mostRecent().args[0]).toBe(RadarVerificationDialogComponent);
    expect(dialog.open.calls.mostRecent().args[1]?.data).toEqual({ hostId: 'h1', hostName: 'EDENRED' });
  });

  it("connecter un client ouvre l'appairage dans la Vigie", () => {
    build();
    dialogResults.set(AddClientDialogComponent, { kind: 'connect' });

    component.addClient();

    const pairing = dialog.open.calls.all().find((call) => call.args[0] === RunnerPairingDialogComponent);
    expect(pairing?.args[1]?.data).toEqual({ space: 'VIGIE' });
  });

  it('retirer sans cocher : retrait seul, rien d’effacé', () => {
    build();
    dialogResults.set(RemoveClientDialogComponent, { confirmed: true, purgeRadar: false });

    component.removeClient(component.selectedHost()!);

    expect(vigie.remove).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    expect(vigie.purgeRadar).not.toHaveBeenCalled();
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("Rien n'a été supprimé");
  });

  it('retirer en cochant : retrait, puis effacement du Radar', () => {
    build();
    dialogResults.set(RemoveClientDialogComponent, { confirmed: true, purgeRadar: true });

    component.removeClient(component.selectedHost()!);

    expect(vigie.remove).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    expect(vigie.purgeRadar).toHaveBeenCalledOnceWith('h1');
  });

  it('le retrait tient même si l’effacement du Radar échoue, et le dit', () => {
    build();
    vigie.purgeRadar.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    dialogResults.set(RemoveClientDialogComponent, { confirmed: true, purgeRadar: true });

    component.removeClient(component.selectedHost()!);

    expect(snackBar.open.calls.mostRecent().args[0]).toContain("son Radar n'a pas pu être effacé");
  });

  it('un retrait refusé (dernier espace) rend le message de la gateway', () => {
    build({ hosts: [client('h1', 'CAGIP', { spaces: ['VIGIE'] })] });
    vigie.remove.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'host_last_space', message: 'Un client vit dans au moins un espace.' },
    })));
    dialogResults.set(RemoveClientDialogComponent, { confirmed: true, purgeRadar: false });

    component.removeClient(component.selectedHost()!);

    expect(snackBar.open.calls.mostRecent().args[0]).toContain('au moins un espace');
    const data = dialog.open.calls.mostRecent().args[1]?.data as { inForge: boolean };
    expect(data.inForge).toBeFalse();
  });

  it('activer dans la Forge un client qui n’y est pas', () => {
    build({ hosts: [client('h1', 'CAGIP', { spaces: ['VIGIE'] })] });

    component.activateInForge(component.selectedHost()!);

    expect(vigie.activate).toHaveBeenCalledOnceWith('h1', 'FORGE');
  });

  // ---- F-106 / SF-106-03 : le déménagement de Teams ----

  it("l'onglet Conversations ouvre le terminal Teams du client, puis y navigue", () => {
    const root = build({ tab: 'conversations' });

    root.querySelector<HTMLButtonElement>('.vigie__open-conversation')?.click();

    expect(atelier.openTeamsTerminal).toHaveBeenCalledOnceWith('h1');
    expect(router.navigate).toHaveBeenCalledWith(['/atelier', 'wtt1']);
    expect(component.openingConversationHostId()).toBeNull();
  });

  it("l'onglet Réunions ouvre la même conversation", () => {
    const root = build({ tab: 'reunions' });

    root.querySelector<HTMLButtonElement>('.vigie__open-meetings')?.click();

    expect(atelier.openTeamsTerminal).toHaveBeenCalledOnceWith('h1');
  });

  it("une ouverture refusée le dit, et ne navigue pas", () => {
    build({ tab: 'conversations' });
    atelier.openTeamsTerminal.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409, error: { error: 'host_not_in_space', message: "Ce client n'est pas activé dans la Vigie." },
    })));

    component.openConversation(component.selectedHost()!);

    expect(router.navigate).not.toHaveBeenCalledWith(['/atelier', jasmine.anything()]);
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("n'est pas activé dans la Vigie");
  });

  it("l'en-tête porte la liaison Teams d'un client qui a un terminal Teams, relevée une fois", fakeAsync(() => {
    const root = build({ hosts: [client('h1', 'EDENRED', { teamsTerminalId: 'wtt1' })] });

    expect(teamsLinks.getLink).toHaveBeenCalledOnceWith('wtt1');
    expect(root.querySelector('.vigie__teams-link')?.textContent).toContain('Teams relié');
    tick(VIGIE_REFRESH_MS);
    expect(teamsLinks.getLink).toHaveBeenCalledTimes(1);
    discardPeriodicTasks();
  }));

  it("sans terminal Teams, ou en échec, aucune liaison n'est affichée", () => {
    let root = build();
    expect(teamsLinks.getLink).not.toHaveBeenCalled();
    expect(root.querySelector('.vigie__teams-link')).toBeNull();

    TestBed.resetTestingModule();
    root = build({ hosts: [client('h1', 'EDENRED', { teamsTerminalId: 'wtt1' })] });
    teamsLinks.getLink.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component.links.set({});
    component.refresh();
    fixture.detectChanges();
    expect(root.querySelector('.vigie__teams-link')).toBeNull();
  });

  // ---- F-106 / SF-106-04 : la passerelle vers la Forge ----

  it('le menu d’un client activé dans la Forge porte « Voir dans la Forge »', () => {
    const root = build();

    (root.querySelector('.poste__menu-trigger') as HTMLElement).click();
    fixture.detectChanges();

    const link = document.querySelector('.vigie__see-forge') as HTMLAnchorElement | null;
    expect(link?.textContent).toContain('Voir dans la Forge');
    expect(link?.getAttribute('href')).toBe('/forge/h1');
    expect(document.querySelector('.vigie__activate-forge')).toBeNull();
  });

  // ---- F-106 / SF-106-05 : la page d'un espace non souscrit ----

  it('un droit illisible montre aussi la présentation de la Vigie, sans lire les clients', () => {
    build({ entitled: false });
    TestBed.resetTestingModule();
    const root = build();
    atelier.teamsAccess.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    atelier.runnerHostsOverview.calls.reset();

    component.ngOnInit();
    fixture.detectChanges();

    expect(component.error()).toBe('not-entitled');
    expect(root.querySelector('app-space-pitch')?.textContent).toContain('La Vigie');
    expect(atelier.runnerHostsOverview).not.toHaveBeenCalled();
  });

  // ---- F-99 / SF-99-07 : exporter le Radar, et le proposer avant toute purge ----

  it("exporter le Radar : téléchargement, nom dit ; un échec est dit", () => {
    build();
    exporter.download.and.returnValue(of('radar-edenred-2026-09-13.md'));

    component.exportRadar(component.selectedHost()!);

    expect(exporter.download).toHaveBeenCalledOnceWith('h1', 'EDENRED');
    expect(snackBar.open.calls.mostRecent().args[0]).toBe('Radar exporté : radar-edenred-2026-09-13.md');
    expect(vigie.purgeRadar).not.toHaveBeenCalled();

    exporter.download.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    component.exportRadar(component.selectedHost()!);
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("n'a pas pu être exporté");
  });

  it('le retrait passe le client au dialogue, pour proposer l’export', () => {
    build();
    dialogResults.set(RemoveClientDialogComponent, { confirmed: false, purgeRadar: false });

    component.removeClient(component.selectedHost()!);

    expect((dialog.open.calls.mostRecent().args[1]?.data as { hostId: string }).hostId).toBe('h1');
    expect(vigie.remove).not.toHaveBeenCalled();
  });

  it('clôturer la mission sans cocher : clôture seule, rien d’effacé', () => {
    build();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: true, purgeRadar: false });

    component.closeMission(component.selectedHost()!);

    expect((dialog.open.calls.mostRecent().args[1]?.data as { hostId: string }).hostId).toBe('h1');
    expect(atelier.setHostMissionStatus).toHaveBeenCalledOnceWith('h1', 'CLOSED');
    expect(vigie.purgeRadar).not.toHaveBeenCalled();
    expect(snackBar.open.calls.mostRecent().args[0]).toContain("rien n'est coupé");
  });

  it('clôturer en cochant : la purge MISSION_CLOSED part après la clôture confirmée', () => {
    build();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: true, purgeRadar: true });

    component.closeMission(component.selectedHost()!);

    expect(atelier.setHostMissionStatus).toHaveBeenCalledOnceWith('h1', 'CLOSED');
    expect(vigie.purgeRadar).toHaveBeenCalledOnceWith('h1', 'MISSION_CLOSED');
    expect(snackBar.open.calls.mostRecent().args[0]).toContain('son Radar est effacé');
  });

  it('clôture refusée ou non confirmée par la gateway : aucune purge ; annuler : rien', () => {
    build();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: true, purgeRadar: true });
    atelier.setHostMissionStatus.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component.closeMission(component.selectedHost()!);
    expect(vigie.purgeRadar).not.toHaveBeenCalled();
    expect(component.busyHostId()).toBeNull();

    atelier.setHostMissionStatus.and.returnValue(of({ id: 'h1', missionStatus: 'ACTIVE' } as unknown as RunnerHost));
    component.closeMission(component.selectedHost()!);
    expect(vigie.purgeRadar).not.toHaveBeenCalled();

    atelier.setHostMissionStatus.calls.reset();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: false, purgeRadar: false });
    component.closeMission(component.selectedHost()!);
    expect(atelier.setHostMissionStatus).not.toHaveBeenCalled();
  });

  // ---- F-109 / SF-109-04 : les pages du client ----

  it("l'onglet Pages range les pages du client", () => {
    const root = build({ tab: 'pages', hostRef: 'h1' });

    expect(root.querySelector('[data-tab="pages"]')?.textContent).toContain('Pages');
    expect(root.querySelector('app-host-pages')).not.toBeNull();
    expect(pages.list).toHaveBeenCalledWith('h1', 'VIGIE');
  });

  it('clôturer en cochant « ses pages » : leur purge part après la clôture confirmée, et seulement alors', () => {
    build();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: true, purgeRadar: false, purgePages: true });
    atelier.setHostMissionStatus.and.returnValue(of({ id: 'h1', missionStatus: 'ACTIVE' } as unknown as RunnerHost));
    component.closeMission(component.selectedHost()!);
    expect(pages.removePlace).not.toHaveBeenCalled();

    atelier.setHostMissionStatus.and.returnValue(of({ id: 'h1', missionStatus: 'CLOSED' } as unknown as RunnerHost));
    component.closeMission(component.selectedHost()!);
    expect(pages.removePlace).toHaveBeenCalledOnceWith('h1', 'VIGIE');
    expect(vigie.purgeRadar).not.toHaveBeenCalled();
  });

  it('clôturer sans cocher « ses pages » : aucune page effacée', () => {
    build();
    dialogResults.set(CloseMissionDialogComponent, { confirmed: true, purgeRadar: true, purgePages: false });

    component.closeMission(component.selectedHost()!);

    expect(pages.removePlace).not.toHaveBeenCalled();
  });

  it('une mission déjà close ne propose pas de la clôturer', () => {
    build({ hosts: [client('h1', 'EDENRED', { missionStatus: 'CLOSED' })], hostRef: 'h1' });

    component.closeMission(component.selectedHost()!);

    expect(dialog.open).not.toHaveBeenCalled();
  });
});
