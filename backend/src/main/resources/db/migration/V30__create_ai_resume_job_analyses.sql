CREATE TABLE ai_resume_job_analyses (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    resume_id VARCHAR(64) NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    job_description TEXT NOT NULL,
    resume_content_hash VARCHAR(64) NOT NULL,
    prompt_version VARCHAR(32) NOT NULL,
    result_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_ai_resume_job_analyses_user_resume_created
    ON ai_resume_job_analyses (user_id, resume_id, created_at DESC);
