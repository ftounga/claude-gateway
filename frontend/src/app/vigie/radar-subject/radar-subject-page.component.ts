import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  RadarAliasView,
  RadarCorrectionView,
  RadarEvidenceView,
  RadarManagerAnswer,
  RadarProjectCandidate,
  RadarSubjectDetail,
  RadarSubjectProjectLink,
  RadarSubjectProjects,
  RadarUnknownView,
} from '../../core/models/radar-subject.models';
import { VigiePerson } from '../../core/models/vigie.models';
import { AtelierService } from '../../core/services/atelier.service';
import { RadarSubjectService } from '../../core/services/radar-subject.service';
import { VigieService } from '../../core/services/vigie.service';
import { RADAR_DRAFT_STATE } from '../../shared/radar-draft';
import { SpacePitchComponent } from '../../shared/space-pitch/space-pitch.component';
import { httpErrorMessage } from '../../shared/http-error.util';
import { newsUndoErrorOf } from '../radar/radar-news';
import { UNDO_SNACK_MS } from '../radar/radar-columns.component';
import { RadarSubjectAliasesComponent } from './radar-subject-aliases.component';
import { RadarSubjectJournalComponent } from './radar-subject-journal.component';
import { aliasCorrection } from './radar-subject-journal';
import {
  SplitSubjectDialogComponent,
  SplitSubjectDialogData,
  SplitSubjectDialogResult,
} from './split-subject-dialog.component';
import {
  dayLabel,
  evidenceNumbers,
  linkLabel,
  peopleByRole,
  refsOf,
  roleLabel,
  safeLink,
  sourceView,
  stateBadge,
  whenLabel,
} from './radar-subject-view';

/** Ce qui empêche la page d'exister. */
export type SubjectPageError = 'none' | 'not-found' | 'not-in-vigie' | 'not-entitled' | 'network';

/**
 * **La page d'un sujet du Radar** (F-103 / SF-103-01) — la réponse à « où en est le MFA ? ».
 *
 * <p>L'état, la prochaine étape et l'échéance ; un résumé rendu <b>phrase par phrase</b>, chacune avec
 * ses renvois ; la chronologie multi-sources, avec ses citations et ses liens profonds. Les gestes sur
 * l'état vivent dans l'onglet Radar (F-102) ; la page porte ceux qui touchent à la <b>structure</b> du
 * sujet — séparer, alias (F-99 / SF-99-06) — et le journal où chaque correction s'annule.</p>
 *
 * <p><b>Isolation.</b> Tout part du poste de l'adresse ; la gateway vérifie possession, activation
 * dans la Vigie, et filtre sur {@code user_id} et {@code host_id}. Un sujet d'autrui est
 * « introuvable », exactement comme un sujet qui n'existe pas.</p>
 */
