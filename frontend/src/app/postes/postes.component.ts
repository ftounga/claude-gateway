import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MatDialog } from '@angular/material/dialog';
import { MatMenuModule } from '@angular/material/menu';
import { MatSnackBar } from '@angular/material/snack-bar';

import { AtelierService } from '../core/services/atelier.service';
import { HostFolder, HostProjectSummary, RunnerHostOverview } from '../core/models/atelier.models';
import { ForgeBreadcrumbComponent } from '../shared/forge-breadcrumb/forge-breadcrumb.component';
import { HostBadgeComponent } from '../shared/host-badge/host-badge.component';
import { LiveBadgeComponent } from '../shared/live-badge/live-badge.component';
import { HostTone, hostTone } from '../shared/host-identity';
import { MissionBadgeComponent } from '../shared/mission-badge/mission-badge.component';
import {
  RunnerPairingDialogComponent,
  RunnerPairingDialogData,
} from '../atelier/runner/runner-pairing-dialog.component';
import {
  AddProjectDialogComponent,
  AddProjectDialogData,
} from './add-project-dialog/add-project-dialog.component';
import {
  DeleteHostDialogComponent,
  DeleteHostDialogData,
} from './delete-host-dialog/delete-host-dialog.component';
import {
  HostMissionStatus,
  MISSION_STATUSES,
  isMissionClosed,
  missionHint,
  missionIcon,
  missionLabel,
  normalizeMissionStatus,
} from '../shared/mission-status';

/** Période de rafraîchissement de la vue, en millisecondes. */
export const POSTES_REFRESH_MS = 15_000;

/** Ce qui empêche la vue d'exister — distinct d'un simple hoquet pendant un rafraîchissement. */
export type PostesError = 'none' | 'network' | 'forbidden';

/**
 * Liste vide **partagée** : une carte sans dossier connu rend toujours la <b>même</b> référence.
 * Un `[]` neuf à chaque appel changerait de référence à chaque cycle de détection.
 */
const EMPTY_FOLDERS: HostFolder[] = [];

/**
 * Écran **Postes** (F-49 / SF-49-02) : le seul endroit d'où l'on voit **toutes ses machines** —
 * connectées ou non, depuis quand, sous quel système, quel interpréteur, quels droits, quels projets
 * vivent dessous et ce qui tourne. Le terminal d'un projet est à **un clic**.
 *
 * <p>F-48 a réuni les projets sous un poste, mais les informations restaient éparpillées : la
 * présence dans le dialogue d'appairage d'un projet, l'interpréteur dans son détail, l'activité dans
 * le journal de chacun. Pour savoir si sa machine du bureau était encore connectée, il fallait ouvrir
 * un projet qui vit dessus.</p>
 *
 * <p><b>Une vue d'état, pas des terminaux vivants</b> (arbitrage n° 3 du cadrage du 2026-09-10) :
 * cet écran n'ouvre <b>aucun</b> canal SSE ou WebSocket. Il rejoue une lecture toutes les quinze
 * secondes, et <b>rien du tout</b> quand l'onglet est masqué — une vue que personne ne regarde n'a
 * aucune raison d'appeler la gateway.</p>
 *
 * <p><b>Presque en lecture seule</b> : renommer une machine, la couper ou révoquer un jeton restent
 * dans le dialogue de mise en service — une vue d'ensemble qui porterait tous ces gestes sur chaque
 * carte deviendrait un champ de mines. Deux exceptions, et deux seulement : l'<b>état de mission</b>
 * (F-60 / SF-60-02), qui est la question même de cet écran, et <b>supprimer le poste</b> (F-69 /
 * SF-69-02), parce que les postes ne sont listés <b>nulle part ailleurs</b> — inventer un écran de
 * réglages pour un seul bouton serait disproportionné. Cette dernière est sous menu de dépassement,
 * derrière un dialogue, et son cas dangereux est <b>refusé par la gateway</b>.</p>
 *
 * <p>L'isolation est garantie côté gateway : l'appel ne porte aucun identifiant, la vue part du
 * JWT.</p>
 */
