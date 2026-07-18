-- PostgreSQL upgrade: workshop/team two-level knowledge data access control
-- Apply after upgrade_v1.1_to_v1.2.sql. Existing resources remain inaccessible
-- to non-admin users until an administrator explicitly assigns their scope.

CREATE TABLE IF NOT EXISTS t_workshop (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_workshop_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS t_workshop_team (
    id          VARCHAR(20)  NOT NULL PRIMARY KEY,
    workshop_id VARCHAR(20)  NOT NULL,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    enabled     SMALLINT     NOT NULL DEFAULT 1,
    create_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT     NOT NULL DEFAULT 0,
    CONSTRAINT uk_workshop_team_code UNIQUE (workshop_id, code)
);
CREATE INDEX IF NOT EXISTS idx_workshop_team_workshop_id ON t_workshop_team (workshop_id);

CREATE TABLE IF NOT EXISTS t_user_data_scope (
    id          VARCHAR(20) NOT NULL PRIMARY KEY,
    user_id     VARCHAR(20) NOT NULL,
    scope_type  VARCHAR(16) NOT NULL,
    workshop_id VARCHAR(20) NOT NULL,
    team_id     VARCHAR(20),
    create_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_user_data_scope_type CHECK (
        (scope_type = 'WORKSHOP' AND team_id IS NULL)
        OR (scope_type = 'TEAM' AND team_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_user_data_scope ON t_user_data_scope
    (user_id, scope_type, workshop_id, COALESCE(team_id, ''));
CREATE INDEX IF NOT EXISTS idx_user_data_scope_user_id ON t_user_data_scope (user_id);

CREATE TABLE IF NOT EXISTS t_knowledge_resource_scope (
    id            VARCHAR(20) NOT NULL PRIMARY KEY,
    resource_type VARCHAR(32) NOT NULL,
    resource_id   VARCHAR(20) NOT NULL,
    scope_type    VARCHAR(16) NOT NULL,
    workshop_id   VARCHAR(20),
    team_id       VARCHAR(20),
    created_by    VARCHAR(20),
    create_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_knowledge_resource_type CHECK (resource_type IN ('KNOWLEDGE_BASE', 'DOCUMENT')),
    CONSTRAINT ck_knowledge_resource_scope_type CHECK (
        (scope_type = 'GLOBAL' AND workshop_id IS NULL AND team_id IS NULL)
        OR (scope_type = 'WORKSHOP' AND workshop_id IS NOT NULL AND team_id IS NULL)
        OR (scope_type = 'TEAM' AND workshop_id IS NOT NULL AND team_id IS NOT NULL)
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_knowledge_resource_scope ON t_knowledge_resource_scope
    (resource_type, resource_id, scope_type, COALESCE(workshop_id, ''), COALESCE(team_id, ''));
CREATE INDEX IF NOT EXISTS idx_knowledge_resource_scope_resource
    ON t_knowledge_resource_scope (resource_type, resource_id);

COMMENT ON TABLE t_workshop IS '车间组织单元表';
COMMENT ON TABLE t_workshop_team IS '车间下属班组表';
COMMENT ON TABLE t_user_data_scope IS '用户车间和班组数据范围表';
COMMENT ON TABLE t_knowledge_resource_scope IS '知识库和文档的车间班组访问范围表';
