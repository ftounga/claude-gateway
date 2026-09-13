import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { catchError, forkJoin, of } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';

import { RadarEvidenceView, RadarSubjectDetail } from '../../core/models/radar-subject.models';
import { VigiePerson } from '../../core/models/vigie.models';
import { RadarSubjectService } from '../../core/services/radar-subject.service';
import { VigieService } from '../../core/services/vigie.service';
import { SpacePitchComponent } from '../../shared/space-pitch/space-pitch.component';
import {
  dayLabel,
  evidenceNumbers,
  linkLabel,
  refsOf,
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
 * ses renvois ; la chronologie multi-sources, avec ses citations et ses liens profonds. La page
 * <b>lit</b> : les gestes sur le sujet vivent dans l'onglet Radar (F-102).</p>
 *
 * <p><b>Isolation.</b> Tout part du poste de l'adresse ; la gateway vérifie possession, activation
 * dans la Vigie, et filtre sur {@code user_id} et {@code host_id}. Un sujet d'autrui est
 * « introuvable », exactement comme un sujet qui n'existe pas.</p>
 */
@Component({
  selector: 'app-radar-subject-page',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatProgressSpinnerModule, SpacePitchComponent],
  templateUrl: './radar-subject-page.component.html',
  styleUrl: './radar-subject-page.component.scss',
})
export class RadarSubjectPageComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly subjects = inject(RadarSubjectService);
  private readonly vigie = inject(VigieService);
  private readonly destroyRef = inject(DestroyRef);

  readonly hostRef = signal<string | null>(null);
  readonly subjectId = signal<string | null>(null);
  readonly hostName = signal<string | null>(null);
  readonly detail = signal<RadarSubjectDetail | null>(null);
  readonly people = signal<VigiePerson[]>([]);
  readonly loading = signal(true);
  readonly error = signal<SubjectPageError>('none');
  /** La preuve mise en évidence après un clic sur un renvoi. */
  readonly focusedEvidenceId = signal<string | null>(null);

  readonly numbers = computed(() => {
    const detail = this.detail();
    return detail ? evidenceNumbers(detail) : new Map<string, number>();
  });

  readonly badge = computed(() => stateBadge(this.detail()?.state));

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
    this.loadHostName(hostRef);
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