@Component({
  selector: 'app-postes',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    ForgeBreadcrumbComponent,
    HostBadgeComponent,
    LiveBadgeComponent,
    MissionBadgeComponent,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatMenuModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  templateUrl: './postes.component.html',
  styleUrl: './postes.component.scss',
})
export class PostesComponent implements OnInit {
  private readonly atelier = inject(AtelierService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  /** Les trois états proposés au choix, dans l'ordre : du plus vivant au plus rangé. */
  readonly missionStatuses = MISSION_STATUSES;

  readonly hosts = signal<RunnerHostOverview[]>([]);
  /** Premier chargement : c'est le seul moment où l'écran a le droit d'être vide. */
  readonly loading = signal(true);
  readonly error = signal<PostesError>('none');
  /** Heure de la dernière lecture réussie — ce qui permet de juger si la vue vieillit. */
  readonly lastUpdatedAt = signal<Date | null>(null);

  readonly isEmpty = computed(() => !this.loading() && this.error() === 'none'
    && this.hosts().length === 0);

  /**
   * **Ce qui reste au premier plan** (F-60 / SF-60-02) : les missions en cours et en attente. Un
   * poste clôturé n'est pas perdu, il descend dans le repli — « se ranger sans disparaître ».
   */
  readonly openHosts = computed(() =>
    this.hosts().filter((host) => !isMissionClosed(host.missionStatus)));

  /**
   * **Terminaux vivants**, tous postes confondus (F-70 / SF-70-01). Affiché en tête d'écran avec ce
   * qu'il engage : quatre flux vivants, ce sont quatre tours facturés en parallèle.
   */
  readonly liveTerminalCount = computed(() =>
    this.hosts().reduce((total, host) => total + (host.liveTerminals ?? 0), 0));

  /** Plafond tranché par le PO. Écrit à côté du compte pour que la limite soit prévisible. */
  readonly liveTerminalLimit = 4;

  /** Les missions clôturées, rangées : hors de la vue principale, à un clic de la consultation. */
  readonly closedHosts = computed(() =>
    this.hosts().filter((host) => isMissionClosed(host.missionStatus)));

  /**
   * Repli des clôturées : **refermé** à l'ouverture de l'écran. Sa raison d'être est de retirer
   * les missions closes du champ de vision ; l'ouvrir d'office annulerait le rangement.
   */
  readonly closedOpen = signal(false);

  /** Poste dont l'état est en train de partir à la gateway — le temps d'un aller-retour. */
  readonly savingHostId = signal<string | null>(null);

  /** Poste dont la suppression est en cours : la carte se verrouille le temps de l'aller-retour. */
  readonly deletingHostId = signal<string | null>(null);

  // -------------------------- les dossiers non encore ouverts (F-72 / SF-72-03)

  /**
   * **Ce que la machine contient et que vous n'avez pas encore ouvert**, par poste.
   *
   * <p>Demande du PO : voir les dossiers de la racine <b>sans que rien ne soit créé</b>. Créer
   * automatiquement un projet par dossier noierait la vue — un <code>~/dev</code> de consultant en
   * contient vingt ou trente, dont deux servent — et ne rien montrer oblige à chercher.</p>
   *
   * <p>La clef est l'identifiant du poste ; l'absence d'entrée signifie « pas encore lu », ce qui
   * n'est pas la même chose qu'une liste vide.</p>
   */
  private readonly rootFolders = signal<Record<string, HostFolder[]>>({});

  /** Postes dont la racine a été tronquée : on le **dit** plutôt que de laisser croire. */
  private readonly rootTruncated = signal<Record<string, boolean>>({});

  /** Postes déjà interrogés dans cette page — la lecture n'est **pas** rejouée par le sondage. */
  private readonly foldersRead = new Set<string>();

  /** Chemin dont l'ouverture est en vol : la ligne se verrouille le temps de l'aller-retour. */
  readonly openingFolder = signal<string | null>(null);

  /**
   * Nombre de dossiers non ouverts montrés sur une carte. Huit tient dans une carte sans la faire
   * dérouler ; en afficher trente la rendrait illisible — et la lisibilité est le **seul** critère
   * ici : c'est le poste qui est facturé (F-65), pas les projets.
   */
  readonly maxUnopenedShown = 8;

  /**
   * Des postes existent, mais **toutes** leurs missions sont clôturées. La vue principale est vide
   * et le dit ; le repli, lui, reste présent et ouvrable — rien n'a disparu.
   */
  readonly allClosed = computed(() => !this.loading() && this.error() === 'none'
    && this.hosts().length > 0 && this.openHosts().length === 0);

  private timer: ReturnType<typeof setInterval> | null = null;

  /** L'ancrage demandé par le fil d'Ariane n'est honoré qu'une fois : après, l'écran est à vous. */
  private anchorHonoured = false;

  ngOnInit(): void {
    this.load(true);
    this.startPolling();
    document.addEventListener('visibilitychange', this.onVisibilityChange);
    this.destroyRef.onDestroy(() => {
      this.stopPolling();
      document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
  }

  /**
   * Amène dans le champ de vision la carte visée par le fragment `#poste-<id>` (F-68 / SF-68-01).
   *
   * <p>C'est la réponse au niveau « chez qui » du fil d'Ariane : cliquer sur le client ramène à
   * <b>sa</b> carte, dans la vue qui les porte toutes — il n'existe pas d'écran par client, et en
   * inventer un ouvrirait un périmètre que personne n'a demandé.</p>
   *
   * <p>Silencieux quand la carte n'existe pas : poste supprimé, mission clôturée et repliée, ou
   * simple fragment recopié de travers. Un fil d'Ariane ne doit jamais produire d'erreur.</p>
   */
  revealAnchoredHost(): void {
    if (this.anchorHonoured) {
      return;
    }
    const fragment = this.route.snapshot.fragment;
    if (!fragment || !fragment.startsWith('poste-')) {
      return;
    }
    this.anchorHonoured = true;
    const card = document.getElementById(fragment);
    if (card && typeof card.scrollIntoView === 'function') {
      card.scrollIntoView({ block: 'center' });
    }
  }

  /** Relecture demandée par l'utilisateur (bouton « Rafraîchir » ou « Réessayer »). */
  refresh(): void {
    // Une relecture DEMANDÉE relit aussi les racines (F-72 / SF-72-03) : c'est le geste par lequel
    // on dit « j'ai lancé le runner » ou « j'ai créé un dossier sur ma machine ». Le sondage
    // automatique, lui, ne les relit jamais.
    this.foldersRead.clear();
    this.load(this.hosts().length === 0);
  }

  /**
   * **Connecte un poste** (F-72 / SF-72-02) — le premier des deux gestes, et le seul geste de
   * création qui reste à la racine de la Forge.
   *
   * <p>Il part de la <b>machine</b> : on nomme le client, on vérifie le réseau, on appaire, on
   * lance le runner qui déclare sa racine. <b>Aucun projet n'est créé</b>, et c'est normal — ils
   * viendront de la carte de ce poste, autant qu'on veut, sans jamais réappairer.</p>
   *
   * <p>C'est l'inversion que F-72 apporte : le parcours d'avant partait du <b>projet</b>, puis
   * ramassait un poste en chemin dans la fenêtre d'appairage, et redemandait le même nom — d'où
   * deux entités nommées comme le client, et un utilisateur perdu.</p>
   */
  connectHost(): void {
    this.dialog
      .open(RunnerPairingDialogComponent, {
        // Aucune donnée de projet : c'est l'absence qui met le dialogue en mode POSTE. Un drapeau
        // pourrait contredire les données, l'absence non.
        data: {} satisfies RunnerPairingDialogData,
        width: RunnerPairingDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe(() => {
        // Le poste existe peut-être maintenant, connecté ou non : la vue doit le montrer. On relit
        // sans vider l'écran — un poste de plus n'est pas une raison de faire clignoter le reste.
        this.load(false);
      });
  }

  /**
   * **Poste virtuel « Hébergé »** (F-71 / SF-71-03) : les projets sans machine — dépôt GitHub,
   * archive importée — que l'accueil, organisé par postes, n'avait nulle part où ranger.
   *
   * <p><b>Ce n'est pas un poste</b> : sa carte ne porte ni appairage, ni état de connexion, ni
   * mission, ni suppression. Il vient de la gateway telle quelle et n'apparaît que s'il porte
   * quelque chose — l'écran ne le fabrique pas.</p>
   */
  isHosted(host: RunnerHostOverview): boolean {
    return host.virtual === true;
  }

  /**
   * Clef de suivi d'une carte. L'identifiant d'un poste réel ; une constante pour le poste virtuel,
   * qui n'en a pas — il n'existe aucune ligne en base pour lui.
   */
  hostKey(host: RunnerHostOverview): string {
    return host.id ?? 'heberge';
  }

  /**
   * Ton d'identité d'un poste (F-49 / SF-49-03) : **dérivé de son nom**, jamais rangé nulle part.
   * C'est ce qui rattache visuellement chaque projet à sa machine — le filet de la carte et celui
   * de chaque projet dessous sortent d'ici. Le nom reste écrit à côté : la couleur ne porte jamais
   * seule l'information.
   */
  tone(host: RunnerHostOverview): HostTone | null {
    // Le §9 réserve ses dix tons à l'identification d'une MACHINE. Le poste « Hébergé » n'en est
    // pas une : il garde le gris neutre du §5, et l'absence de couleur n'est pas un registre de
    // plus. Renvoyer un ton ici lui donnerait une identité de machine qu'il n'a pas.
    return this.isHosted(host) ? null : hostTone(host.name);
  }

  /** Ouvre le terminal du projet — le « à un clic » que la vue promet. */
  openTerminal(project: HostProjectSummary): void {
    this.router.navigate(['/atelier', project.id]);
  }

  // -------------------------------------------- ajouter un projet (F-72 / SF-72-03)

  /**
   * **Ajoute un projet** sous ce poste — le second des deux gestes.
   *
   * <p>Le poste est déjà appairé : il n'y a <b>rien à réinstaller</b>. L'explorateur liste les
   * dossiers de la machine, on clique, et le projet existe — <b>sans qu'aucun nom soit demandé</b>.
   * Autant de fois qu'on veut, <b>sans jamais réappairer</b>.</p>
   */
  addProject(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null) {
      // Le poste « Hébergé » n'a pas de machine à parcourir (F-71). Le gabarit n'offre pas ce
      // geste ; la garde est là pour que ce soit vrai du CODE et pas seulement du gabarit.
      return;
    }
    const data: AddProjectDialogData = { hostId, hostName: host.name };
    this.dialog
      .open(AddProjectDialogComponent, {
        data,
        width: AddProjectDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((changed) => {
        if (changed === true) {
          // Des projets sont apparus : la vue ET la liste des dossiers non ouverts sont en retard.
          this.forgetFolders(hostId);
          this.load(false);
        }
      });
  }

  /**
   * **Ouvre un projet sur un dossier non encore ouvert**, d'un clic depuis la carte.
   *
   * <p>C'est le même geste que dans l'explorateur, sans l'explorateur : le dossier est déjà sous les
   * yeux, il n'y a rien à parcourir.</p>
   */
  openFolderAsProject(host: RunnerHostOverview, folder: HostFolder): void {
    const hostId = host.id;
    if (hostId === null || this.openingFolder() !== null) {
      return;
    }
    this.openingFolder.set(folder.path);
    this.atelier.openHostProject(hostId, folder.path).subscribe({
      next: (workspace) => {
        this.openingFolder.set(null);
        this.snackBar.open(
          `Projet « ${workspace.name} » ouvert. Rien n'a été installé sur la machine.`,
          'Fermer',
          { duration: 4000, panelClass: 'snack-info' },
        );
        this.forgetFolders(hostId);
        this.load(false);
      },
      error: (err: unknown) => {
        this.openingFolder.set(null);
        this.snackBar.open(this.openErrorMessage(err), 'Fermer',
          { duration: 6000, panelClass: 'snack-error' });
        // L'écran était peut-être en retard — un projet créé dans un autre onglet. On relit plutôt
        // que de le laisser mentir, sans quoi le même refus se rejouerait.
        this.forgetFolders(hostId);
        this.load(false);
      },
    });
  }

  /**
   * Les dossiers de la racine **que ce poste n'a pas encore ouverts**, tronqués au seuil d'affichage.
   *
   * <p>Ce qui est <b>exclu</b> ne passe pas par ici : <code>.runnerignore</code>, le bruit de
   * construction (SF-38-21) et les dossiers cachés sont écartés <b>par le runner et la gateway</b>,
   * avant d'arriver. L'écran n'ajoute aucun filtre — ce qui est exclu ne quitte jamais la machine.</p>
   */
  unopenedFolders(host: RunnerHostOverview): HostFolder[] {
    const hostId = host.id;
    return hostId === null ? EMPTY_FOLDERS : this.unopenedByHost()[hostId]?.shown ?? EMPTY_FOLDERS;
  }

  /**
   * Ce que chaque carte a à montrer, **calculé une fois** par lecture.
   *
   * <p>Un signal calculé, et non un filtre appelé depuis le gabarit : une méthode qui rend un
   * nouveau tableau à chaque appel change de <b>référence</b> à chaque cycle de détection, ce
   * qu'Angular signale en mode développement (NG0100). Ici la référence est stable tant que les
   * dossiers ne changent pas.</p>
   */
  private readonly unopenedByHost = computed(() => {
    const truncated = this.rootTruncated();
    const byHost: Record<string, { shown: HostFolder[]; more: string | null }> = {};
    for (const [hostId, folders] of Object.entries(this.rootFolders())) {
      const free = folders.filter((folder) => !folder.used);
      const shown = free.slice(0, this.maxUnopenedShown);
      const hidden = free.length - shown.length;
      const more = hidden > 0
        ? `et ${hidden} autre${hidden > 1 ? 's' : ''} — ouvrez-les depuis « Ajouter un projet ».`
        : truncated[hostId]
          ? 'La machine en contient davantage : la liste a été tronquée.'
          : null;
      byHost[hostId] = { shown, more };
    }
    return byHost;
  });

  /**
   * Ce qu'on ne montre pas, **dit** : le reste de la liste, ou la troncature de la machine. Une
   * liste incomplète se dit (SF-38-21) — un dossier manquant en silence, ce sont dix minutes à
   * chercher ce que le système savait ne pas avoir envoyé.
   */
  moreFolders(host: RunnerHostOverview): string | null {
    const hostId = host.id;
    return hostId === null ? null : this.unopenedByHost()[hostId]?.more ?? null;
  }

  /**
   * Relit la racine d'un poste **connecté**, une seule fois par page.
   *
   * <p><b>Hors du sondage de 15 s</b> (arbitrage A1 du cadrage) : y attacher une lecture de la
   * machine ferait 240 <code>list_files</code> par heure et par poste pour une liste qui ne bouge
   * presque jamais — et chacun est une ligne d'audit sur la machine du client.</p>
   */
  private loadFolders(hosts: RunnerHostOverview[]): void {
    for (const host of hosts) {
      const hostId = host.id;
      // Le poste « Hébergé » n'a pas de machine ; un poste déconnecté n'a personne pour lister —
      // et la carte n'affiche alors aucune section, plutôt qu'une liste vide qui mentirait.
      if (hostId === null || !host.connected || this.foldersRead.has(hostId)) {
        continue;
      }
      this.foldersRead.add(hostId);
      this.atelier.runnerHostFolders(hostId).subscribe({
        next: (response) => {
          this.rootFolders.update((all) => ({ ...all, [hostId]: response.folders ?? [] }));
          this.rootTruncated.update((all) => ({ ...all, [hostId]: response.truncated === true }));
        },
        // SILENCIEUX : cette liste est un confort. Un rouge ici enverrait chercher au mauvais
        // endroit, alors que la carte, elle, est exacte. Le refus explicite existe là où le geste
        // est demandé — dans le dialogue « Ajouter un projet ».
        error: () => this.forgetFolders(hostId),
      });
    }
  }

  /** Oublie ce qu'on savait de la racine d'un poste : la prochaine lecture la relira. */
  private forgetFolders(hostId: string): void {
    this.foldersRead.delete(hostId);
    this.rootFolders.update((all) => {
      const next = { ...all };
      delete next[hostId];
      return next;
    });
  }

  /** Le message d'échec d'une ouverture. Sur un **409**, celui du serveur est repris tel quel. */
  private openErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409 && typeof err.error?.message === 'string') {
        return err.error.message;
      }
      if (err.status === 403) {
        return 'La Forge est nécessaire pour ce geste.';
      }
      if (err.status === 404) {
        return 'Poste introuvable.';
      }
    }
    return "Le projet n'a pas pu être ouvert. Veuillez réessayer.";
  }

  // -------------------------------------------- état de mission (F-60 / SF-60-02)

  /** Ouvre ou referme le repli des missions clôturées. */
  toggleClosed(): void {
    this.closedOpen.update((open) => !open);
  }

  /** État de mission d'un poste, jamais nul : une valeur absente se lit « En cours ». */
  mission(host: RunnerHostOverview): HostMissionStatus {
    return normalizeMissionStatus(host.missionStatus);
  }

  /** Libellé **écrit** de l'état — c'est lui que la couleur double, et jamais l'inverse. */
  missionLabel(status: string | null | undefined): string {
    return missionLabel(status);
  }

  /** Icône de l'entrée de menu. Décorative : le libellé l'accompagne toujours. */
  missionIcon(status: string | null | undefined): string {
    return missionIcon(status);
  }

  /** Ce que l'entrée de menu explique, pour que le choix ne soit pas une devinette. */
  missionHint(status: string | null | undefined): string {
    return missionHint(status);
  }

  /**
   * Déclare où en est la mission de ce poste.
   *
   * <p><b>Jamais de mise à jour optimiste</b> : clôturer *range* la carte hors de la vue
   * principale. La faire disparaître avant de savoir si l'ordre a abouti la ferait réapparaître à
   * la relecture suivante. L'écran attend donc la réponse — c'est elle qui fait foi.</p>
   *
   * <p>Sur échec, l'état affiché ne bouge pas d'un pixel et un message le dit : la vue reste
   * exacte, même quand la gateway ne répond pas.</p>
   */
  setMission(host: RunnerHostOverview, status: HostMissionStatus): void {
    // Le poste « Hébergé » n'a pas de mission — ni de client, ni de machine. Le gabarit ne propose
    // pas le menu ; cette garde tient même si quelqu'un l'y remet un jour.
    if (host.id === null || this.mission(host) === status || this.savingHostId() !== null) {
      return;
    }
    this.savingHostId.set(host.id);
    this.atelier.setHostMissionStatus(host.id, status).subscribe({
      next: (updated) => {
        this.savingHostId.set(null);
        // C'est la réponse qui décide, pas la valeur demandée.
        const confirmed = normalizeMissionStatus(updated.missionStatus);
        this.hosts.update((hosts) => hosts.map((h) =>
          h.id === host.id ? { ...h, missionStatus: confirmed } : h));
        if (isMissionClosed(confirmed)) {
          // La carte vient de quitter la vue principale : on dit où elle est allée.
          this.snackBar.open(`Mission clôturée. « ${host.name} » est rangé, rien n'est coupé.`,
            'Fermer', { duration: 4000, panelClass: 'snack-info' });
        }
      },
      error: () => {
        this.savingHostId.set(null);
        this.snackBar.open("L'état de la mission n'a pas pu être enregistré.", 'Fermer',
          { duration: 4000, panelClass: 'snack-error' });
      },
    });
  }

  // -------------------------------------------- suppression d'un poste (F-69 / SF-69-02)

  /**
   * **Supprime un poste** — ou explique pourquoi c'est refusé.
   *
   * <p>Décision du PO : <b>pas de cascade</b>. Tant que des projets vivent sous ce poste, le
   * dialogue est un <b>refus</b> : il dit combien il en reste et où ils sont, et <b>aucun appel ne
   * part</b>. Proposer un bouton qui refusera à coup sûr serait une fausse promesse.</p>
   *
   * <p>L'écran peut malgré tout être en retard — un projet créé dans un autre onglet, la vue relue
   * il y a quinze secondes. C'est pourquoi la gateway refuse elle aussi, en 409 : c'est <b>elle</b>
   * qui fait foi, et son message porte le compte exact. L'écran le reprend tel quel et relit.</p>
   */
  deleteHost(host: RunnerHostOverview): void {
    // Rien à supprimer sur un poste qui n'existe pas en base (F-71) : ses projets se suppriment un
    // par un, comme partout ailleurs.
    if (host.id === null || this.deletingHostId() !== null) {
      return;
    }
    const data: DeleteHostDialogData = {
      hostName: host.name,
      remainingProjects: host.projects.length,
    };
    this.dialog
      .open(DeleteHostDialogComponent, { data, width: '520px' })
      .afterClosed()
      .subscribe((confirmed) => {
        if (confirmed === true) {
          this.performHostDeletion(host);
        }
      });
  }

  private performHostDeletion(host: RunnerHostOverview): void {
    const hostId = host.id;
    if (hostId === null) {
      // Inatteignable depuis l'écran : le poste « Hébergé » n'expose pas ce geste. La garde est là
      // pour que ce soit vrai du CODE et pas seulement du gabarit.
      return;
    }
    this.deletingHostId.set(hostId);
    this.atelier.deleteRunnerHost(hostId).subscribe({
      next: () => {
        this.deletingHostId.set(null);
        // La carte ne quitte l'écran qu'à la réponse : la retirer avant la ferait revenir au
        // rafraîchissement suivant si l'ordre avait échoué.
        this.hosts.update((hosts) => hosts.filter((h) => h.id === null || h.id !== hostId));
        this.snackBar.open(
          `Poste « ${host.name} » supprimé. Rien n'a été effacé sur la machine.`,
          'Fermer',
          { duration: 5000, panelClass: 'snack-info' },
        );
      },
      error: (err: unknown) => {
        this.deletingHostId.set(null);
        this.snackBar.open(this.deletionErrorMessage(err), 'Fermer',
          { duration: 6000, panelClass: 'snack-error' });
        // La vue était en retard sur la gateway : on la relit plutôt que de la laisser mentir.
        this.load(false);
      },
    });
  }

  /**
   * Le message d'échec. Sur un **409**, celui du serveur est repris **tel quel** : il porte le
   * nombre exact de projets restants, que l'écran ne connaissait manifestement pas.
   */
  private deletionErrorMessage(err: unknown): string {
    if (err instanceof HttpErrorResponse) {
      if (err.status === 409 && typeof err.error?.message === 'string') {
        return err.error.message;
      }
      if (err.status === 403) {
        return 'La Forge est nécessaire pour ce geste.';
      }
      if (err.status === 404) {
        return 'Poste introuvable. Il a peut-être déjà été supprimé.';
      }
    }
    return "Le poste n'a pas pu être supprimé. Rien n'a été effacé.";
  }

  // ---------------------------------------------------------------- libellés

  /**
   * Durée écoulée depuis un instant, en relatif. Calculée **à l'écran** : une durée calculée au
   * serveur vieillit dans le navigateur et redevient fausse entre deux rafraîchissements.
   */
  elapsedLabel(instant: string | null | undefined): string | null {
    if (!instant) {
      return null;
    }
    const elapsed = Math.floor((Date.now() - new Date(instant).getTime()) / 1000);
    if (!Number.isFinite(elapsed) || elapsed < 0) {
      return null;
    }
    if (elapsed < 60) {
      return `il y a ${elapsed} s`;
    }
    if (elapsed < 3600) {
      return `il y a ${Math.floor(elapsed / 60)} min`;
    }
    if (elapsed < 86_400) {
      return `il y a ${Math.floor(elapsed / 3600)} h`;
    }
    return `il y a ${Math.floor(elapsed / 86_400)} j`;
  }

  /** État de la machine, en une phrase : « Connecté » ou depuis quand on ne l'a plus vue. */
  hostStateLabel(host: RunnerHostOverview): string {
    if (host.connected) {
      return 'Connecté';
    }
    const seen = this.elapsedLabel(host.lastSeenAt);
    return seen ? `Vu ${seen}` : 'Jamais connecté';
  }

  /** Chemin du projet sous la racine du poste — la racine elle-même quand il est vide. */
  projectPathLabel(project: HostProjectSummary): string {
    return project.projectPath?.trim() ? project.projectPath : 'la racine';
  }

  /** Ce qui tourne sur cette machine, ou `null` quand il n'y a rien à dire. */
  activityLabel(host: RunnerHostOverview): string | null {
    if (host.activeProjects > 0) {
      return host.activeProjects === 1 ? '1 projet actif'
        : `${host.activeProjects} projets actifs`;
    }
    const last = this.elapsedLabel(host.lastActivityAt);
    return last ? `Dernière activité ${last}` : null;
  }

  /** Ce que le projet a fait en dernier — le nom de l'outil, jamais sa cible. */
  projectActivityLabel(project: HostProjectSummary): string | null {
    const last = this.elapsedLabel(project.lastActivityAt);
    if (!last) {
      return null;
    }
    return project.lastTool ? `${project.lastTool} · ${last}` : last;
  }

  /** Heure de la dernière lecture réussie, en clair. */
  lastUpdatedLabel(): string | null {
    const at = this.lastUpdatedAt();
    if (!at) {
      return null;
    }
    const pad = (value: number) => String(value).padStart(2, '0');
    return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
  }

  // ---------------------------------------------------------------- interne

  /**
   * Lit la vue.
   *
   * @param blocking vrai au premier chargement : c'est le seul cas où l'on a le droit de vider
   *        l'écran. Un échec pendant un rafraîchissement **conserve** la vue précédente — une vue
   *        d'état qui clignote à chaque hoquet réseau est pire que la même vue légèrement en retard.
   */
  private load(blocking: boolean): void {
    if (blocking) {
      this.loading.set(true);
    }
    this.atelier.runnerHostsOverview().subscribe({
      next: (hosts) => {
        this.hosts.set(hosts.map((host) => ({ ...host, projects: host.projects ?? [] })));
        // La racine de chaque poste connecté, lue UNE fois (F-72 / SF-72-03, arbitrage A1) : le
        // sondage de 15 s ne la rejoue pas — lire la machine du client 240 fois par heure pour une
        // liste qui ne bouge presque jamais n'a aucun sens.
        this.loadFolders(hosts);
        this.error.set('none');
        this.loading.set(false);
        this.lastUpdatedAt.set(new Date());
        // Les cartes viennent d'être rendues : l'ancrage du fil d'Ariane peut enfin les trouver.
        setTimeout(() => this.revealAnchoredHost());
      },
      error: (err: unknown) => {
        this.loading.set(false);
        if (err instanceof HttpErrorResponse && err.status === 403) {
          // L'accès Atelier est refusé : la vue n'existera pas, quelle que soit la relecture.
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

  private startPolling(): void {
    this.stopPolling();
    if (this.error() === 'forbidden') {
      // Un refus d'accès ne se répare pas en relisant : on n'arme pas le sondage.
      return;
    }
    this.timer = setInterval(() => this.load(false), POSTES_REFRESH_MS);
  }

  private stopPolling(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  /**
   * Onglet masqué : on cesse d'appeler. Retour au premier plan : on relit tout de suite, parce que
   * ce que l'écran montre a pu vieillir de plusieurs minutes.
   */
  private readonly onVisibilityChange = (): void => {
    if (document.visibilityState === 'hidden') {
      this.stopPolling();
      return;
    }
    if (this.error() !== 'forbidden') {
      this.load(false);
      this.startPolling();
    }
  };
}
