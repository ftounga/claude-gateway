import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { RunnerHostOverview } from '../core/models/atelier.models';
import { VigiePerson, VigieRadarCounts } from '../core/models/vigie.models';
import { AtelierService } from '../core/services/atelier.service';
import { HostPagesComponent } from '../shared/pages/host-pages.component';
import { PresentationsPanelComponent } from '../shared/presentations/presentations-panel.component';
import { PagesService } from '../core/services/pages.service';
import { HostPresenceService } from '../core/services/host-presence.service';
import { VigieService, countsOfBrief } from '../core/services/vigie.service';
import { RadarBrief } from '../core/models/radar.models';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { LiveBadgeComponent } from '../shared/live-badge/live-badge.component';
import { TeamsLinkBadgeComponent } from '../shared/teams-link-badge/teams-link-badge.component';
import { TeamsLink, TeamsLinkService } from '../atelier/teams/teams-link.service';
import { MissionBadgeComponent } from '../shared/mission-badge/mission-badge.component';
import { SpacePitchComponent } from '../shared/space-pitch/space-pitch.component';
import {
  FORGE_ACCESS_BILLING_ROUTE,
  FORGE_ACCESS_CODE_FRAGMENT,
} from '../shared/forge-access';
import { httpErrorMessage } from '../shared/http-error.util';
import { isMissionClosed } from '../shared/mission-status';
import {
  RunnerPairingDialogComponent,
  RunnerPairingDialogData,
} from '../atelier/runner/runner-pairing-dialog.component';
import { ForgeRailComponent } from '../postes/forge-rail/forge-rail.component';
import { ForgeRow, defaultHostRef, groupHosts, hostRef } from '../postes/forge-fleet';
import {
  AddClientDialogComponent,
  AddClientDialogResult,
} from './add-client-dialog/add-client-dialog.component';
import {
  RemoveClientDialogComponent,
  RemoveClientDialogData,
  RemoveClientDialogResult,
} from './remove-client-dialog/remove-client-dialog.component';
import {
  CloseMissionDialogComponent,
  CloseMissionDialogData,
  CloseMissionDialogResult,
} from './close-mission-dialog/close-mission-dialog.component';
import { RadarExporter } from './radar-export/radar-export';
import { RadarScheduleComponent } from './radar-schedule/radar-schedule.component';
import { HostMailAddressComponent } from '../shared/host-mail-address/host-mail-address.component';
import { VigieReadinessComponent } from './vigie-readiness/vigie-readiness.component';
import { RunnerDiagJournalComponent } from './runner-diag-journal/runner-diag-journal.component';
import { RunnerUpdateNoticeComponent } from '../shared/runner-update/runner-update-notice.component';
import { updatingPresence } from '../shared/runner-update/runner-update';
import {
  RadarVerificationDialogComponent,
  RadarVerificationDialogData,
} from './radar-verification/radar-verification-dialog.component';
import { RadarBoardComponent } from './radar/radar-board.component';
import { RadarDirectoryComponent } from './radar-directory/radar-directory.component';
import { MeetingCapturePanelComponent } from './meeting-capture/meeting-capture-panel.component';
import {
  VIGIE_TABS,
  VIGIE_TAB_LABELS,
  VigieTab,
  effectiveVigieTab,
  fleetSummary,
  followUpLabel,
  syncLabel,
  syncNeedsAttention,
  toHandleLabel,
} from './vigie-fleet';

/** Période de rafraîchissement de la vue, comme la Forge. */
export const VIGIE_REFRESH_MS = 15_000;

/** Ce qui empêche la Vigie d'exister. */
export type VigieError = 'none' | 'network' | 'forbidden' | 'not-entitled';

