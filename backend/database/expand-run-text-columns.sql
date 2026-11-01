-- Existing MariaDB databases created with the earlier default-sized @Lob mapping
-- may contain TINYTEXT (255 bytes). Widen both columns without deleting records.
-- Run this against the application database before deploying the corrected mapping.
ALTER TABLE run_text_entity MODIFY COLUMN text LONGTEXT CHARACTER SET utf8mb4 NOT NULL;
ALTER TABLE run_log_entity MODIFY COLUMN message LONGTEXT CHARACTER SET utf8mb4 NOT NULL;
