import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TerminalPreview } from '../../core/models/atelier.models';
import { TerminalPreviewComponent } from './terminal-preview.component';

/**
 * L'aperçu vivant, à l'écran (F-76 / SF-76-02).
 *
 * <p>Deux règles y sont vérifiées avant toute autre : <b>ce qui attend une autorisation se dit en
 * toutes lettres</b> — un point de couleur n'a pas suffi le 2026-09-08 (F-47) — et <b>aucun
 * quatrième registre de couleur</b> n'est introduit.</p>
 */
describe('TerminalPreviewComponent', () => {
  let fixture: ComponentFixture<TerminalPreviewComponent>;

  function render(preview: TerminalPreview | null, density: 'card' | 'tile' = 'card'): string {
    fixture.componentInstance.preview = preview;
    fixture.componentInstance.density = density;
    fixture.detectChanges();
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [TerminalPreviewComponent] })
      .compileComponents();
    fixture = TestBed.createComponent(TerminalPreviewComponent);
  });

  it('écrit ce qui attend une autorisation, en toutes lettres', () => {
    const text = render({
      activity: 'AWAITING_APPROVAL',
      activityDetail: 'rm -rf build',
      lines: ['Autorisation demandée'],
    });

    expect(text).toContain('Attend votre autorisation');
    expect(text).toContain('rm -rf build');
    expect(fixture.nativeElement.querySelector('.badge--warning')).not.toBeNull();
  });

  it('nomme la commande en cours', () => {
    expect(render({ activity: 'RUNNING', activityDetail: 'npm test', lines: [] }))
      .toContain('Exécute npm test');
  });

  it('dit « réfléchit » quand rien n’est encore nommé', () => {
    expect(render({ activity: 'THINKING', lines: [] })).toContain('Réfléchit');
  });

  it('ne montre rien quand il n’y a rien à dire', () => {
    // Une ligne « Inactif » sous chaque projet n'apprendrait rien de plus que la pastille de vie.
    render({ activity: 'IDLE', lines: [] });

    expect(fixture.nativeElement.querySelector('.preview')).toBeNull();
    render(null);
    expect(fixture.nativeElement.querySelector('.preview')).toBeNull();
  });

  it('montre trois lignes sur une carte et six dans une tuile', () => {
    const lines = ['1', '2', '3', '4', '5', '6'];

    render({ activity: 'RUNNING', activityDetail: 'npm test', lines }, 'card');
    expect(fixture.nativeElement.querySelectorAll('.preview__line').length).toBe(3);

    render({ activity: 'RUNNING', activityDetail: 'npm test', lines }, 'tile');
    expect(fixture.nativeElement.querySelectorAll('.preview__line').length).toBe(6);
  });

  it('montre les DERNIÈRES lignes, pas les premières', () => {
    const text = render(
      { activity: 'RUNNING', activityDetail: 'npm test', lines: ['vieux', 'milieu', 'a', 'b', 'c'] },
      'card',
    );

    expect(text).toContain('c');
    expect(text).not.toContain('vieux');
  });

  it('n’emprunte aucune couleur d’identité de poste ni aucune couleur nouvelle', () => {
    // Non-régression de la règle du cadrage : trois registres cohabitent déjà, on n'en ajoute pas
    // un quatrième. L'attente emprunte la pastille de STATUT (§5), qui existe déjà.
    render({ activity: 'AWAITING_APPROVAL', activityDetail: 'git push', lines: ['x'] });

    const html = (fixture.nativeElement as HTMLElement).innerHTML;
    expect(html).not.toContain('#4370A3');
    expect(html).not.toContain('host-identity');
    expect(html).not.toContain('style="background');
  });

  it('ne porte aucun champ de saisie : on regarde, on n’écrit pas', () => {
    render({ activity: 'RUNNING', activityDetail: 'npm test', lines: ['x'] });

    expect(fixture.nativeElement.querySelector('input')).toBeNull();
    expect(fixture.nativeElement.querySelector('textarea')).toBeNull();
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