@Component({
  selector: 'app-radar-subject-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatMenuModule, MatProgressSpinnerModule, MatTooltipModule,
    SpacePitchComponent, RadarSubjectAliasesComponent, RadarSubjectJournalComponent],
  templateUrl: './radar-subject-page.component.html',
  styleUrl: './radar-subject-page.component.scss',
})
export class RadarSubjectPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly subjects = inject(RadarSubjectService);
  private readonly vigie = inject(VigieService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly atelier = inject(AtelierService);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  readonly hostRef = signal<string | null>(null);
  readonly subjectId = signal<string | null>(null);
  readonly hostName = signal<string | null>(null);
  readonly detail = signal<RadarSubjectDetail | null>(null);
  readonly people = signal<VigiePerson[]>([]);
  readonly loading = signal(true);
  readonly error = signal<SubjectPageError>('none');
  /** La preuve mise en évidence après un clic sur un renvoi. */
  readonly focusedEvidenceId = signal<string | null>(null);
  /** Ce que le Radar ne sait pas (SF-103-02) : `null` en lecture, `'error'` s'il n'a pas pu être lu. */
  readonly unknowns = signal<RadarUnknownView[] | 'error' | null>(null);

  /** La réponse préparée pour le manager (SF-103-03), jamais préparée d'office. */
  readonly answer = signal<RadarManagerAnswer | null>(null);
  readonly preparingAnswer = signal(false);
  readonly answerError = signal<string | null>(null);
  readonly openingConversation = signal(false);
  /** La nouvelle en cours d'annulation (F-104 / SF-104-02). */
  readonly undoingNews = signal<string | null>(null);
  /** Le journal des corrections du sujet (SF-99-06) : `null` en lecture, `'error'` s'il n'a pas pu être lu. */
  readonly corrections = signal<RadarCorrectionView[] | 'error' | null>(null);
  /** Un geste de structure (séparer, alias) est en cours. */
  readonly structureBusy = signal(false);
  /** La correction en cours d'annulation. */
  readonly undoingCorrection = signal<string | null>(null);

  /**
   * **Le projet dans la Forge** (F-106 / SF-106-06) : liens, propositions et projets liables. `null` en
   * lecture, `'error'` s'il n'a pas pu être lu — la page n'en dépend pas.
   */
  readonly projects = signal<RadarSubjectProjects | 'error' | null>(null);
  /** Le projet dont le lien est en cours d'écriture : un geste à la fois. */
  readonly projectBusy = signal<string | null>(null);

  readonly proposedProjects = computed<RadarSubjectProjectLink[]>(() => {
    const projects = this.projects();
    return projects && projects !== 'error' ? projects.links.filter((link) => link.state === 'PROPOSED') : [];
  });

  readonly linkedProjects = computed<RadarSubjectProjectLink[]>(() => {
    const projects = this.projects();
    return projects && projects !== 'error' ? projects.links.filter((link) => link.state === 'CONFIRMED') : [];
  });

  /** Le client est activé dans la Forge : le lien « Voir le projet dans la Forge » a une destination. */
  readonly projectsInForge = computed(() => {
    const projects = this.projects();
    return !!projects && projects !== 'error' && projects.inForge;
  });

  readonly projectCandidates = computed<RadarProjectCandidate[]>(() => {
    const projects = this.projects();
    return projects && projects !== 'error' ? projects.candidates : [];
  });

  /** Qui est dans ce sujet : qui décide, qui pilote, les experts, les informés. */
  readonly roles = computed(() => peopleByRole(this.detail()?.people));

  readonly numbers = computed(() => {
    const detail = this.detail();
    return detail ? evidenceNumbers(detail) : new Map<string, number>();
  });

  readonly badge = computed(() => {
    const detail = this.detail();
    return stateBadge(detail?.state, !!detail?.wokeAt && detail?.state === 'CLOSED');
  });

  readonly summary = computed(() =>
    [...(this.detail()?.summary ?? [])].sort((a, b) => a.position - b.position));

  readonly authors = computed(() => {
    const names = new Map<string, string>();
    for (const person of this.people()) {
      names.set(person.id, person.displayName);
    }
    return names;
  });

  readonly sourceView = sourceView;
  readonly whenLabel = whenLabel;
  readonly dayLabel = dayLabel;
  readonly linkLabel = linkLabel;
  readonly safeLink = safeLink;
  readonly roleLabel = roleLabel;

  /** Garde contre une réponse d'un sujet précédent arrivée après celle du sujet courant. */
  private requestSeq = 0;
  private hostNamesRead = false;

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const hostRef = params.get('hostRef');
      const subjectId = params.get('subjectId');
      const hostChanged = hostRef !== this.hostRef();
      this.hostRef.set(hostRef);
      this.subjectId.set(subjectId);
      if (hostChanged) {
        this.hostNamesRead = false;
        this.hostName.set(null);
      }
      this.load();
    });
  }

  /** Relit la page (bouton *Réessayer*). */
  reload(): void {
    this.load();
  }

  // ------------------------------------------------------------ la réponse au manager (SF-103-03)

  /** Prépare la réponse : un appel au fournisseur, décompté — seulement sur ce geste. */
  prepareAnswer(): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId || this.preparingAnswer()) {
      return;
    }
    const seq = this.requestSeq;
    this.preparingAnswer.set(true);
    this.answerError.set(null);
    this.subjects.managerAnswer(hostRef, subjectId).subscribe({
      next: (answer) => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.preparingAnswer.set(false);
        this.answer.set(answer);
      },
      error: (err: unknown) => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.preparingAnswer.set(false);
        this.answerError.set(answerErrorOf(err));
      },
    });
  }

  /** Copie la réponse dans le presse-papiers. */
  copyAnswer(): void {
    const text = this.answer()?.text;
    const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
    if (!text) {
      return;
    }
    if (!clipboard || typeof clipboard.writeText !== 'function') {
      this.snackBar.open('Copie impossible sur ce navigateur.', 'Fermer', { duration: 4000, panelClass: 'snack-error' });
      return;
    }
    clipboard.writeText(text).then(
      () => this.snackBar.open('Réponse copiée.', 'Fermer', { duration: 4000, panelClass: 'snack-success' }),
      () => this.snackBar.open('Copie impossible.', 'Fermer', { duration: 4000, panelClass: 'snack-error' }),
    );
  }

  /**
   * **Ajuster en discutant** : ouvre la conversation du client (son terminal Teams, créé s'il n'existe pas)
   * et y dépose un brouillon **sans l'envoyer**. Le brouillon voyage dans l'état de navigation, jamais
   * dans l'adresse : il ne se retrouve ni dans l'historique du navigateur, ni dans un journal d'accès.
   */
  adjustAnswer(): void {
    const hostRef = this.hostRef();
    const answer = this.answer();
    const subject = this.detail();
    if (!hostRef || !answer || !subject || this.openingConversation()) {
      return;
    }
    this.openingConversation.set(true);
    this.atelier.openTeamsTerminal(hostRef).subscribe({
      next: (terminal) => {
        this.openingConversation.set(false);
        void this.router.navigate(['/atelier', terminal.id], {
          state: { [RADAR_DRAFT_STATE]: adjustDraft(subject.name, answer.text) },
        });
      },
      error: (err: unknown) => {
        this.openingConversation.set(false);
        this.snackBar.open(httpErrorMessage(err, "La conversation n'a pas pu être ouverte. Rien n'a été créé."),
          'Fermer', { duration: 6000, panelClass: 'snack-error' });
      },
    });
  }

  // ------------------------------------------------------------ le projet dans la Forge (F-106 / SF-106-06)

  /** « Chemin sous la racine » d'un projet, pour le distinguer d'un homonyme. */
  projectPathLabel(path: string | null | undefined): string {
    return path?.trim() ? path : 'la racine';
  }

  /** Lie le sujet au projet — ou confirme la proposition. */
  linkProject(workspaceId: string): void {
    this.writeProject(workspaceId, true);
  }

  /** Délie — ou refuse la proposition : le Radar ne la reposera pas. */
  unlinkProject(workspaceId: string): void {
    this.writeProject(workspaceId, false);
  }

  private writeProject(workspaceId: string, link: boolean): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId || this.projectBusy() !== null) {
      return;
    }
    const seq = this.requestSeq;
    this.projectBusy.set(workspaceId);
    const write = link
      ? this.subjects.linkProject(hostRef, subjectId, workspaceId)
      : this.subjects.unlinkProject(hostRef, subjectId, workspaceId);
    write.subscribe({
      next: (projects) => {
        this.projectBusy.set(null);
        if (seq === this.requestSeq) {
          this.projects.set(projects);
        }
      },
      error: (err: unknown) => {
        this.projectBusy.set(null);
        this.snackBar.open(httpErrorMessage(err, link
          ? "Le lien n'a pas pu être posé. Rien n'a changé."
          : "Le lien n'a pas pu être défait. Rien n'a changé."),
        'Fermer', { duration: 6000, panelClass: 'snack-error' });
      },
    });
  }

  // ------------------------------------------------------------ annuler une nouvelle (F-104 / SF-104-02)

  /** Une note de l'utilisateur ou un courriel collé : c'est une nouvelle, elle s'annule entière. */
  isNews(evidence: RadarEvidenceView): boolean {
    return evidence.source === 'USER_NOTE' || evidence.source === 'PASTED_MAIL';
  }

  /** Annule la nouvelle : toutes ses écritures, puis sa preuve ; la page se relit. */
  undoNews(evidence: RadarEvidenceView): void {
    const hostRef = this.hostRef();
    if (!hostRef || !this.isNews(evidence) || this.undoingNews() !== null) {
      return;
    }
    this.undoingNews.set(evidence.id);
    this.subjects.undoNews(hostRef, evidence.id).subscribe({
      next: () => {
        this.undoingNews.set(null);
        this.snackBar.open('Nouvelle annulée : le Radar a tout défait.', 'Fermer', { duration: 4000, panelClass: 'snack-success' });
        this.reloadAfterUndo(hostRef);
      },
      error: (err: unknown) => {
        this.undoingNews.set(null);
        this.snackBar.open(newsUndoErrorOf(err), 'Fermer', { duration: 6000, panelClass: 'snack-error' });
      },
    });
  }

  /** Relit la page ; un sujet né de cette nouvelle n'existe plus : retour au Radar du client. */
  private reloadAfterUndo(hostRef: string): void {
    const subjectId = this.subjectId();
    if (!subjectId) {
      return;
    }
    this.subjects.subject(hostRef, subjectId).subscribe({
      next: () => this.load(),
      error: (err: unknown) => {
        if (err instanceof HttpErrorResponse && err.status === 404) {
          void this.router.navigate(['/vigie', hostRef]);
          return;
        }
        this.load();
      },
    });
  }

  // ------------------------------------------------------------ séparer, alias, journal (F-99 / SF-99-06)

  /** Ouvre le choix de ce qui part dans un nouveau sujet. */
  openSplit(): void {
    const hostRef = this.hostRef();
    const subject = this.detail();
    if (!hostRef || !subject || subject.mergedIntoId || this.structureBusy()) {
      return;
    }
    this.dialog
      .open<SplitSubjectDialogComponent, SplitSubjectDialogData, SplitSubjectDialogResult>(SplitSubjectDialogComponent, {
        data: { hostId: hostRef, subject },
        width: SplitSubjectDialogComponent.DIALOG_WIDTH,
        maxWidth: '95vw',
        autoFocus: false,
      })
      .afterClosed()
      .subscribe((result) => {
        if (!result) {
          return;
        }
        this.offerUndo(`« ${result.name} » est un nouveau sujet.`, hostRef, () => of(result.correction));
        this.refreshAfterGesture();
      });
  }

  /** Ajoute un autre nom au sujet. */
  addAlias(alias: string): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId || this.structureBusy()) {
      return;
    }
    this.structureBusy.set(true);
    this.subjects.addAlias(hostRef, subjectId, alias).subscribe({
      next: (added) => {
        this.structureBusy.set(false);
        this.offerUndo('Alias ajouté.', hostRef, () => this.findAliasCorrection(hostRef, subjectId, 'ADD_ALIAS', added.id));
        this.refreshAfterGesture();
      },
      error: (err: unknown) => {
        this.structureBusy.set(false);
        this.fail(err, "L'alias n'a pas pu être ajouté. Rien n'a changé.");
      },
    });
  }

  /** Retire un alias ou une consigne. */
  removeAlias(alias: RadarAliasView): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId || this.structureBusy()) {
      return;
    }
    this.structureBusy.set(true);
    this.subjects.removeAlias(hostRef, subjectId, alias.id).subscribe({
      next: () => {
        this.structureBusy.set(false);
        this.offerUndo(alias.rejected ? 'Consigne retirée.' : 'Alias retiré.', hostRef,
          () => this.findAliasCorrection(hostRef, subjectId, 'REMOVE_ALIAS', alias.id));
        this.refreshAfterGesture();
      },
      error: (err: unknown) => {
        this.structureBusy.set(false);
        this.fail(err, "L'alias n'a pas pu être retiré. Rien n'a changé.");
        this.refreshAfterGesture();
      },
    });
  }

  /** Annule une correction du journal ; la page relit — un sujet qui n'existe plus ramène au Radar. */
  undoCorrection(correction: RadarCorrectionView): void {
    const hostRef = this.hostRef();
    if (!hostRef || this.undoingCorrection() !== null || correction.undoneAt !== null) {
      return;
    }
    this.undoingCorrection.set(correction.id);
    this.subjects.undo(hostRef, correction.id).subscribe({
      next: () => {
        this.undoingCorrection.set(null);
        this.snackBar.open('Geste annulé.', 'Fermer', { duration: 4000, panelClass: 'snack-success' });
        this.reloadAfterUndo(hostRef);
      },
      error: (err: unknown) => {
        this.undoingCorrection.set(null);
        this.fail(err, "Le geste n'a pas pu être annulé.");
      },
    });
  }

  /**
   * La snackbar d'un geste, avec **Annuler** (§17). La correction à annuler est résolue au clic : pour un
   * alias, elle est retrouvée dans le journal relu (les routes d'alias ne la rendent pas).
   */
  private offerUndo(message: string, hostRef: string, correction: () => Observable<RadarCorrectionView | null>): void {
    this.snackBar.open(message, 'Annuler', { duration: UNDO_SNACK_MS })
      .onAction()
      .subscribe(() => correction().subscribe({
        next: (found) => {
          if (found && this.hostRef() === hostRef) {
            this.undoCorrection(found);
          } else if (!found) {
            this.snackBar.open("Le geste n'a pas été retrouvé dans vos corrections.", 'Fermer',
              { duration: 6000, panelClass: 'snack-error' });
          }
        },
        error: (err: unknown) => this.fail(err, "Le geste n'a pas pu être annulé."),
      }));
  }

  private findAliasCorrection(hostRef: string, subjectId: string, action: 'ADD_ALIAS' | 'REMOVE_ALIAS',
    aliasId: string): Observable<RadarCorrectionView | null> {
    return this.subjects.corrections(hostRef, subjectId).pipe(
      map((journal) => aliasCorrection(journal ?? [], action, aliasId)));
  }

  /**
   * Relit le sujet et son journal après un geste, **sans** effacer la page : une réponse préparée pour le
   * manager (décomptée) reste affichée.
   */
  private refreshAfterGesture(): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId) {
      return;
    }
    const seq = this.requestSeq;
    this.loadCorrections(hostRef, subjectId, seq);
    this.subjects.subject(hostRef, subjectId).subscribe({
      next: (subject) => {
        if (seq === this.requestSeq) {
          this.detail.set(subject);
        }
      },
      error: () => {
        if (seq === this.requestSeq) {
          this.load();
        }
      },
    });
  }

  private loadCorrections(hostRef: string, subjectId: string, seq: number): void {
    this.subjects.corrections(hostRef, subjectId).subscribe({
      next: (journal) => {
        if (seq === this.requestSeq) {
          this.corrections.set(journal ?? []);
        }
      },
      error: () => {
        if (seq === this.requestSeq) {
          this.corrections.set('error');
        }
      },
    });
  }

  private fail(err: unknown, fallback: string): void {
    this.snackBar.open(httpErrorMessage(err, fallback), 'Fermer', { duration: 6000, panelClass: 'snack-error' });
  }

  refs(ids: readonly string[] | null | undefined): number[] {
    return refsOf(ids, this.numbers());
  }

  /** L'identifiant d'une preuve d'après son numéro sur la page. */
  evidenceIdOf(ref: number): string | null {
    for (const [id, n] of this.numbers()) {
      if (n === ref) {
        return id;
      }
    }
    return null;
  }

  numberOf(evidence: RadarEvidenceView): number | null {
    return this.numbers().get(evidence.id) ?? null;
  }

  authorOf(evidence: RadarEvidenceView): string | null {
    return evidence.authorPersonId ? this.authors().get(evidence.authorPersonId) ?? null : null;
  }

  /** Un renvoi : la chronologie défile jusqu'à la preuve, qui est mise en évidence. */
  focusEvidence(ref: number, event?: Event): void {
    event?.preventDefault();
    const id = this.evidenceIdOf(ref);
    if (!id) {
      return;
    }
    this.focusedEvidenceId.set(id);
    const target = typeof document === 'undefined' ? null : document.getElementById(`preuve-${id}`);
    target?.scrollIntoView?.({ behavior: 'smooth', block: 'center' });
  }

  private load(): void {
    const hostRef = this.hostRef();
    const subjectId = this.subjectId();
    if (!hostRef || !subjectId) {
      this.loading.set(false);
      this.error.set('not-found');
      return;
    }
    const seq = ++this.requestSeq;
    this.loading.set(true);
    this.error.set('none');
    this.unknowns.set(null);
    this.corrections.set(null);
    this.loadCorrections(hostRef, subjectId, seq);
    this.answer.set(null);
    this.answerError.set(null);
    this.preparingAnswer.set(false);
    this.loadHostName(hostRef);
    this.projects.set(null);
    this.projectBusy.set(null);
    // Le projet dans la Forge (SF-106-06) ne retient pas la page non plus.
    this.subjects.projects(hostRef, subjectId).subscribe({
      next: (projects) => {
        if (seq === this.requestSeq) {
          this.projects.set(projects);
        }
      },
      error: () => {
        if (seq === this.requestSeq) {
          this.projects.set('error');
        }
      },
    });
    // Les manques ne retiennent pas la page : ils arrivent quand ils arrivent, ou disent qu'ils manquent.
    this.subjects.unknowns(hostRef, subjectId).subscribe({
      next: (unknowns) => {
        if (seq === this.requestSeq) {
          this.unknowns.set(unknowns ?? []);
        }
      },
      error: () => {
        if (seq === this.requestSeq) {
          this.unknowns.set('error');
        }
      },
    });
    forkJoin({
      subject: this.subjects.subject(hostRef, subjectId),
      // L'annuaire ne sert qu'à nommer les auteurs : illisible, la page s'affiche sans eux.
      people: this.vigie.people(hostRef).pipe(catchError(() => of([] as VigiePerson[]))),
    }).subscribe({
      next: ({ subject, people }) => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.detail.set(subject);
        this.people.set(people ?? []);
        this.focusedEvidenceId.set(null);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        if (seq !== this.requestSeq) {
          return;
        }
        this.detail.set(null);
        this.loading.set(false);
        this.error.set(errorOf(err));
      },
    });
  }

  /** Le nom du client pour le fil d'Ariane, une fois par client ; illisible, il s'appelle « Client ». */
  private loadHostName(hostRef: string): void {
    if (this.hostNamesRead) {
      return;
    }
    this.hostNamesRead = true;
    this.vigie.hostSpaces().pipe(catchError(() => of([]))).subscribe((hosts) => {
      if (this.hostRef() === hostRef) {
        this.hostName.set((hosts ?? []).find((host) => host.hostId === hostRef)?.name ?? null);
      }
    });
  }
}

