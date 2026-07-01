import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';

import { DeleteAccountDialogComponent } from './delete-account-dialog.component';

describe('DeleteAccountDialogComponent', () => {
  let component: DeleteAccountDialogComponent;
  let dialogRef: jasmine.SpyObj<MatDialogRef<DeleteAccountDialogComponent, boolean>>;

  beforeEach(() => {
    dialogRef = jasmine.createSpyObj('MatDialogRef', ['close']);
    TestBed.configureTestingModule({
      imports: [DeleteAccountDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: { email: 'Alice@Example.com' } },
      ],
    });
    const fixture = TestBed.createComponent(DeleteAccountDialogComponent);
    component = fixture.componentInstance;
  });

  it('matches the account email case- and space-insensitively', () => {
    component.confirmation.set('  alice@example.com ');
    expect(component.matches()).toBeTrue();
  });

  it('does not match a different email', () => {
    component.confirmation.set('bob@example.com');
    expect(component.matches()).toBeFalse();
  });

  it('confirms only when the email matches', () => {
    component.confirmation.set('wrong@example.com');
    component.confirm();
    expect(dialogRef.close).not.toHaveBeenCalled();

    component.confirmation.set('alice@example.com');
    component.confirm();
    expect(dialogRef.close).toHaveBeenCalledWith(true);
  });

  it('cancels with false', () => {
    component.cancel();
    expect(dialogRef.close).toHaveBeenCalledWith(false);
  });
});
