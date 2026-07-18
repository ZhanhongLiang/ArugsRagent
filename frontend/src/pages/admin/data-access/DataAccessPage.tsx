import { useCallback, useEffect, useMemo, useState } from "react";
import { Building2, FileKey, FolderKey, Plus, RefreshCw, Save, ShieldCheck, Trash2, UsersRound } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import {
  createWorkshop,
  createWorkshopTeam,
  getKnowledgeResourceScopes,
  getUserDataScopes,
  getWorkshops,
  getWorkshopTeams,
  replaceKnowledgeResourceScopes,
  replaceUserDataScopes,
  type DataScopeType,
  type KnowledgeResourceScopeType,
  type KnowledgeResourceType,
  type ScopeAssignment,
  type Workshop,
  type WorkshopTeam
} from "@/services/dataAccessService";
import { getDocuments, getKnowledgeBases, type KnowledgeBase, type KnowledgeDocument } from "@/services/knowledgeService";
import { getUsersPage, type UserItem } from "@/services/userService";
import { getErrorMessage } from "@/utils/error";

type ScopeDraft<T extends DataScopeType | KnowledgeResourceScopeType> = ScopeAssignment<T> & {
  key: string;
};

const USER_SCOPE_OPTIONS: Array<{ value: DataScopeType; label: string }> = [
  { value: "WORKSHOP", label: "整个车间" },
  { value: "TEAM", label: "指定班组" }
];

const RESOURCE_SCOPE_OPTIONS: Array<{ value: KnowledgeResourceScopeType; label: string }> = [
  { value: "GLOBAL", label: "全体已登录用户" },
  { value: "WORKSHOP", label: "指定车间" },
  { value: "TEAM", label: "指定班组" }
];

