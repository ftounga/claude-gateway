import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';

import { AtelierSlashRecallComponent } from './atelier-slash-recall.component';
import { ThreadRecallResult } from './slash-panel-commands';

/**
 * Le corps du panneau /rappel (F-165 / SF-165-06) : présentation pure. On vérifie les états (invite /
 * chargement / échec / prêt) et le rendu des extraits.
 */
describe('AtelierSlashRecallComponent (F-165 / SF-165-06)', () => {
  let fixture: ComponentFixture<AtelierSlashRecallComponent>;
  let component: AtelierSlashRecallComponent;

  const result: ThreadRecallResult = {
    query: 'vpc', semantic: true,
    extracts: [
      { role: 'assistant', excerpt: 'Le VPC CIDR est 10.0.0.0/16', createdAt: '2026-09-30T10:00:00Z' },
      { role: 'user', excerpt: 'et le sous-réseau ?', createdAt: '2026-09-30T10:05:00Z' },
    ],
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashRecallComponent, NoopAnimationsModule],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashRecallComponent);
    component = fixture.componentInstance;
  });

  it('invite à donner un terme (état empty)', () => {
    fixture.componentRef.setInput('state', 'empty');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Donnez un terme');
  });

  it('affiche le chargement et l\'échec sans planter', () => {
    fixture.componentRef.setInput('state', 'loading');
    fixture.componentRef.setInput('term', 'vpc');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.recall-note')).not.toBeNull();

    fixture.componentRef.setInput('state', 'error');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.recall-note--error')).not.toBeNull();
  });

  it('liste les extraits en état « ready » et dit le mode sémantique', () => {
    component.state = 'ready';
    component.result = result;
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent as string;
    expect(text).toContain('par le sens');
    expect(text).toContain('VPC CIDR');
    expect(text).toContain('Claude'); // rôle assistant
    expect(text).toContain('Vous'); // rôle user
    expect((fixture.nativeElement as HTMLElement).querySelectorAll('.recall-item').length).toBe(2);
  });

  it('dit « aucun extrait » quand la recherche ne rend rien', () => {
    component.state = 'ready';
    component.result = { query: 'zzz', semantic: false, extracts: [] };
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Aucun extrait');
  });
});
