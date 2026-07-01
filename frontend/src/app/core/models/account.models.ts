/** Contrats DTO de l'API RGPD du compte (F-11). Figés par le backend (SF-11-01). */

import { AuthProvider, UserRole } from './auth.models';

export interface AccountExportAccount {
  id: string;
  email: string;
  emailVerified: boolean;
  provider: AuthProvider;
  role: UserRole;
  createdAt: string;
}

export interface AccountExportSubscription {
  status: string;
  planCode: string | null;
  trialEndsAt: string | null;
  currentPeriodEnd: string | null;
}

export interface AccountExportUsage {
  periodStart: string;
  inputTokens: number;
  outputTokens: number;
}

export interface AccountExportMessage {
  role: string;
  content: string;
  model: string | null;
  createdAt: string;
}

export interface AccountExportConversation {
  id: string;
  title: string;
  model: string;
  createdAt: string;
  messages: AccountExportMessage[];
}

export interface AccountExportUploadedFile {
  filename: string;
  mediaType: string;
  sizeBytes: number;
  createdAt: string;
}

/** Document d'export RGPD complet (GET /api/account/export). */
export interface AccountExport {
  exportedAt: string;
  account: AccountExportAccount;
  subscription: AccountExportSubscription | null;
  usage: AccountExportUsage[];
  conversations: AccountExportConversation[];
  uploadedFiles: AccountExportUploadedFile[];
}
