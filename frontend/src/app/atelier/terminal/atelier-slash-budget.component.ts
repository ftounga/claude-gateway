import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  computed,
  inject,
  input,
} from '@angular/core';

import { WeeklyBudgetService } from '../../core/services/weekly-budget.service';

/**
 * Le **corps du panneau `/budget`** (F-165 / SF-165-04) — le **budget hebdomadaire du poste**.
 *
 * <p><b>Réutilise le service PARTAGÉ</b> {@link WeeklyBudgetService} — la <b>même</b> lecture unique que la
 * Forge et `/cout`, pour ne jamais afficher deux chiffres différents au même instant (F-133 / SF-133-15).
 * Le budget est <b>scopé admin</b> côté passerelle : pour qui n'y a pas droit (ou un poste sans plafond),
 * la lecture rend `null` et le panneau <b>dégrade proprement</b> — jamais le budget d'un autre.</p>
 *
 * <p>Une VUE : aucun tour modèle. Jetons `--cg-*`, chiffres en `tabular-nums`, cibles ≥ 44 px.</p>
 */
@Component({
  selector: 'app-atelier-slash-budget',
  standalone: true,
  imports: [],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './atelier-slash-budget.component.html',
  styleUrl: './atelier-slash-budget.component.scss',
})
export class AtelierSlashBudgetComponent implements OnInit {
  private readonly budget = inject(WeeklyBudgetService);

  /** Le poste dont on lit le budget. `null` = aucun poste (terminal hébergé, orphelin). */
  readonly hostId = input<string | null>(null);

  /** La ligne budget du poste, ou `null` si non lisible (non-admin, ou aucun plafond) — réactif. */
  readonly client = computed(() => this.budget.clientOf(this.hostId()));

  /** Budget restant de la semaine (jamais négatif à l'affichage). */
  readonly remaining = computed(() => {
    const line = this.client();
    if (!line || line.budgetEur === null) {
      return 0;
    }
    return Math.max(0, line.budgetEur - line.spentEur);
  });

  /** Part consommée, bornée à 100 % pour la jauge (recalculée si l'API ne l'a pas fournie). */
  readonly percent = computed(() => {
    const line = this.client();
    if (!line || line.budgetEur === null || line.budgetEur <= 0) {
      return 0;
    }
    const raw = line.percent ?? (line.spentEur / line.budgetEur) * 100;
    return Math.min(100, Math.round(raw));
  });

  /** Vrai quand le budget approche l'épuisement (≥ 80 %) : la jauge passe en teinte d'alerte. */
  readonly nearLimit = computed(() => this.percent() >= 80);

  ngOnInit(): void {
    // Lecture unique et partagée (idempotente) : ne relance rien si déjà chargée.
    this.budget.load();
  }

  /** Un montant en euros, en français : « 1,23 € ». */
  money(value: number): string {
    return `${(value ?? 0).toFixed(2).replace('.', ',')} €`;
  }
}
