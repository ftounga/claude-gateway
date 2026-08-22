import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { LegalPageComponent } from './legal-page.component';
import { LEGAL_COMPANY, SERVICE_NAME } from './legal-info';

/** Conditions générales d'utilisation — route publique `/cgu`. */
@Component({
  selector: 'app-cgu',
  standalone: true,
  imports: [LegalPageComponent, RouterLink],
  templateUrl: './cgu.component.html',
})
export class CguComponent {
  protected readonly company = LEGAL_COMPANY;
  protected readonly serviceName = SERVICE_NAME;
}