function createDraft<T extends DataScopeType | KnowledgeResourceScopeType>(scopeType: T): ScopeDraft<T> {
  return {
    key: `${scopeType}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    scopeType,
    workshopId: "",
    teamId: ""
  };
}

function toUserScopeDrafts(
  scopes: Array<{ id: string; scopeType: DataScopeType; workshopId: string; teamId?: string | null }>
): ScopeDraft<DataScopeType>[] {
  return scopes.map((scope) => ({
    key: scope.id,
    scopeType: scope.scopeType,
    workshopId: scope.workshopId,
    teamId: scope.teamId || ""
  }));
}

function toResourceScopeDrafts(
  scopes: Array<{
    id: string;
    scopeType: KnowledgeResourceScopeType;
    workshopId?: string | null;
    teamId?: string | null;
  }>
): ScopeDraft<KnowledgeResourceScopeType>[] {
  return scopes.map((scope) => ({
    key: scope.id,
    scopeType: scope.scopeType,
    workshopId: scope.workshopId || "",
    teamId: scope.teamId || ""
  }));
}

export function DataAccessPage() {
  const [workshops, setWorkshops] = useState<Workshop[]>([]);
  const [teams, setTeams] = useState<WorkshopTeam[]>([]);
  const [teamsByWorkshop, setTeamsByWorkshop] = useState<Record<string, WorkshopTeam[]>>({});
  const [users, setUsers] = useState<UserItem[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBase[]>([]);
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [selectedWorkshopId, setSelectedWorkshopId] = useState("");
  const [selectedUserId, setSelectedUserId] = useState("");
  const [selectedKnowledgeBaseId, setSelectedKnowledgeBaseId] = useState("");
  const [resourceType, setResourceType] = useState<KnowledgeResourceType>("KNOWLEDGE_BASE");
  const [selectedDocumentId, setSelectedDocumentId] = useState("");
  const [userScopes, setUserScopes] = useState<ScopeDraft<DataScopeType>[]>([]);
  const [resourceScopes, setResourceScopes] = useState<ScopeDraft<KnowledgeResourceScopeType>[]>([]);
  const [loading, setLoading] = useState(true);
  const [savingUserScopes, setSavingUserScopes] = useState(false);
  const [savingResourceScopes, setSavingResourceScopes] = useState(false);
  const [workshopDialogOpen, setWorkshopDialogOpen] = useState(false);
  const [teamDialogOpen, setTeamDialogOpen] = useState(false);
  const [workshopForm, setWorkshopForm] = useState({ code: "", name: "" });
  const [teamForm, setTeamForm] = useState({ code: "", name: "" });
  const [creatingOrganization, setCreatingOrganization] = useState(false);

  const selectedWorkshop = useMemo(
    () => workshops.find((workshop) => workshop.id === selectedWorkshopId) || null,
    [selectedWorkshopId, workshops]
  );
  const selectedUser = useMemo(
    () => users.find((user) => user.id === selectedUserId) || null,
    [selectedUserId, users]
  );
  const selectedKnowledgeBase = useMemo(
    () => knowledgeBases.find((knowledgeBase) => knowledgeBase.id === selectedKnowledgeBaseId) || null,
    [knowledgeBases, selectedKnowledgeBaseId]
  );
  const selectedDocument = useMemo(
    () => documents.find((document) => document.id === selectedDocumentId) || null,
    [documents, selectedDocumentId]
  );
  const resourceId = resourceType === "KNOWLEDGE_BASE" ? selectedKnowledgeBaseId : selectedDocumentId;
  const resourceLabel = resourceType === "KNOWLEDGE_BASE" ? selectedKnowledgeBase?.name : selectedDocument?.docName;

  const cacheWorkshopTeams = useCallback(async (workshopId: string): Promise<WorkshopTeam[]> => {
    try {
      const workshopTeams = await getWorkshopTeams(workshopId);
      setTeamsByWorkshop((current) => ({ ...current, [workshopId]: workshopTeams }));
      return workshopTeams;
    } catch (error) {
      toast.error(getErrorMessage(error, "加载班组失败"));
      return [];
    }
  }, []);

  const teamNameMap = useMemo(() => {
    const names = new Map<string, string>();
    Object.values(teamsByWorkshop).flat().forEach((team) => names.set(team.id, team.name));
    return names;
  }, [teamsByWorkshop]);
  const workshopNameMap = useMemo(() => {
    const names = new Map<string, string>();
    workshops.forEach((workshop) => names.set(workshop.id, workshop.name));
    return names;
  }, [workshops]);

  const loadReferenceData = async () => {
    try {
      setLoading(true);
      const [nextWorkshops, nextUsers, nextKnowledgeBases] = await Promise.all([
        getWorkshops(),
        getUsersPage(1, 200),
        getKnowledgeBases(1, 200)
      ]);
      setWorkshops(nextWorkshops);
      setUsers(nextUsers.records || []);
      setKnowledgeBases(nextKnowledgeBases);
      setSelectedWorkshopId((current) => current || nextWorkshops[0]?.id || "");
      setSelectedKnowledgeBaseId((current) => current || nextKnowledgeBases[0]?.id || "");
    } catch (error) {
      toast.error(getErrorMessage(error, "加载权限基础数据失败"));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadReferenceData();
  }, []);

  useEffect(() => {
    if (!selectedWorkshopId) {
      setTeams([]);
      return;
    }
    void cacheWorkshopTeams(selectedWorkshopId).then(setTeams);
  }, [cacheWorkshopTeams, selectedWorkshopId]);

  useEffect(() => {
    if (!selectedUserId) {
      setUserScopes([]);
      return;
    }
    void getUserDataScopes(selectedUserId)
      .then((scopes) => {
        setUserScopes(toUserScopeDrafts(scopes));
        const teamWorkshopIds = [...new Set(scopes
          .filter((scope) => scope.scopeType === "TEAM")
          .map((scope) => scope.workshopId))];
        void Promise.all(teamWorkshopIds.map(cacheWorkshopTeams));
      })
      .catch((error) => toast.error(getErrorMessage(error, "加载用户数据范围失败")));
  }, [cacheWorkshopTeams, selectedUserId]);

  useEffect(() => {
    if (!selectedKnowledgeBaseId || resourceType !== "DOCUMENT") {
      setDocuments([]);
      setSelectedDocumentId("");
      return;
    }
    void getDocuments(selectedKnowledgeBaseId, { current: 1, size: 200 })
      .then((records) => {
        setDocuments(records);
        setSelectedDocumentId((current) => current || records[0]?.id || "");
      })
      .catch((error) => toast.error(getErrorMessage(error, "加载文档列表失败")));
  }, [resourceType, selectedKnowledgeBaseId]);

  useEffect(() => {
    if (!resourceId) {
      setResourceScopes([]);
      return;
    }
    void getKnowledgeResourceScopes(resourceType, resourceId)
      .then((scopes) => {
        setResourceScopes(toResourceScopeDrafts(scopes));
        const teamWorkshopIds = [...new Set(scopes
          .filter((scope) => scope.scopeType === "TEAM" && scope.workshopId)
          .map((scope) => scope.workshopId as string))];
        void Promise.all(teamWorkshopIds.map(cacheWorkshopTeams));
      })
      .catch((error) => toast.error(getErrorMessage(error, "加载资源授权失败")));
  }, [cacheWorkshopTeams, resourceId, resourceType]);

  const updateUserScope = (key: string, changes: Partial<ScopeDraft<DataScopeType>>) => {
    setUserScopes((current) => current.map((scope) => (scope.key === key ? { ...scope, ...changes } : scope)));
  };

  const updateResourceScope = (key: string, changes: Partial<ScopeDraft<KnowledgeResourceScopeType>>) => {
    setResourceScopes((current) => current.map((scope) => (scope.key === key ? { ...scope, ...changes } : scope)));
  };

  const validateUserScopes = () => {
    for (const scope of userScopes) {
      if (!scope.workshopId) {
        toast.error("用户范围必须选择车间");
        return false;
      }
      if (scope.scopeType === "TEAM" && !scope.teamId) {
        toast.error("班组范围必须选择班组");
        return false;
      }
    }
    return true;
  };

  const validateResourceScopes = () => {
    for (const scope of resourceScopes) {
      if (scope.scopeType === "GLOBAL") continue;
      if (!scope.workshopId) {
        toast.error("资源范围必须选择车间");
        return false;
      }
      if (scope.scopeType === "TEAM" && !scope.teamId) {
        toast.error("班组范围必须选择班组");
        return false;
      }
    }
    return true;
  };

  const saveUserScopes = async () => {
    if (!selectedUserId || !validateUserScopes()) return;
    try {
      setSavingUserScopes(true);
      await replaceUserDataScopes(
        selectedUserId,
        userScopes.map(({ scopeType, workshopId, teamId }) => ({
          scopeType,
          workshopId,
          teamId: scopeType === "TEAM" ? teamId : null
        }))
      );
      toast.success("用户数据范围已保存");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存用户数据范围失败"));
    } finally {
      setSavingUserScopes(false);
    }
  };

  const saveResourceScopes = async () => {
    if (!resourceId || !validateResourceScopes()) return;
    try {
      setSavingResourceScopes(true);
      await replaceKnowledgeResourceScopes(
        resourceType,
        resourceId,
        resourceScopes.map(({ scopeType, workshopId, teamId }) => ({
          scopeType,
          workshopId: scopeType === "GLOBAL" ? null : workshopId,
          teamId: scopeType === "TEAM" ? teamId : null
        }))
      );
      toast.success("资源访问范围已保存");
    } catch (error) {
      toast.error(getErrorMessage(error, "保存资源访问范围失败"));
    } finally {
      setSavingResourceScopes(false);
    }
  };

  const submitWorkshop = async () => {
    const code = workshopForm.code.trim();
    const name = workshopForm.name.trim();
    if (!code || !name) {
      toast.error("请填写车间编码和名称");
      return;
    }
    try {
      setCreatingOrganization(true);
      const workshop = await createWorkshop({ code, name });
      setWorkshops((current) => [...current, workshop].sort((left, right) => left.code.localeCompare(right.code)));
      setSelectedWorkshopId(workshop.id);
      setWorkshopDialogOpen(false);
      setWorkshopForm({ code: "", name: "" });
      toast.success("车间已创建");
    } catch (error) {
      toast.error(getErrorMessage(error, "创建车间失败"));
    } finally {
      setCreatingOrganization(false);
    }
  };

  const submitTeam = async () => {
    const code = teamForm.code.trim();
    const name = teamForm.name.trim();
    if (!selectedWorkshopId || !code || !name) {
      toast.error("请先选择车间，并填写班组编码和名称");
      return;
    }
    try {
      setCreatingOrganization(true);
      const team = await createWorkshopTeam(selectedWorkshopId, { code, name });
      setTeams((current) => [...current, team].sort((left, right) => left.code.localeCompare(right.code)));
      setTeamsByWorkshop((current) => ({
        ...current,
        [selectedWorkshopId]: [...(current[selectedWorkshopId] || []), team]
          .sort((left, right) => left.code.localeCompare(right.code))
      }));
      setTeamDialogOpen(false);
      setTeamForm({ code: "", name: "" });
      toast.success("班组已创建");
    } catch (error) {
      toast.error(getErrorMessage(error, "创建班组失败"));
    } finally {
      setCreatingOrganization(false);
    }
  };

  const renderOrganizationLabel = (scope: ScopeDraft<DataScopeType | KnowledgeResourceScopeType>) => {
    if (scope.scopeType === "GLOBAL") return "全体已登录用户";
    const workshopName = workshopNameMap.get(scope.workshopId || "") || "未选择车间";
    if (scope.scopeType === "WORKSHOP") return workshopName;
    const teamName = teamNameMap.get(scope.teamId || "") || "未选择班组";
    return `${workshopName} / ${teamName}`;
  };

  const renderScopeEditor = <T extends DataScopeType | KnowledgeResourceScopeType>(
    scope: ScopeDraft<T>,
    options: Array<{ value: T; label: string }>,
    onChange: (changes: Partial<ScopeDraft<T>>) => void,
    onRemove: () => void
  ) => {
    const requiresWorkshop = scope.scopeType !== "GLOBAL";
    const requiresTeam = scope.scopeType === "TEAM";
    const canSelectTeam = Boolean(scope.workshopId);
    return (
      <div key={scope.key} className="grid gap-2 rounded-md border border-slate-200 bg-slate-50 p-3 lg:grid-cols-[150px_minmax(0,1fr)_minmax(0,1fr)_auto]">
        <Select
          value={scope.scopeType}
          onValueChange={(value: T) => onChange({ scopeType: value, workshopId: value === "GLOBAL" ? "" : scope.workshopId, teamId: value === "TEAM" ? scope.teamId : "" })}
        >
          <SelectTrigger><SelectValue /></SelectTrigger>
          <SelectContent>
            {options.map((option) => <SelectItem key={option.value} value={option.value}>{option.label}</SelectItem>)}
          </SelectContent>
        </Select>
        {requiresWorkshop ? (
          <Select value={scope.workshopId || undefined} onValueChange={(workshopId) => {
            onChange({ workshopId, teamId: "" });
            if (scope.scopeType === "TEAM") void cacheWorkshopTeams(workshopId);
          }}>
            <SelectTrigger><SelectValue placeholder="选择车间" /></SelectTrigger>
            <SelectContent>
              {workshops.map((workshop) => <SelectItem key={workshop.id} value={workshop.id}>{workshop.name} · {workshop.code}</SelectItem>)}
            </SelectContent>
          </Select>
        ) : <div className="flex h-10 items-center rounded-md border border-dashed border-slate-200 bg-white px-3 text-sm text-slate-500">不限制组织范围</div>}
        {requiresTeam ? (
          <Select value={scope.teamId || undefined} onValueChange={(teamId) => onChange({ teamId })} disabled={!canSelectTeam}>
            <SelectTrigger><SelectValue placeholder={canSelectTeam ? "选择班组" : "先选择车间"} /></SelectTrigger>
            <SelectContent>
              {(teamsByWorkshop[scope.workshopId || ""] || []).map((team) => <SelectItem key={team.id} value={team.id}>{team.name} · {team.code}</SelectItem>)}
            </SelectContent>
          </Select>
        ) : <div className="flex h-10 items-center rounded-md border border-dashed border-slate-200 bg-white px-3 text-sm text-slate-500">{scope.scopeType === "WORKSHOP" ? "车间内全部班组" : "无需选择班组"}</div>}
        <Button type="button" variant="ghost" size="icon" className="text-slate-500 hover:text-destructive" onClick={onRemove} title="移除范围">
          <Trash2 className="h-4 w-4" />
        </Button>
      </div>
    );
  };

  return (
    <div className="admin-page space-y-6">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">数据权限</h1>
          <p className="admin-page-subtitle">按车间和班组配置用户可见范围，以及知识库和文档访问控制。</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => void loadReferenceData()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" />刷新数据
          </Button>
        </div>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(0,0.95fr)]">
        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <div>
              <CardTitle className="text-base">组织结构</CardTitle>
              <p className="mt-1 text-sm text-muted-foreground">维护车间与下属班组，停用和删除组织单元不属于当前 MVP。</p>
            </div>
            <Button size="sm" onClick={() => setWorkshopDialogOpen(true)}><Plus className="mr-1 h-4 w-4" />新增车间</Button>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
              <div className="overflow-hidden rounded-md border border-slate-200">
                <div className="border-b bg-slate-50 px-4 py-3 text-sm font-medium text-slate-700">车间</div>
                <div className="max-h-[280px] overflow-y-auto">
                  {workshops.length === 0 ? <p className="px-4 py-8 text-center text-sm text-muted-foreground">暂无车间</p> : workshops.map((workshop) => (
                    <button key={workshop.id} type="button" onClick={() => setSelectedWorkshopId(workshop.id)} className={`flex w-full items-center justify-between border-b px-4 py-3 text-left text-sm last:border-b-0 ${selectedWorkshopId === workshop.id ? "bg-primary/5 text-primary" : "hover:bg-slate-50"}`}>
                      <span className="min-w-0"><span className="block truncate font-medium">{workshop.name}</span><span className="block truncate text-xs text-muted-foreground">{workshop.code}</span></span>
                      <Building2 className="h-4 w-4 shrink-0" />
                    </button>
                  ))}
                </div>
              </div>
              <div className="overflow-hidden rounded-md border border-slate-200">
                <div className="flex items-center justify-between border-b bg-slate-50 px-4 py-3">
                  <span className="text-sm font-medium text-slate-700">{selectedWorkshop ? `${selectedWorkshop.name}的班组` : "班组"}</span>
                  <Button size="sm" variant="ghost" className="h-7 px-2" onClick={() => setTeamDialogOpen(true)} disabled={!selectedWorkshopId}>新增</Button>
                </div>
                <div className="max-h-[280px] overflow-y-auto">
                  {!selectedWorkshopId ? <p className="px-4 py-8 text-center text-sm text-muted-foreground">先选择车间</p> : teams.length === 0 ? <p className="px-4 py-8 text-center text-sm text-muted-foreground">暂无班组</p> : teams.map((team) => (
                    <div key={team.id} className="flex items-center gap-3 border-b px-4 py-3 text-sm last:border-b-0">
                      <UsersRound className="h-4 w-4 text-slate-500" /><span className="min-w-0"><span className="block truncate font-medium">{team.name}</span><span className="block truncate text-xs text-muted-foreground">{team.code}</span></span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">授权语义</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-3 sm:grid-cols-3">
            <div className="rounded-md border border-slate-200 p-4"><Badge variant="outline">GLOBAL</Badge><p className="mt-3 text-sm font-medium">公共资料</p><p className="mt-1 text-sm text-muted-foreground">所有已登录用户均可读取。</p></div>
            <div className="rounded-md border border-slate-200 p-4"><Badge variant="outline">WORKSHOP</Badge><p className="mt-3 text-sm font-medium">车间资料</p><p className="mt-1 text-sm text-muted-foreground">需拥有该车间范围，可覆盖下属班组。</p></div>
            <div className="rounded-md border border-slate-200 p-4"><Badge variant="outline">TEAM</Badge><p className="mt-3 text-sm font-medium">班组资料</p><p className="mt-1 text-sm text-muted-foreground">仅指定班组或该车间范围可读。</p></div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <div><CardTitle className="text-base">用户数据范围</CardTitle><p className="mt-1 text-sm text-muted-foreground">范围整体保存；保存时会覆盖该用户之前的车间和班组范围。</p></div>
          <Button size="sm" variant="outline" disabled={!selectedUserId || savingUserScopes} onClick={() => void saveUserScopes()}><Save className="mr-1 h-4 w-4" />保存范围</Button>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="max-w-md"><Select value={selectedUserId || undefined} onValueChange={setSelectedUserId}><SelectTrigger><SelectValue placeholder="选择要授权的用户" /></SelectTrigger><SelectContent>{users.filter((user) => user.role !== "admin").map((user) => <SelectItem key={user.id} value={user.id}>{user.username} · {user.role}</SelectItem>)}</SelectContent></Select></div>
          {!selectedUser ? <div className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">选择普通用户后配置其可访问的车间或班组。</div> : <>
            <div className="flex flex-wrap items-center justify-between gap-3"><div className="text-sm text-muted-foreground">当前用户：<span className="font-medium text-foreground">{selectedUser.username}</span></div><Button size="sm" variant="outline" onClick={() => setUserScopes((current) => [...current, createDraft<DataScopeType>("TEAM")])}><Plus className="mr-1 h-4 w-4" />添加范围</Button></div>
            <div className="space-y-2">{userScopes.length === 0 ? <div className="rounded-md border border-dashed p-5 text-sm text-muted-foreground">当前没有数据范围。保存空列表会撤销该用户的全部范围。</div> : userScopes.map((scope) => renderScopeEditor(scope, USER_SCOPE_OPTIONS, (changes) => updateUserScope(scope.key, changes), () => setUserScopes((current) => current.filter((item) => item.key !== scope.key))))}</div>
          </>}
        </CardContent>
      </Card>

      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <div><CardTitle className="text-base">知识资源访问范围</CardTitle><p className="mt-1 text-sm text-muted-foreground">知识库 ACL 是默认范围；文档 ACL 存在时会覆盖知识库默认范围。</p></div>
          <Button size="sm" variant="outline" disabled={!resourceId || savingResourceScopes} onClick={() => void saveResourceScopes()}><Save className="mr-1 h-4 w-4" />保存访问范围</Button>
        </CardHeader>
        <CardContent className="space-y-4">
          <div className="grid gap-3 lg:grid-cols-[180px_minmax(0,1fr)_minmax(0,1fr)]">
            <Select value={resourceType} onValueChange={(value: KnowledgeResourceType) => setResourceType(value)}><SelectTrigger><SelectValue /></SelectTrigger><SelectContent><SelectItem value="KNOWLEDGE_BASE">知识库</SelectItem><SelectItem value="DOCUMENT">文档</SelectItem></SelectContent></Select>
            <Select value={selectedKnowledgeBaseId || undefined} onValueChange={setSelectedKnowledgeBaseId}><SelectTrigger><SelectValue placeholder="选择知识库" /></SelectTrigger><SelectContent>{knowledgeBases.map((knowledgeBase) => <SelectItem key={knowledgeBase.id} value={knowledgeBase.id}>{knowledgeBase.name}</SelectItem>)}</SelectContent></Select>
            {resourceType === "DOCUMENT" ? <Select value={selectedDocumentId || undefined} onValueChange={setSelectedDocumentId} disabled={!selectedKnowledgeBaseId}><SelectTrigger><SelectValue placeholder={selectedKnowledgeBaseId ? "选择文档" : "先选择知识库"} /></SelectTrigger><SelectContent>{documents.map((document) => <SelectItem key={document.id} value={document.id}>{document.docName}</SelectItem>)}</SelectContent></Select> : <div className="flex h-10 items-center rounded-md border border-dashed border-slate-200 px-3 text-sm text-muted-foreground">知识库默认范围</div>}
          </div>
          {!resourceId ? <div className="rounded-md border border-dashed p-8 text-center text-sm text-muted-foreground">选择知识库或文档后配置访问范围。</div> : <>
            <div className="flex flex-wrap items-center justify-between gap-3"><div className="flex items-center gap-2 text-sm text-muted-foreground">{resourceType === "KNOWLEDGE_BASE" ? <FolderKey className="h-4 w-4" /> : <FileKey className="h-4 w-4" />}<span>当前资源：<span className="font-medium text-foreground">{resourceLabel}</span></span></div><Button size="sm" variant="outline" onClick={() => setResourceScopes((current) => [...current, createDraft<KnowledgeResourceScopeType>("TEAM")])}><Plus className="mr-1 h-4 w-4" />添加范围</Button></div>
            <div className="space-y-2">{resourceScopes.length === 0 ? <div className="rounded-md border border-dashed p-5 text-sm text-muted-foreground">当前资源未配置 ACL。普通用户默认不可读；保存空列表会撤销全部访问范围。</div> : resourceScopes.map((scope) => renderScopeEditor(scope, RESOURCE_SCOPE_OPTIONS, (changes) => updateResourceScope(scope.key, changes), () => setResourceScopes((current) => current.filter((item) => item.key !== scope.key))))}</div>
          </>}
        </CardContent>
      </Card>

      <Dialog open={workshopDialogOpen} onOpenChange={setWorkshopDialogOpen}>
        <DialogContent><DialogHeader><DialogTitle>新增车间</DialogTitle><DialogDescription>车间编码创建后作为组织标识使用。</DialogDescription></DialogHeader><div className="space-y-3"><Input value={workshopForm.code} onChange={(event) => setWorkshopForm((current) => ({ ...current, code: event.target.value }))} placeholder="车间编码，例如 ASSEMBLY" /><Input value={workshopForm.name} onChange={(event) => setWorkshopForm((current) => ({ ...current, name: event.target.value }))} placeholder="车间名称，例如 装配车间" /></div><DialogFooter><Button variant="outline" onClick={() => setWorkshopDialogOpen(false)} disabled={creatingOrganization}>取消</Button><Button onClick={() => void submitWorkshop()} disabled={creatingOrganization}>创建车间</Button></DialogFooter></DialogContent>
      </Dialog>

      <Dialog open={teamDialogOpen} onOpenChange={setTeamDialogOpen}>
        <DialogContent><DialogHeader><DialogTitle>新增班组</DialogTitle><DialogDescription>{selectedWorkshop ? `归属车间：${selectedWorkshop.name}` : "请先选择车间"}</DialogDescription></DialogHeader><div className="space-y-3"><Input value={teamForm.code} onChange={(event) => setTeamForm((current) => ({ ...current, code: event.target.value }))} placeholder="班组编码，例如 TEAM-A" /><Input value={teamForm.name} onChange={(event) => setTeamForm((current) => ({ ...current, name: event.target.value }))} placeholder="班组名称，例如 装配一班" /></div><DialogFooter><Button variant="outline" onClick={() => setTeamDialogOpen(false)} disabled={creatingOrganization}>取消</Button><Button onClick={() => void submitTeam()} disabled={!selectedWorkshopId || creatingOrganization}>创建班组</Button></DialogFooter></DialogContent>
      </Dialog>
    </div>
  );
}
