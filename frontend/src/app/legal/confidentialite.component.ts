import { Component } from '@angular/core';

import { LegalPageComponent } from './legal-page.component';
import { LEGAL_COMPANY, LEGAL_HOST, SERVICE_NAME } from './legal-info';

/** Politique de confidentialité (RGPD art. 13-14) — route publique `/confidentialite`. */
@Component({
  selector: 'app-confidentialite',
  standalone: true,
  imports: [LegalPageComponent],
  templateUrl: './confidentialite.component.html',
})
export class ConfidentialiteComponent {
  protected readonly company = LEGAL_COMPANY;
  protected readonly host = LEGAL_HOST;
  protected readonly serviceName = SERVICE_NAME;
}
