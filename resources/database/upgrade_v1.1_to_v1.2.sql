-- ragent v1.1 -> v1.2 upgrade script
-- t_message: add deep-thinking content and duration fields

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_content TEXT DEFAULT NULL;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS thinking_duration INT DEFAULT NULL;
