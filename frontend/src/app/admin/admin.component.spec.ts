import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { AdminComponent } from './admin.component';
import { AdminService } from './admin.service';
import { AdminUser } from './admin.models';
import { AuthService } from '../core/services/auth.service';

describe('AdminComponent', () => {
  let fixture: ComponentFixture<AdminComponent>;
  let adminSpy: jasmine.SpyObj<AdminService>;

  const users: AdminUser[] = [
    {
      id: '1',
      email: 'a@example.com',
      role: 'ADMIN',
      createdAt: '2026-07-01T00:00:00Z',
      planCode: 'PRO',
      subscriptionStatus: 'ACTIVE',
      currentPeriodEnd: null,
      totalTokens: 1234,
    },
    {
      id: '2',
      email: 'b@example.com',
      role: 'USER',
      createdAt: '2026-07-01T00:00:00Z',
      planCode: null,
      subscriptionStatus: null,
      currentPeriodEnd: null,
      totalTokens: 0,
    },
  ];

  beforeEach(async () => {
    adminSpy = jasmine.createSpyObj<AdminService>('AdminService', ['getUsers']);
    adminSpy.getUsers.and.returnValue(of(users));

    await TestBed.configureTestingModule({
      imports: [AdminComponent],
      providers: [provideNoopAnimations(), { provide: AdminService, useValue: adminSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(AdminComponent);
    fixture.detectChanges();
  });

  it('charge et affiche les utilisateurs', () => {
    expect(adminSpy.getUsers).toHaveBeenCalled();
    expect(fixture.componentInstance.dataSource.data.length).toBe(2);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('a@example.com');
    expect(text).toContain('ADMIN');
  });
});

describe('AuthService.isAdmin', () => {
  function tokenWithRole(role: string): string {
    return `header.${btoa(JSON.stringify({ role }))}.sig`;
  }

  it('détecte le rôle ADMIN depuis le claim du JWT', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    const auth = TestBed.inject(AuthService);

    auth.storeToken(tokenWithRole('ADMIN'));
    expect(auth.isAdmin()).toBeTrue();

    auth.storeToken(tokenWithRole('USER'));
    expect(auth.isAdmin()).toBeFalse();

    auth.clearToken();
  });
});
