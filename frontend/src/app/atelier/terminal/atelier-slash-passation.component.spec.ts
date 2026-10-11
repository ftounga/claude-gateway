import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AtelierContextService } from '../../core/services/atelier-context.service';
import { AtelierSlashPassationComponent } from './atelier-slash-passation.component';
import { HANDOFF_SECTIONS, ThreadHandoff } from './slash-panel-commands';

/**
 * Le corps du panneau « La passation » (F-117 / SF-117-09) : lecture du résumé de compaction, correction
 * (Corriger / Enregistrer / Annuler), refus côté écran d'un texte hors gabarit, lecture seule sans
 * correction.
 */
describe('AtelierSlashPassationComponent (F-117 / SF-117-09)', () => {
  let fixture: ComponentFixture<AtelierSlashPassationComponent>;
  let component: AtelierSlashPassationComponent;
  let context: jasmine.SpyObj<AtelierContextService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const text = HANDOFF_SECTIONS.map((s) => `${s}\nContenu.`).join('\n');
  const handoff: ThreadHandoff = { summary: text, length: text.length };

  beforeEach(async () => {
    context = jasmine.createSpyObj<AtelierContextService>('AtelierContextService', ['correctHandoff']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    await TestBed.configureTestingModule({
      imports: [AtelierSlashPassationComponent, NoopAnimationsModule],
      providers: [
        { provide: AtelierContextService, useValue: context },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AtelierSlashPassationComponent);
    component = fixture.componentInstance;
    component.workspaceId = 'w1';
  });

  function el<T extends HTMLElement>(selector: string): T | null {
    return fixture.nativeElement.querySelector(selector) as T | null;
  }

  it('dit qu\'il n\'y a pas encore de passation', () => {
    component.state = 'none';
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Pas encore de passation');
  });

  it('rend la passation en Markdown, avec « Corriger »', () => {
    component.state = 'ready';
    component.handoff = handoff;
    fixture.detectChanges();
    expect(el('.pass-body h2')?.textContent).toContain('Objectif');
    expect(el('.pass-edit')).not.toBeNull();
  });

  it('en lecture seule, consultation sans « Corriger »', () => {
    component.state = 'ready';
    component.handoff = handoff;
    component.readOnly = true;
    fixture.detectChanges();
    expect(el('.pass-body')).not.toBeNull();
    expect(el('.pass-edit')).toBeNull();
  });

  it('Annuler referme l\'édition sans rien envoyer', () => {
    component.state = 'ready';
    component.handoff = handoff;
    fixture.detectChanges();
    el<HTMLButtonElement>('.pass-edit')!.click();
    fixture.detectChanges();
    expect(el('.pass-editor__input')).not.toBeNull();
    el<HTMLButtonElement>('.pass-cancel')!.click();
    fixture.detectChanges();
    expect(el('.pass-editor__input')).toBeNull();
    expect(context.correctHandoff).not.toHaveBeenCalled();
  });

  it('refuse d\'enregistrer un texte privé d\'une rubrique', () => {
    component.state = 'ready';
    component.handoff = handoff;
    component.startEdit();
    component.draft.set(text.replace('## Fichiers', '## Autres'));
    fixture.detectChanges();
    expect(component.canSave).toBeFalse();
    expect(el<HTMLButtonElement>('.pass-save')!.disabled).toBeTrue();
    expect(el('.pass-error')?.textContent).toContain('cinq rubriques');
  });

  it('enregistre la correction, émet la passation corrigée et le dit', () => {
    const corrected = text.replace('Contenu.', 'Corrigé.');
    context.correctHandoff.and.returnValue(of({ summary: corrected, length: corrected.length }));
    let saved: ThreadHandoff | undefined;
    component.saved.subscribe((h) => (saved = h));
    component.state = 'ready';
    component.handoff = handoff;
    component.startEdit();
    component.draft.set(corrected);
    fixture.detectChanges();

    el<HTMLButtonElement>('.pass-save')!.click();

    expect(context.correctHandoff).toHaveBeenCalledWith('w1', corrected);
    expect(saved?.summary).toBe(corrected);
    expect(component.editing()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('garde le brouillon et relaie le message du serveur en cas de refus', () => {
    context.correctHandoff.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 409,
      error: { error: 'turn_in_progress', message: 'Un tour est en cours sur ce projet.' },
    })));
    component.state = 'ready';
    component.handoff = handoff;
    component.startEdit();
    fixture.detectChanges();

    component.save();

    expect(component.editing()).toBeTrue();
    expect(snackBar.open).toHaveBeenCalledWith('Un tour est en cours sur ce projet.', 'OK', jasmine.any(Object));
  });
});