/**
 * **La Vigie** (F-106 / SF-106-02) — l'espace du pilotage, à côté de la Forge.
 *
 * <p>La même forme que la Forge refondue (F-98) : un bandeau de flotte, la colonne des clients, un
 * seul client ouvert et ses onglets. La colonne est <b>le même composant</b> que celle de la Forge ;
 * seuls ses mots changent. Ce qui attend l'utilisateur ici n'est pas une autorisation mais une
 * <b>relance due</b> : c'est elle qui range un client dans « À regarder ».</p>
 *
 * <p><b>Un client, deux regards</b> : on n'importe pas un poste dans la Vigie, on l'y active
 * (SF-106-01). Rien de ce que fait cet écran ne copie ni ne supprime un poste.</p>
 */
@Component({
  selector: 'app-vigie',
  imports: [
    RouterLink,
    HostPagesComponent,
    PresentationsPanelComponent,
    ForgeRailComponent,
    HostBadgeComponent,
    LiveBadgeComponent,
    MissionBadgeComponent,
    RadarBoardComponent,
    RadarDirectoryComponent,
    MeetingCapturePanelComponent,
    RadarScheduleComponent,
    HostMailAddressComponent,
    VigieReadinessComponent,
    RunnerDiagJournalComponent,
    RunnerUpdateNoticeComponent,
    SpacePitchComponent,
    TeamsLinkBadgeComponent,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './vigie.component.html',
  // Découpé pour tenir le budget de style par feuille (SF-30-16) : l'ossature et le poste ouvert
  // partagés d'abord (même cascade qu'avant, quand `_forge-layout` était `@use` en tête), puis les
  // contenus propres à la Vigie.
  styleUrls: [
    './vigie-forge-shell.scss',
    './vigie-forge-detail.scss',
    './vigie.component.scss',
  ],
})
export class VigieComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly vigie = inject(VigieService);
  private readonly pagesService = inject(PagesService);
  private readonly presence = inject(HostPresenceService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly destroyRef = inject(DestroyRef);
  private readonly teamsLinks = inject(TeamsLinkService);
  private readonly exporter = inject(RadarExporter);

  readonly isMissionClosed = isMissionClosed;

  readonly billingRoute = FORGE_ACCESS_BILLING_ROUTE;
  readonly accessCodeFragment = FORGE_ACCESS_CODE_FRAGMENT;
  readonly tabs = VIGIE_TABS;
  readonly followUpLabel = followUpLabel;
  readonly toHandleLabel = toHandleLabel;

  readonly hosts = signal<RunnerHostOverview[]>([]);
  readonly loading = signal(true);
  readonly error = signal<VigieError>('none');
  readonly filter = signal('');
  readonly closedOpen = signal(false);
  /** Les compteurs du Radar par client, lus une fois par page. */
  readonly radarCounts = signal<Record<string, VigieRadarCounts>>({});
  /** L'annuaire par client, lu à l'ouverture de l'onglet Personnes. */
  readonly people = signal<Record<string, VigiePerson[] | 'error'>>({});
  readonly busyHostId = signal<string | null>(null);
  /** Client dont la conversation Teams est en cours d'ouverture (F-106 / SF-106-03). */
  readonly openingConversationHostId = signal<string | null>(null);
  /** L'état de la liaison Teams par client (F-87), relevé une fois sur son terminal Teams. */
  readonly links = signal<Record<string, TeamsLink>>({});
  private readonly linksRead = new Set<string>();

  private readonly countsRead = new Set<string>();
  private readonly peopleRead = new Set<string>();
  private timer: ReturnType<typeof setInterval> | null = null;

  private readonly routeHostRef = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('hostRef'))),
    { initialValue: null },
  );

  private readonly routeTab = toSignal(
    this.route.queryParamMap.pipe(map((params) => params.get('onglet'))),
    { initialValue: null },
  );

  readonly activeTab = computed<VigieTab>(() => effectiveVigieTab(this.routeTab()));

  readonly openHosts = computed(() =>
    this.hosts().filter((host) => !isMissionClosed(host.missionStatus)));

  readonly onlineCount = computed(() =>
    this.openHosts().filter((host) => this.online(host)).length);

  readonly summary = computed(() => fleetSummary(this.radarCounts()));

  readonly isEmpty = computed(() => !this.loading() && this.error() === 'none'
    && this.hosts().length === 0);

  readonly groups = computed(() =>
    groupHosts(this.hosts(), (host) => this.online(host), this.filter(),
      (host) => this.followUpsOf(host)));

  readonly selectedRef = computed<string | null>(() => {
    const hosts = this.hosts();
    if (hosts.length === 0) {
      return null;
    }
    const wanted = this.routeHostRef();
    if (wanted && hosts.some((host) => hostRef(host) === wanted)) {
      return wanted;
    }
    return defaultHostRef(hosts, (host) => this.online(host), (host) => this.followUpsOf(host));
  });

  readonly selectedHost = computed<RunnerHostOverview | null>(() =>
    this.hosts().find((host) => hostRef(host) === this.selectedRef()) ?? null);

  readonly detailOpen = computed(() => this.routeHostRef() !== null);

  ngOnInit(): void {
    const releaseClock = this.presence.watchClock();
    this.destroyRef.onDestroy(() => {
      releaseClock();
      this.stopPolling();
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
    // Le droit d'abord : sans lui, la Vigie n'a rien à lire (droit Teams, en attendant F-107).
    this.atelier.teamsAccess().subscribe({
      next: (access) => {
        if (access.entitled === true) {
          this.start();
        } else {
          this.deny();
        }
      },
      error: () => this.deny(),
    });
  }

  // ------------------------------------------------------------ colonne et client ouvert

  selectHost(row: ForgeRow): void {
    void this.router.navigate(['/vigie', row.ref], { queryParamsHandling: 'preserve' });
  }

  toggleClosed(): void {
    this.closedOpen.update((open) => !open);
  }

  refresh(): void {
    this.linksRead.clear();
    this.countsRead.clear();
    this.peopleRead.clear();
    this.load(this.hosts().length === 0);
  }

  tabLabel(tab: VigieTab): string {
    return VIGIE_TAB_LABELS[tab];
  }

  selectTab(tab: VigieTab): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { onglet: tab === 'radar' ? null : tab },
      queryParamsHandling: 'merge',
    });
    if (tab === 'personnes') {
      this.loadPeople(this.selectedHost());
    }
  }

  online(host: RunnerHostOverview): boolean {
    return this.presence.isOnline(host.id, host.connected);
  }

  presenceState(host: RunnerHostOverview): string {
    // F-111 / SF-111-04 : pendant la bascule d'une mise à jour, le runner n'est pas « hors ligne ».
    const updating = updatingPresence(host, this.online(host));
    if (updating) {
      return updating;
    }
    const [state] = this.presence.label(host.id, host.connected, host.lastSeenAt).split(' · ');
    return state.charAt(0).toUpperCase() + state.slice(1);
  }

  presenceSeen(host: RunnerHostOverview): string | null {
    return this.presence.label(host.id, host.connected, host.lastSeenAt).split(' · ')[1] ?? null;
  }

  followUpsOf(host: RunnerHostOverview): number {
    return host.id === null ? 0 : this.radarCounts()[host.id]?.followUpsDue ?? 0;
  }

  /** Ce qui réclame un geste dans le Radar du client (F-102 / SF-102-03). */
  toHandleOf(host: RunnerHostOverview): number {
    return host.id === null ? 0 : this.radarCounts()[host.id]?.toHandle ?? 0;
  }

  /**
   * Le résumé d'un client vient d'être relu (geste, synchro, *Réessayer*) : ses compteurs remplacent ceux
   * de la page — onglet, bandeau et colonne suivent sans relire la vue.
   */
  onBrief(hostId: string, brief: RadarBrief): void {
    this.radarCounts.update((all) => ({ ...all, [hostId]: countsOfBrief(brief) }));
  }

  countsOf(host: RunnerHostOverview): VigieRadarCounts | null {
    return host.id === null ? null : this.radarCounts()[host.id] ?? null;
  }

  syncLabel = syncLabel;
  syncNeedsAttention = syncNeedsAttention;

  inForge(host: RunnerHostOverview): boolean {
    return (host.spaces ?? ['FORGE']).includes('FORGE');
  }

  peopleOf(host: RunnerHostOverview): VigiePerson[] | 'error' | null {
    return host.id === null ? null : this.people()[host.id] ?? null;
  }

  // ------------------------------------------------------------ Teams (F-106 / SF-106-03)

  /** La liaison Teams du client, ou `null` quand il n'y a rien à dire (§14). */
  linkOf(host: RunnerHostOverview): TeamsLink | null {
    return host.id === null ? null : this.links()[host.id] ?? null;
  }

  /**
   * **Ouvre la conversation Teams du client** — son terminal Teams, créé s'il n'existe pas —, puis
   * y navigue. C'est la porte du volet Teams, qui a déménagé de la Forge.
   */
  openConversation(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null || this.openingConversationHostId() !== null) {
      return;
    }
    this.openingConversationHostId.set(hostId);
    this.atelier.openTeamsTerminal(hostId).subscribe({
      next: (terminal) => {
        this.openingConversationHostId.set(null);
        void this.router.navigate(['/atelier', terminal.id]);
      },
      error: (err: unknown) => {
        this.openingConversationHostId.set(null);
        this.fail(err, "La conversation n'a pas pu être ouverte. Rien n'a été créé.");
        this.load(false);
      },
    });
  }

  // ------------------------------------------------------------ ajouter, retirer

  addClient(): void {
    this.dialog
      .open<AddClientDialogComponent, void, AddClientDialogResult>(AddClientDialogComponent, {
        width: AddClientDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((result) => {
        if (!result) {
          return;
        }
        if (result.kind === 'connect') {
          this.connectClient();
          return;
        }
        this.load(false);
        void this.router.navigate(['/vigie', result.hostId], { queryParamsHandling: 'preserve' });
        // F-100 / SF-100-06 : à l'activation, la vérification guidée — ce que le runner voit de ce client.
        this.openVerification({ id: result.hostId, name: result.hostName ?? 'ce client' });
      });
  }

  /** **La vérification guidée** d'un client (F-100 / SF-100-06), relançable depuis son en-tête. */
  openVerification(host: Pick<RunnerHostOverview, 'id' | 'name'>): void {
    if (host.id === null) {
      return;
    }
    this.dialog.open<RadarVerificationDialogComponent, RadarVerificationDialogData>(RadarVerificationDialogComponent, {
      data: { hostId: host.id, hostName: host.name },
      width: RadarVerificationDialogComponent.DIALOG_WIDTH,
      maxWidth: '95vw',
      autoFocus: false,
    });
  }

  connectClient(): void {
    this.dialog
      .open(RunnerPairingDialogComponent, {
        data: { space: 'VIGIE' } satisfies RunnerPairingDialogData,
        width: RunnerPairingDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe(() => this.load(false));
  }

  activateInForge(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null || this.busyHostId() !== null) {
      return;
    }
    this.busyHostId.set(hostId);
    this.vigie.activate(hostId, 'FORGE').subscribe({
      next: () => {
        this.busyHostId.set(null);
        this.snackBar.open(`« ${host.name} » est aussi dans la Forge.`, 'Fermer', { duration: 5000 });
        this.load(false);
      },
      error: (err: unknown) => {
        this.busyHostId.set(null);
        this.fail(err, "Le client n'a pas pu être activé dans la Forge. Rien n'a changé.");
      },
    });
  }

  removeClient(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null || this.busyHostId() !== null) {
      return;
    }
    const data: RemoveClientDialogData = { hostId, hostName: host.name, inForge: this.inForge(host) };
    this.dialog
      .open<RemoveClientDialogComponent, RemoveClientDialogData, RemoveClientDialogResult>(
        RemoveClientDialogComponent, { data, width: '520px', maxWidth: '95vw', autoFocus: false })
      .afterClosed()
      .subscribe((result) => {
        if (result?.confirmed === true) {
          this.doRemove(hostId, host.name, result.purgeRadar === true);
        }
      });
  }

  // ------------------------------------------------------------ export, clôture de mission (F-99 / SF-99-07)

  /** Télécharge le Radar du client en Markdown ; rien n'est effacé. */
  exportRadar(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null) {
      return;
    }
    this.exporter.download(hostId, host.name).subscribe({
      next: (fileName) => this.snackBar.open(`Radar exporté : ${fileName}`, 'Fermer', { duration: 5000 }),
      error: (err: unknown) => this.fail(err, "Le Radar n'a pas pu être exporté."),
    });
  }

  /**
   * **Clôture la mission** du client : il se range, rien n'est coupé. L'export de son Radar est proposé,
   * son effacement est une case décochée ; la purge ne part qu'après la clôture confirmée par la gateway.
   */
  closeMission(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null || this.busyHostId() !== null || isMissionClosed(host.missionStatus)) {
      return;
    }
    this.dialog
      .open<CloseMissionDialogComponent, CloseMissionDialogData, CloseMissionDialogResult>(
        CloseMissionDialogComponent,
        { data: { hostId, hostName: host.name }, width: '560px', maxWidth: '95vw', autoFocus: false })
      .afterClosed()
      .subscribe((result) => {
        if (result?.confirmed === true) {
          this.doCloseMission(hostId, host.name, result.purgeRadar === true, result.purgePages === true);
        }
      });
  }

  private doCloseMission(hostId: string, name: string, purgeRadar: boolean, purgePages = false): void {
    this.busyHostId.set(hostId);
    this.atelier.setHostMissionStatus(hostId, 'CLOSED').subscribe({
      next: (updated) => {
        const done = (message: string, error = false) => {
          this.busyHostId.set(null);
          this.snackBar.open(message, 'Fermer',
            { duration: error ? 8000 : 5000, panelClass: error ? 'snack-error' : 'snack-info' });
          this.load(false);
        };
        if (!isMissionClosed(updated.missionStatus)) {
          done("La mission n'a pas été clôturée. Rien n'a été effacé.", true);
          return;
        }
        // Les pages du client (F-109 / SF-109-04) : effacées seulement si c'est demandé, et APRÈS la clôture confirmée.
        if (purgePages) {
          this.pagesService.removePlace(hostId, 'VIGIE').subscribe({
            error: () => this.snackBar.open("Les pages de ce client n'ont pas pu être effacées.", 'Fermer',
              { duration: 8000, panelClass: 'snack-error' }),
          });
        }
        if (!purgeRadar) {
          done(`Mission clôturée. « ${name} » est rangé, rien n'est coupé.`);
          return;
        }
        this.vigie.purgeRadar(hostId, 'MISSION_CLOSED').subscribe({
          next: () => done(`Mission clôturée. « ${name} » est rangé, et son Radar est effacé.`),
          error: () => done("Mission clôturée, mais son Radar n'a pas pu être effacé.", true),
        });
      },
      error: (err: unknown) => {
        this.busyHostId.set(null);
        this.fail(err, "La mission n'a pas pu être clôturée. Rien n'a changé.");
      },
    });
  }

  private doRemove(hostId: string, name: string, purgeRadar: boolean): void {
    this.busyHostId.set(hostId);
    this.vigie.remove(hostId, 'VIGIE').subscribe({
      next: () => {
        const done = () => {
          this.busyHostId.set(null);
          this.load(false);
          void this.router.navigate(['/vigie'], { queryParamsHandling: 'preserve' });
        };
        if (!purgeRadar) {
          this.snackBar.open(`« ${name} » est retiré de la Vigie. Rien n'a été supprimé.`, 'Fermer',
            { duration: 5000 });
          done();
          return;
        }
        this.vigie.purgeRadar(hostId).subscribe({
          next: () => {
            this.snackBar.open(`« ${name} » est retiré de la Vigie, et son Radar est effacé.`,
              'Fermer', { duration: 5000 });
            done();
          },
          error: () => {
            this.snackBar.open("Le client est retiré, mais son Radar n'a pas pu être effacé.",
              'Fermer', { duration: 8000, panelClass: 'snack-error' });
            done();
          },
        });
      },
      error: (err: unknown) => {
        this.busyHostId.set(null);
        this.fail(err, "Le client n'a pas pu être retiré de la Vigie. Rien n'a changé.");
      },
    });
  }

  // ------------------------------------------------------------ interne

  private deny(): void {
    this.loading.set(false);
    this.error.set('not-entitled');
  }

  private start(): void {
    this.load(true);
    this.startPolling();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  private load(blocking: boolean): void {
    if (blocking) {
      this.loading.set(true);
    }
    this.atelier.runnerHostsOverview('VIGIE').subscribe({
      next: (hosts) => {
        for (const host of hosts) {
          this.presence.record(host.id, host.connected, host.lastSeenAt);
        }
        // La Vigie ne montre pas les projets : le filtre ne doit pas retenir un client par eux.
        const clients = hosts
          .filter((host) => host.virtual !== true && host.id !== null)
          .map((host) => ({ ...host, projects: [] }));
        this.hosts.set(clients);
        this.error.set('none');
        this.loading.set(false);
        this.loadCounts(clients);
        this.loadLinks(clients);
        this.revealClosedSelection();
        if (this.activeTab() === 'personnes') {
          this.loadPeople(this.selectedHost());
        }
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (err instanceof HttpErrorResponse && err.status === 403) {
          this.error.set('forbidden');
          this.hosts.set([]);
          this.stopPolling();
          return;
        }
        if (blocking) {
          this.error.set('network');
        }
      },
    });
  }

  /** Les compteurs du Radar, une fois par client et par page — jamais au sondage. */
  private loadCounts(hosts: RunnerHostOverview[]): void {
    for (const host of hosts) {
      const hostId = host.id;
      if (hostId === null || this.countsRead.has(hostId)) {
        continue;
      }
      this.countsRead.add(hostId);
      this.vigie.radarCounts(hostId).subscribe({
        next: (counts) => this.radarCounts.update((all) => ({ ...all, [hostId]: counts })),
        error: () => this.countsRead.delete(hostId),
      });
    }
  }

  /**
   * La liaison Teams de chaque client qui a un terminal Teams et une machine en ligne, une fois par
   * page. Silencieuse : un relevé en échec n'affiche rien (§14).
   */
  private loadLinks(hosts: RunnerHostOverview[]): void {
    for (const host of hosts) {
      const hostId = host.id;
      const terminalId = host.teamsTerminalId ?? null;
      if (hostId === null || terminalId === null || !this.online(host) || this.linksRead.has(hostId)) {
        continue;
      }
      this.linksRead.add(hostId);
      this.teamsLinks.getLink(terminalId).subscribe({
        next: (link) => this.links.update((all) => ({ ...all, [hostId]: link })),
        error: () => undefined,
      });
    }
  }

  private loadPeople(host: RunnerHostOverview | null): void {
    const hostId = host?.id ?? null;
    if (hostId === null || this.peopleRead.has(hostId)) {
      return;
    }
    this.peopleRead.add(hostId);
    this.vigie.people(hostId).subscribe({
      next: (people) => this.people.update((all) => ({ ...all, [hostId]: people ?? [] })),
      error: () => this.people.update((all) => ({ ...all, [hostId]: 'error' })),
    });
  }

  private revealClosedSelection(): void {
    const host = this.selectedHost();
    if (host && isMissionClosed(host.missionStatus)) {
      this.closedOpen.set(true);
    }
  }

  private fail(err: unknown, fallback: string): void {
    this.snackBar.open(httpErrorMessage(err, fallback), 'Fermer',
      { duration: 6000, panelClass: 'snack-error' });
  }

  private startPolling(): void {
    this.stopPolling();
    this.timer = setInterval(() => this.load(false), VIGIE_REFRESH_MS);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') {
      this.stopPolling();
      return;
    }
    if (this.error() === 'none') {
      this.load(false);
      this.startPolling();
    }
  };
}
