import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NoopAnimationsModule } from '@angular/platform-browser/animations';
import { of } from 'rxjs';

import { McpConnectionsComponent } from './mcp-connections.component';
import { McpConnectionsService } from '../core/services/mcp-connections.service';
import { CreatedMcpToken, McpToken } from '../core/models/mcp-connections.models';

describe('McpConnectionsComponent', () => {
  let fixture: ComponentFixture<McpConnectionsComponent>;
  let component: McpConnectionsComponent;
  let service: jasmine.SpyObj<McpConnectionsService>;

  const token: McpToken = {
    id: 't1',
    name: 'CI',
    prefix: 'cgmcp_ab12',
    scopes: ['compte:lire'],
    hostIds: [],
    createdAt: '2026-09-14T10:00:00Z',
    expiresAt: '2026-10-14T10:00:00Z',
    lastUsedAt: null,
    revoked: false,
    expired: false,
  };

  beforeEach(async () => {
    service = jasmine.createSpyObj<McpConnectionsService>('McpConnectionsService', [
      'listTokens',
      'createToken',
      'revokeToken',
      'journal',
      'hosts',
    ]);
    service.listTokens.and.returnValue(of([token]));
    service.journal.and.returnValue(of([]));
    service.hosts.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [McpConnectionsComponent, NoopAnimationsModule],
      providers: [{ provide: McpConnectionsService, useValue: service }],
    }).compileComponents();

    fixture = TestBed.createComponent(McpConnectionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('loads tokens, journal and hosts on init', () => {
    expect(service.listTokens).toHaveBeenCalled();
    expect(service.journal).toHaveBeenCalled();
    expect(service.hosts).toHaveBeenCalled();
    expect(component.tokens().length).toBe(1);
  });

  it('creates a token and shows the secret once', () => {
    const created: CreatedMcpToken = { secret: 'cgmcp_secret', token };
    service.createToken.and.returnValue(of(created));

    component.form.controls.name.setValue('CI');
    component.form.controls.expiresInDays.setValue(30);
    component.create();

    expect(service.createToken).toHaveBeenCalledWith(
      jasmine.objectContaining({ name: 'CI', expiresInDays: 30, scopes: ['compte:lire'] }),
    );
    expect(component.createdSecret()?.secret).toBe('cgmcp_secret');
  });

  it('does not create when no scope is selected', () => {
    component.selectedScopes.set(new Set());
    component.form.controls.name.setValue('CI');
    component.create();
    expect(service.createToken).not.toHaveBeenCalled();
  });
});
