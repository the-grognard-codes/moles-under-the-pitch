CREATE TABLE ffb_local_schema (version INT PRIMARY KEY);
INSERT INTO ffb_local_schema VALUES (8);
CREATE TABLE ffb_prepared_matches (
  match_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  document_version INT NOT NULL,
  document_json LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  CHECK (document_version BETWEEN 1 AND 2147483646),
  CHECK (OCTET_LENGTH(document_json) <= 16842752)
) ENGINE=InnoDB;

CREATE TABLE probe_current (
  match_id CHAR(36) PRIMARY KEY, generation BIGINT NOT NULL,
  snapshot LONGBLOB NOT NULL, summary_json TEXT NOT NULL, digest BINARY(32) NOT NULL,
  sealed BOOLEAN NOT NULL DEFAULT FALSE, result_json TEXT
) ENGINE=InnoDB;
CREATE TABLE probe_record (
  match_id CHAR(36) NOT NULL, revision INT NOT NULL, record_bytes LONGBLOB NOT NULL,
  PRIMARY KEY (match_id, revision)
) ENGINE=InnoDB;
CREATE TABLE probe_receipt (
  match_id CHAR(36) NOT NULL, actor VARCHAR(8) NOT NULL, request_id VARCHAR(128) NOT NULL,
  revision INT NOT NULL, fingerprint BINARY(32) NOT NULL,
  PRIMARY KEY (match_id, actor, request_id)
) ENGINE=InnoDB;
