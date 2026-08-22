import { Component } from '@angular/core';

import { LegalPageComponent } from './legal-page.component';
import { LEGAL_COMPANY, SERVICE_NAME } from './legal-info';

/**
 * Page de contact — route publique `/contact`.
 *
 * Volontairement sans formulaire : un formulaire imposerait un endpoint, une protection
 * anti-spam et un envoi d'e-mail. Une adresse affichée remplit l'obligation d'être joignable.
 */
@Component({
  selector: 'app-contact',
  standalone: true,
  imports: [LegalPageComponent],
  templateUrl: './contact.component.html',
})
export class ContactComponent {
  protected readonly company = LEGAL_COMPANY;
  protected readonly serviceName = SERVICE_NAME;
}
