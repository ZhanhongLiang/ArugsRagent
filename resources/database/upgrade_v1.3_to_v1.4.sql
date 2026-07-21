-- PostgreSQL upgrade: document-level business metadata for vector retrieval filters
-- Apply after upgrade_v1.2_to_v1.3.sql.

ALTER TABLE t_knowledge_document
    ADD COLUMN IF NOT EXISTS metadata_json JSONB;

COMMENT ON COLUMN t_knowledge_document.metadata_json IS '面向检索的业务元数据JSON';
