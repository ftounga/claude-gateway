import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';

import { AppRequestView } from '../../core/models/atelier.models';
import { AppRequestService, sizeLabel } from '../../core/services/app-request.service';
import { AdminBesoinsComponent } from './admin-besoins.component';

function item(over: Partial<AppRequestView> = {}): AppRequestView {
  return {
    id: 'r-1', workspaceId: 'ws-1', kind: 'BUG', title: 'Le terminal se fige',
    description: 'quand je colle une image le terminal se fige', reformulation: 'Le collage gèle la saisie.',
    observedContext: 'Terminal du poste', status: 'EN_ATTENTE', featureRef: null,
    createdAt: '2026-10-16T10:00:00Z', updatedAt: '2026-10-16T10:00:00Z', recordedAt: '2026-10-16T10:01:00Z',
    attachments: [
      { position: 1, name: 'capture.png', contentType: 'image/png', size: 2048, copyStatus: 'COPIED' },
      { position: 2, name: 'demo.mp4', contentType: 'video/mp4', size: 5_000_000, copyStatus: 'FAILED',
        copyError: 'Fichier illisible sur la machine (demo.mp4).' },
      { position: 3, name: 'log.txt', contentType: 'text/plain', size: 12, copyStatus: 'PENDING' },
    ],
    ...over,
  };
}

/**
 * La section **Besoins** de l'administration (F-205 / SF-205-02) : liste filtrée, détail avec les mots
 * de l'utilisateur et ses pièces, aperçu d'une capture, changement de statut et de feature.
 */
describe('AdminBesoinsComponent (F-205 / SF-205-02)', () => {
  let fixture: ComponentFixture<AdminBesoinsComponent>;
  let component: AdminBesoinsComponent;
  let service: jasmine.SpyObj<AppRequestService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  beforeEach(() => {
    service = jasmine.createSpyObj<AppRequestService>('AppRequestService', ['list', 'update', 'attachment']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);
    service.list.and.returnValue(of([item()]));
    service.attachment.and.returnValue(of(new Blob([new Uint8Array([1, 2, 3])], { type: 'image/png' })));
    TestBed.configureTestingModule({
      imports: [AdminBesoinsComponent, NoopAnimationsModule],
      providers: [
        { provide: AppRequestService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    fixture = TestBed.createComponent(AdminBesoinsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function el(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  it('charge les besoins en attente par défaut', () => {
    expect(service.list).toHaveBeenCalledWith('EN_ATTENTE');
    expect(el().textContent).toContain('Le terminal se fige');
    expect(el().textContent).toContain('En attente');
  });

  it('changer de filtre recharge', () => {
    component.filter = null;
    component.load();
    expect(service.list).toHaveBeenCalledWith(null);
  });

  it('ouvrir un besoin montre ses mots, ses pièces et l’état de leur copie, avec l’aperçu de la capture', () => {
    (el().querySelector('.besoin__head') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(el().textContent).toContain('quand je colle une image le terminal se fige');
    expect(el().textContent).toContain('Le collage gèle la saisie.');
    expect(el().textContent).toContain('Copie échouée : Fichier illisible sur la machine (demo.mp4).');
    expect(el().textContent).toContain('En copie…');
    expect(service.attachment).toHaveBeenCalledOnceWith('r-1', 1);
    expect(el().querySelector('img.piece__preview')).not.toBeNull();
  });

  it('pose le statut et la feature', () => {
    service.update.and.returnValue(of(item({ status: 'CADRE', featureRef: 'F-210' })));
    component.toggle(component.items()[0]);
    component.draftStatus = 'CADRE';
    component.draftFeature = ' F-210 ';
    component.save(component.items()[0]);
    expect(service.update).toHaveBeenCalledWith('r-1', { status: 'CADRE', featureRef: 'F-210' });
    expect(component.items()[0].featureRef).toBe('F-210');
    expect(snackBar.open).toHaveBeenCalledWith('Besoin mis à jour.', 'Fermer', jasmine.any(Object));
  });

  it('une référence mal formée n’est pas envoyée', () => {
    component.toggle(component.items()[0]);
    component.draftFeature = 'ticket-12';
    expect(component.featureValid()).toBeFalse();
    component.save(component.items()[0]);
    expect(service.update).not.toHaveBeenCalled();
  });

  it('un refus serveur est dit', () => {
    service.update.and.returnValue(throwError(() => new HttpErrorResponse({
      status: 400, error: { message: 'featureRef : de la forme F-123.' },
    })));
    component.save(component.items()[0]);
    expect(snackBar.open).toHaveBeenCalledWith('featureRef : de la forme F-123.', 'Fermer', jasmine.any(Object));
  });

  it('un chargement en échec est dit', () => {
    service.list.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    component.load();
    expect(snackBar.open).toHaveBeenCalledWith('Impossible de charger les besoins.', 'Fermer', jasmine.any(Object));
  });

  it('les tailles se lisent', () => {
    expect(sizeLabel(12)).toBe('12 o');
    expect(sizeLabel(2048)).toBe('2 Ko');
    expect(sizeLabel(5_000_000)).toBe('4,8 Mo');
  });
});
