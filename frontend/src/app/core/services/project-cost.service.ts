import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { catchError, of } from 'rxjs';

/** Ce qu'un projet a coûté (F-143 / SF-143-01). Montants en euros, convertis côté serveur. */
export interface ProjectCost {
  id: string;
  name: string;
  hostId: string | null;
  hostName: string | null;
  /** Dépense de la semaine en cours. */
  weekEur: number;
  /** Dépense depuis l'origine. */
  totalEur: number;
  weekTurns: number;
  totalTurns: number;
}

export interface ProjectCostReport {
  from: string;
  to: string;
  projects: ProjectCost[];
}

/**
 * **Ce que chaque projet a coûté** (F-143 / SF-143-01).
 *
 * <p>Le grain qui manquait entre la réponse (SF-133-02) et le client (SF-133-03) : un client porte
 * plusieurs projets, et c'est le projet qu'on ouvre.</p>
 *
 * <p><b>Une seule lecture pour tous les écrans</b>, comme pour le budget hebdomadaire : la tuile de
 * projet et le terminal montrent le même fait, et deux appels finiraient par afficher deux chiffres
 * du même instant.</p>
 *
 * <p><b>Un échec ne dit rien</b> : 403 pour un non-administrateur, gateway muette — le service rend
 * `null` et les écrans n'affichent rien. Le montant ne doit de toute façon pas quitter le serveur
 * pour qui n'est pas administrateur.</p>
 */
@Injectable({ providedIn: 'root' })
export class ProjectCostService {
  private readonly http = inject(HttpClient);

  readonly report = signal<ProjectCostReport | null>(null);

  private pending = false;

  /** Lit le rapport une fois. Les appels suivants ne relancent rien. */
  load(): void {
    if (this.pending || this.report() !== null) {
      return;
    }
    this.pending = true;
    this.http
      .get<ProjectCostReport>('/api/admin/cost/projects')
      .pipe(catchError(() => of(null)))
      .subscribe((report) => {
        this.pending = false;
        this.report.set(report);
      });
  }

  /** La dépense d'un projet, ou `null` s'il est inconnu du rapport. */
  costOf(projectId: string | null): ProjectCost | null {
    if (!projectId) {
      return null;
    }
    return this.report()?.projects.find((project) => project.id === projectId) ?? null;
  }
}
