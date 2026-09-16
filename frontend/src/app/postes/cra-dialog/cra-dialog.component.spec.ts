import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';

import { CraDialogComponent } from './cra-dialog.component';
import { CraRecap, PosteBillingService } from '../../core/services/poste-billing.service';

describe('CraDialogComponent', () => {
  let fixture: ComponentFixture<CraDialogComponent>;
  let component: CraDialogComponent;
  let billing: jasmine.SpyObj<PosteBillingService>;
  let dialogRef: jasmine.SpyObj<MatDialogRef<CraDialogComponent, boolean>>;

  const recap: CraRecap = {
    written: 1,
    rejected: 1,
    unknown: 1,
    lines: [
      { cited: 'Free', hostId: 'h1', hostName: 'Free', days: 20, month: '2025-09',
        status: 'WRITTEN', message: null },
      { cited: 'KG', hostId: 'h2', hostName: 'KG', days: null, month: null, status: 'REJECTED',
        message: 'Plus de jours que de jours ouvrés.' },
      { cited: 'Acme', hostId: null, hostName: null, days: null, month: null,
        status: 'UNKNOWN_HOST', message: 'Client non reconnu : précisez le poste.' },
    ],
  };

  beforeEach(() => {
    billing = jasmine.createSpyObj<PosteBillingService>('PosteBillingService', ['submitCra']);
    dialogRef = jasmine.createSpyObj<MatDialogRef<CraDialogComponent, boolean>>('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [CraDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: PosteBillingService, useValue: billing },
        { provide: MatDialogRef, useValue: dialogRef },
      ],
    });
    fixture = TestBed.createComponent(CraDialogComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('n\'envoie rien pour un message vide', () => {
    component.message.set('   ');
    component.submit();
    expect(billing.submitCra).not.toHaveBeenCalled();
  });

  it('envoie le message et affiche le récapitulatif', () => {
    billing.submitCra.and.returnValue(of(recap));
    component.message.set('Free 20j, KG 25j, Acme 5j');
    component.submit();
    fixture.detectChanges();

    expect(billing.submitCra).toHaveBeenCalledWith('Free 20j, KG 25j, Acme 5j');
    expect(component.recap()).toEqual(recap);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('écrit');
    expect(text).toContain('refusé');
    expect(text).toContain('à préciser');
    expect(text).toContain('Acme');
  });

  it('mappe les statuts aux pastilles de la charte et écrit toujours le libellé', () => {
    expect(component.badgeClass(recap.lines[0])).toContain('badge--success');
    expect(component.badgeClass(recap.lines[1])).toContain('badge--error');
    expect(component.badgeClass(recap.lines[2])).toContain('badge--warning');
    expect(component.statusLabel(recap.lines[0])).toBe('écrit');
    expect(component.lineLabel(recap.lines[0])).toContain('Free');
    expect(component.lineLabel(recap.lines[0])).toContain('20 j');
    expect(component.lineLabel(recap.lines[2])).toBe('Acme');
  });

  it('signale un changement à la fermeture seulement si un CRA a été écrit', () => {
    billing.submitCra.and.returnValue(of({ lines: [], written: 0, rejected: 0, unknown: 0 }));
    component.message.set('bonjour');
    component.submit();
    component.close();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });

  it('affiche une erreur si l\'appel échoue', () => {
    billing.submitCra.and.returnValue(throwError(() => new HttpErrorResponse({ status: 502 })));
    component.message.set('Free 20j');
    component.submit();
    fixture.detectChanges();
    expect(component.error()).not.toBeNull();
    expect(component.submitting()).toBeFalse();
  });
});
