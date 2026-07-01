import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { TemplatesComponent } from './templates.component';
import { TemplatesService } from '../core/services/templates.service';
import { TemplateResponse } from '../core/models/template.models';

describe('TemplatesComponent', () => {
  let fixture: ComponentFixture<TemplatesComponent>;
  let component: TemplatesComponent;
  let service: jasmine.SpyObj<TemplatesService>;
  let dialog: jasmine.SpyObj<MatDialog>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const audit: TemplateResponse = {
    id: 't-1',
    name: 'Audit sécurité',
    category: 'AUDIT',
    content: 'Réalise un audit...',
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-01T00:00:00Z',
  };

  function stubDialog(result: boolean): void {
    dialog.open.and.returnValue({
      afterClosed: () => of(result),
    } as ReturnType<typeof dialog.open>);
  }

  function setup(list: TemplateResponse[] = [audit]): void {
    service = jasmine.createSpyObj<TemplatesService>('TemplatesService', [
      'list',
      'get',
      'create',
      'update',
      'delete',
    ]);
    service.list.and.returnValue(of(list));
    service.create.and.returnValue(of(audit));
    service.update.and.returnValue(of(audit));
    service.delete.and.returnValue(of(void 0));
    dialog = jasmine.createSpyObj<MatDialog>('MatDialog', ['open']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      imports: [TemplatesComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: TemplatesService, useValue: service },
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });

    fixture = TestBed.createComponent(TemplatesComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('loads the template list on init', () => {
    setup();
    expect(service.list).toHaveBeenCalled();
    expect(component.dataSource.data.length).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('shows a snackbar and stops loading when the list fails', () => {
    setup();
    service.list.and.returnValue(throwError(() => new Error('boom')));
    component.refresh();
    expect(component.loading()).toBeFalse();
    expect(snackBar.open).toHaveBeenCalled();
  });

  it('creates a template then refreshes', () => {
    setup([]);
    component.startCreate();
    component.form.setValue({ name: 'Nouveau', category: 'OTHER', content: 'corps' });
    component.save();
    expect(service.create).toHaveBeenCalledWith({
      name: 'Nouveau',
      category: 'OTHER',
      content: 'corps',
    });
    expect(service.list).toHaveBeenCalledTimes(2);
    expect(component.showForm()).toBeFalse();
  });

  it('does not call the API when the form is invalid', () => {
    setup([]);
    component.startCreate();
    component.form.setValue({ name: '', category: 'OTHER', content: '' });
    component.save();
    expect(service.create).not.toHaveBeenCalled();
  });

  it('prefills the form and calls update when editing', () => {
    setup();
    component.startEdit(audit);
    expect(component.editingId()).toBe('t-1');
    expect(component.form.value.name).toBe('Audit sécurité');
    component.form.patchValue({ name: 'Audit v2' });
    component.save();
    expect(service.update).toHaveBeenCalledWith('t-1', {
      name: 'Audit v2',
      category: 'AUDIT',
      content: 'Réalise un audit...',
    });
  });

  it('shows a validation snackbar on 400 when saving', () => {
    setup([]);
    service.create.and.returnValue(throwError(() => new HttpErrorResponse({ status: 400 })));
    component.startCreate();
    component.form.setValue({ name: 'X', category: 'OTHER', content: 'y' });
    component.save();
    expect(snackBar.open).toHaveBeenCalledWith(
      'Vérifiez les champs du modèle.',
      'Fermer',
      jasmine.anything(),
    );
    expect(component.saving()).toBeFalse();
  });

  it('copies the content to the clipboard', async () => {
    setup();
    const writeText = jasmine.createSpy('writeText').and.returnValue(Promise.resolve());
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    });
    component.copy(audit);
    await Promise.resolve();
    expect(writeText).toHaveBeenCalledWith('Réalise un audit...');
  });

  it('deletes a template after confirmation', () => {
    setup();
    stubDialog(true);
    component.remove(audit);
    expect(service.delete).toHaveBeenCalledWith('t-1');
    expect(service.list).toHaveBeenCalledTimes(2);
  });

  it('does not delete when the confirmation is cancelled', () => {
    setup();
    stubDialog(false);
    component.remove(audit);
    expect(service.delete).not.toHaveBeenCalled();
  });
});
