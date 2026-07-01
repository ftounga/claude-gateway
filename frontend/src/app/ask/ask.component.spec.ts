import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';

import { AskComponent } from './ask.component';
import { AskService } from '../core/services/ask.service';
import { AskResponse } from '../core/models/ask.models';

describe('AskComponent', () => {
  let fixture: ComponentFixture<AskComponent>;
  let component: AskComponent;
  let service: jasmine.SpyObj<AskService>;
  let snackBar: jasmine.SpyObj<MatSnackBar>;

  const grounded: AskResponse = {
    answer: 'La confidentialité dure 5 ans [contrat.pdf:-:0].',
    model: 'claude-opus-4-8',
    grounded: true,
    citations: [
      {
        documentId: 'doc-1',
        filename: 'contrat.pdf',
        page: null,
        chunkIndex: 0,
        snippet: 'Clause de confidentialité.',
      },
    ],
  };

  function setup(): void {
    service = jasmine.createSpyObj<AskService>('AskService', ['ask']);
    snackBar = jasmine.createSpyObj<MatSnackBar>('MatSnackBar', ['open']);

    TestBed.configureTestingModule({
      imports: [AskComponent],
      providers: [
        provideNoopAnimations(),
        provideRouter([]),
        { provide: AskService, useValue: service },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });

    fixture = TestBed.createComponent(AskComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('disables submit when the question is blank', () => {
    setup();
    component.question.set('   ');
    expect(component.isBlank()).toBeTrue();
    component.submit();
    expect(service.ask).not.toHaveBeenCalled();
  });

  it('submits the question and displays the grounded answer with citations', () => {
    setup();
    service.ask.and.returnValue(of(grounded));
    component.question.set('Quelle confidentialité ?');

    component.submit();

    expect(service.ask).toHaveBeenCalledWith({ question: 'Quelle confidentialité ?' });
    expect(component.answer()?.answer).toBe(grounded.answer);
    expect(component.answer()?.citations.length).toBe(1);
    expect(component.loading()).toBeFalse();
  });

  it('shows the ungrounded banner when the answer is not grounded', () => {
    setup();
    const ungrounded: AskResponse = { ...grounded, grounded: false, citations: [] };
    service.ask.and.returnValue(of(ungrounded));
    component.question.set('Une question sans document');

    component.submit();
    fixture.detectChanges();

    expect(component.answer()?.grounded).toBeFalse();
    const banner = fixture.nativeElement.querySelector('.ask__ungrounded');
    expect(banner).not.toBeNull();
  });

  it('notifies via snackbar on quota exceeded (402) without crashing', () => {
    setup();
    service.ask.and.returnValue(throwError(() => new HttpErrorResponse({ status: 402 })));
    component.question.set('Une question');

    component.submit();

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
    expect(component.answer()).toBeNull();
  });

  it('notifies via snackbar on provider unavailable (503)', () => {
    setup();
    service.ask.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));
    component.question.set('Une question');

    component.submit();

    expect(snackBar.open).toHaveBeenCalled();
    expect(component.loading()).toBeFalse();
  });
});
