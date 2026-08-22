import { Component } from '@angular/core';

import { LegalPageComponent } from './legal-page.component';
import { LEGAL_COMPANY, LEGAL_HOST, SERVICE_NAME } from './legal-info';

/** Mentions légales (LCEN art. 6-III) — route publique `/mentions-legales`. */
@Component({
  selector: 'app-mentions-legales',
  standalone: true,
  imports: [LegalPageComponent],
  templateUrl: './mentions-legales.component.html',
})
export class MentionsLegalesComponent {
  protected readonly company = LEGAL_COMPANY;
  protected readonly host = LEGAL_HOST;
  protected readonly serviceName = SERVICE_NAME;
}
