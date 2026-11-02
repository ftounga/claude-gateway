import { ChangeDetectionStrategy, Component, inject } from '@angular/core';

import { bancPlan } from './jarvis-banc-scenes';
import { JARVIS_BANC, JarvisStage3dComponent } from './jarvis-stage-3d.component';
import { holoCssVars } from './jarvis-motion';

/**
 * F-236 / SF-236-01 — **la page du banc de captures** (`/jarvis-banc?banc=<scène>&t=<ms>`), <b>développement
 * seulement</b> (la route n'est appariée que si `isDevMode()`). Elle pose la scène 3D seule, sans session ni appel
 * réseau, pour que `frontend/scripts/jarvis-3d-captures.sh` produise des captures reproductibles.
 */
@Component({
  selector: 'app-jarvis-banc',
  imports: [JarvisStage3dComponent],
  template: `
    <app-jarvis-stage-3d [withCore]="true" [planOverride]="staged.plan" [voiceLevel]="voice" [labelLayer]="labels" />
    <div #labels class="jv3d-labels"></div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[style]': 'vars' },
  styleUrls: ['./jarvis-labels.scss'],
  styles: [':host { display: block; min-height: 100dvh; background: #0B1020; }'],
})
export class JarvisBancComponent {
  protected readonly vars = holoCssVars();
  protected readonly staged = bancPlan(inject(JARVIS_BANC)?.scene ?? 'coeur',
    typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches);
  protected readonly voice = (): number => this.staged.voice;
}