/** Le brouillon déposé dans la conversation : une demande d'aide, puis la réponse. */
export function adjustDraft(subjectName: string, answer: string): string {
  return `Aide-moi à ajuster la réponse que je vais donner à mon manager sur le sujet « ${subjectName} » :\n\n${answer}`;
}

/** Ce que l'encart dit d'une préparation en échec. */
export function answerErrorOf(err: unknown): string {
  if (err instanceof HttpErrorResponse) {
    switch (err.status) {
      case 402:
        return 'Votre quota de consommation est atteint : la réponse ne peut pas être préparée.';
      case 503:
        return 'Le fournisseur est momentanément indisponible. Réessayez dans un instant.';
      case 409:
        return 'Ce sujet a été fusionné : préparez la réponse depuis le sujet cible.';
      case 502:
        return "La réponse n'a pas pu être préparée. Réessayez.";
      default:
        break;
    }
  }
  return httpErrorMessage(err, "La réponse n'a pas pu être préparée. Réessayez.");
}

/** Traduit une erreur HTTP en ce que la page sait dire. */
export function errorOf(err: unknown): SubjectPageError {
  if (!(err instanceof HttpErrorResponse)) {
    return 'network';
  }
  switch (err.status) {
    case 400:
    case 404:
      return 'not-found';
    case 403:
      return 'not-entitled';
    case 409:
      return 'not-in-vigie';
    default:
      return 'network';
  }
}
