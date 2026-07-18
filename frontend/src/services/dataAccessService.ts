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

export async function getWorkshops(): Promise<Workshop[]> {
  return api.get<Workshop[], Workshop[]>("/knowledge-access/workshops");
}

export async function createWorkshop(payload: Pick<Workshop, "code" | "name">): Promise<Workshop> {
  return api.post<Workshop, Workshop>("/knowledge-access/workshops", payload);
}

export async function getWorkshopTeams(workshopId: string): Promise<WorkshopTeam[]> {
  return api.get<WorkshopTeam[], WorkshopTeam[]>(`/knowledge-access/workshops/${workshopId}/teams`);
}

export async function createWorkshopTeam(
  workshopId: string,
  payload: Pick<WorkshopTeam, "code" | "name">
): Promise<WorkshopTeam> {
  return api.post<WorkshopTeam, WorkshopTeam>(`/knowledge-access/workshops/${workshopId}/teams`, payload);
}

export async function getUserDataScopes(userId: string): Promise<UserDataScope[]> {
  return api.get<UserDataScope[], UserDataScope[]>(`/knowledge-access/users/${userId}/scopes`);
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
  return api.get<KnowledgeResourceScope[], KnowledgeResourceScope[]>(
    `/knowledge-access/${resourceType}/${resourceId}/scopes`
  );
}

export async function replaceKnowledgeResourceScopes(
  resourceType: KnowledgeResourceType,
  resourceId: string,
  scopes: ScopeAssignment<KnowledgeResourceScopeType>[]
): Promise<void> {
  await api.put(`/knowledge-access/${resourceType}/${resourceId}/scopes`, { scopes });
}
