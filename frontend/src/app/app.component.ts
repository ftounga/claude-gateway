import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { VersionUpdateBannerComponent } from './layout/version-update-banner/version-update-banner.component';

@Component({
  selector: 'app-root',
  // La bannière « nouvelle version » (F-152 / SF-152-05) vit à la racine, hors du router-outlet :
  // un déploiement peut survenir sur n'importe quelle route (app installée), et la racine est la
  // seule surface toujours montée. `position: fixed` → elle ne pousse aucun layout.
  imports: [RouterOutlet, VersionUpdateBannerComponent],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  title = 'claude-gateway';
}
