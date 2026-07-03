/** Vue admin d'un utilisateur (réponse de `GET /api/admin/users`, F-20). */
export interface AdminUser {
  id: string;
  email: string;
  role: string;
  createdAt: string;
  planCode: string | null;
  subscriptionStatus: string | null;
  currentPeriodEnd: string | null;
  totalTokens: number;
}
