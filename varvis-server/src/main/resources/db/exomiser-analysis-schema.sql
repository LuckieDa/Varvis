CREATE TABLE IF NOT EXISTS exomiser_analysis_job (
    analysis_id CHAR(36) NOT NULL,
    schema_version VARCHAR(16) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    ranking_mode VARCHAR(64) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    unavailable_reason VARCHAR(1000) NULL,
    resource_fingerprint CHAR(64) NULL,
    output_sha256 CHAR(64) NULL,
    fallback_used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    PRIMARY KEY (analysis_id),
    INDEX idx_exomiser_analysis_job_status (execution_status),
    INDEX idx_exomiser_analysis_job_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
