import { ChangeDetectionStrategy, Component, afterNextRender, inject, viewChild } from '@angular/core';

import { bancPlan, bancScreen } from './jarvis-banc-scenes';
import { JARVIS_BANC, JarvisStage3dComponent } from './jarvis-stage-3d.component';
import { holoCssVars } from './jarvis-motion';
import { JarvisCockpitComponent } from './hud/jarvis-cockpit.component';
import { OPEN_FOLDS, writeFolds } from './hud/jarvis-cockpit-layout';

/**
 * F-236 / SF-236-01 — **la page du banc de captures** (`/jarvis-banc?banc=<scène>&t=<ms>`), <b>développement
 * seulement</b> (la route n'est appariée que si `isDevMode()`). Elle pose la scène 3D seule, sans session ni appel
 * réseau, pour que `frontend/scripts/jarvis-3d-captures.sh` produise des captures reproductibles.
 *
 * <p>SF-236-15 — les scènes `cockpit*` posent aussi le cockpit PC (panneaux latéraux, filament).</p>
 */
@Component({
  selector: 'app-jarvis-banc',
  imports: [JarvisStage3dComponent, JarvisCockpitComponent],
  template: `
    <app-jarvis-stage-3d #stage [withCore]="true" [planOverride]="staged.plan" [voiceLevel]="voice"
                         [labelLayer]="labels" [insets]="cockpit ? cockpitRef()?.insets() ?? noInsets : noInsets" />
    <div #labels class="jv3d-labels"></div>
    @if (cockpit) {
      <app-jarvis-cockpit #cockpitRef [stage]="stage" [signals]="screen" />
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[style]': 'vars' },
  styleUrls: ['./jarvis-labels.scss'],
  styles: [':host { display: block; min-height: 100dvh; background: #0B1020; }'],
})
export class JarvisBancComponent {
  private readonly scene = inject(JARVIS_BANC)?.scene ?? 'coeur';
  protected readonly vars = holoCssVars();
  protected readonly staged = bancPlan(this.scene,
    typeof matchMedia === 'function' && matchMedia('(max-width: 767px)').matches);
  protected readonly voice = (): number => this.staged.voice;
  protected readonly cockpit = this.scene.startsWith('cockpit');
  protected readonly screen = bancScreen(this.scene);
  protected readonly noInsets = { left: 0, right: 0 };
  protected readonly cockpitRef = viewChild<JarvisCockpitComponent>('cockpitRef');

  constructor() {
    if (this.cockpit) {
      writeFolds(this.scene === 'cockpit-replie' ? { left: true, right: true } : OPEN_FOLDS);
    }
    if (this.scene === 'cockpit-filament') {
      // Le filament de la capture : la ligne du poste « CAGIP » survolée, une fois la 3D affichée.
      afterNextRender(() => setTimeout(() => {
        const item = document.querySelector<HTMLElement>('.ck__item[data-key="h-cagip"]');
        item?.dispatchEvent(new MouseEvent('mouseenter'));
      }, 1500));
    }
  }
}
