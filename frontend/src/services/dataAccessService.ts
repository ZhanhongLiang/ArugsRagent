import { api } from "@/services/api";

export type DataScopeType = "WORKSHOP" | "TEAM";
export type KnowledgeResourceScopeType = "GLOBAL" | "WORKSHOP" | "TEAM";
export type KnowledgeResourceType = "KNOWLEDGE_BASE" | "DOCUMENT";

export interface Workshop {
  id: string;
  code: string;
  name: string;
  enabled: number;
}

export interface WorkshopTeam {
  id: string;
  workshopId: string;
  code: string;
  name: string;
  enabled: number;
}

export interface UserDataScope {
  id: string;
  userId: string;
  scopeType: DataScopeType;
  workshopId: string;
  teamId?: string | null;
}

export interface KnowledgeResourceScope {
  id: string;
  resourceType: KnowledgeResourceType;
  resourceId: string;
  scopeType: KnowledgeResourceScopeType;
  workshopId?: string | null;
  teamId?: string | null;
}

export interface ScopeAssignment<T extends DataScopeType | KnowledgeResourceScopeType> {
  scopeType: T;
  workshopId?: string | null;
  teamId?: string | null;
}

function requireArrayResponse<T>(endpoint: string, payload: unknown): T[] {
  if (!Array.isArray(payload)) {
    throw new Error(`数据权限接口 ${endpoint} 返回了非数组数据`);
  }
  return payload as T[];
}

export async function getWorkshops(): Promise<Workshop[]> {
  const endpoint = "/knowledge-access/workshops";
  return requireArrayResponse<Workshop>(endpoint, await api.get<unknown, unknown>(endpoint));
}

export async function createWorkshop(payload: Pick<Workshop, "code" | "name">): Promise<Workshop> {
  return api.post<Workshop, Workshop>("/knowledge-access/workshops", payload);
}

export async function getWorkshopTeams(workshopId: string): Promise<WorkshopTeam[]> {
  const endpoint = `/knowledge-access/workshops/${workshopId}/teams`;
  return requireArrayResponse<WorkshopTeam>(endpoint, await api.get<unknown, unknown>(endpoint));
}

export async function createWorkshopTeam(
  workshopId: string,
  payload: Pick<WorkshopTeam, "code" | "name">
): Promise<WorkshopTeam> {
  return api.post<WorkshopTeam, WorkshopTeam>(`/knowledge-access/workshops/${workshopId}/teams`, payload);
}

export async function getUserDataScopes(userId: string): Promise<UserDataScope[]> {
  const endpoint = `/knowledge-access/users/${userId}/scopes`;
  return requireArrayResponse<UserDataScope>(endpoint, await api.get<unknown, unknown>(endpoint));
}

export async function replaceUserDataScopes(
  userId: string,
  scopes: ScopeAssignment<DataScopeType>[]
): Promise<void> {
  await api.put(`/knowledge-access/users/${userId}/scopes`, { scopes });
}

export async function getKnowledgeResourceScopes(
  resourceType: KnowledgeResourceType,
  resourceId: string
): Promise<KnowledgeResourceScope[]> {
  const endpoint = `/knowledge-access/${resourceType}/${resourceId}/scopes`;
  return requireArrayResponse<KnowledgeResourceScope>(
    endpoint,
    await api.get<unknown, unknown>(endpoint)
  );
}

export async function replaceKnowledgeResourceScopes(
  resourceType: KnowledgeResourceType,
  resourceId: string,
  scopes: ScopeAssignment<KnowledgeResourceScopeType>[]
): Promise<void> {
  await api.put(`/knowledge-access/${resourceType}/${resourceId}/scopes`, { scopes });
}
