CREATE TABLE IF NOT EXISTS variant_analysis_acmg_source (
    analysis_id CHAR(36) PRIMARY KEY,
    snapshot_sha256 VARCHAR(64) NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    FOREIGN KEY (analysis_id) REFERENCES variant_analysis_job(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS variant_analysis_acmg_run (
    run_id VARCHAR(64) PRIMARY KEY,
    analysis_id CHAR(36) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    response_sha256 VARCHAR(64),
    response_json LONGTEXT,
    private_json LONGTEXT,
    UNIQUE (analysis_id, request_sha256),
    FOREIGN KEY (analysis_id) REFERENCES variant_analysis_job(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS variant_analysis_acmg_chunk (
    run_id VARCHAR(64) NOT NULL,
    column_name VARCHAR(16) NOT NULL,
    chunk_index INT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    payload_text LONGTEXT NOT NULL,
    PRIMARY KEY (run_id, column_name, chunk_index),
    FOREIGN KEY (run_id) REFERENCES variant_analysis_acmg_run(run_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
