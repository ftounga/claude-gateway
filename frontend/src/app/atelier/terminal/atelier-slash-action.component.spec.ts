import { ComponentFixture, TestBed } from '@angular/core/testing';

import { AtelierSlashActionComponent } from './atelier-slash-action.component';

/**
 * Le corps d'accusé d'une commande action (F-165 / SF-165-06) : présentation pure. On vérifie qu'il
 * affiche le message fourni.
 */
describe('AtelierSlashActionComponent (F-165 / SF-165-06)', () => {
  let fixture: ComponentFixture<AtelierSlashActionComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AtelierSlashActionComponent],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashActionComponent);
  });

  it('affiche le message d\'accusé', () => {
    fixture.componentInstance.message = 'Compaction douce en cours…';
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.action-note')?.textContent)
      .toContain('Compaction douce en cours');
  });
});
