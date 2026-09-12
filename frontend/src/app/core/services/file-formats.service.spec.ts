import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { FileFormatsService } from './file-formats.service';
import { FileFormats } from '../models/file-formats.models';

/**
 * F-85 / SF-85-01 — l'`accept` des sélecteurs est **dérivé** de la liste blanche du serveur.
 *
 * <p>Le test central est `aTypeAddedOnTheServerAppearsWithoutTouchingTheScreen` : c'est lui qui
 * empêche l'écran et le serveur de diverger. Si un jour quelqu'un réintroduit une liste écrite dans
 * le frontend, il tombe.</p>
 */
describe('FileFormatsService', () => {
  let service: FileFormatsService;
  let httpMock: HttpTestingController;

  const serverAnswer = (mediaTypes: string[]): FileFormats => ({
    documents: { mediaTypes, maxBytes: 20971520 },
    attachments: { mediaTypes: ['application/pdf', 'text/csv'], maxBytes: 33554432 },
  });

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), FileFormatsService],
    });
    service = TestBed.inject(FileFormatsService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it("dérive l'accept des types renvoyés par le serveur", () => {
    service.load();
    httpMock
      .expectOne('/api/file-formats')
      .flush(serverAnswer(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff']));

    expect(service.accept('documents')).toBe(
      'application/pdf,image/png,image/jpeg,image/tiff',
    );
    expect(service.accept('attachments')).toBe('application/pdf,text/csv');
    expect(service.profile('documents')?.maxBytes).toBe(20971520);
  });

  it('un type ajouté au serveur apparaît sans toucher à une ligne de cet écran', () => {
    // La seule chose qui change entre ce test et le précédent est ce que le serveur répond.
    service.load();
    httpMock
      .expectOne('/api/file-formats')
      .flush(serverAnswer(['application/pdf', 'image/png', 'image/jpeg', 'image/tiff', 'image/bmp']));

    expect(service.accept('documents')).toContain('image/bmp');
  });

  it("ne lance qu'un seul appel, quel que soit le nombre d'écrans qui le demandent", () => {
    service.load();
    service.load();
    service.load();

    httpMock.expectOne('/api/file-formats').flush(serverAnswer(['application/pdf']));
    httpMock.verify();
  });

  it("laisse l'accept vide si le serveur ne répond pas — aucune liste de secours recopiée", () => {
    service.load();
    httpMock
      .expectOne('/api/file-formats')
      .flush({ message: 'boom' }, { status: 500, statusText: 'Server Error' });

    expect(service.accept('documents')).toBe('');
    expect(service.profile('documents')).toBeNull();
  });
});
