CREATE TABLE diagnostics (
    session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    t TIMESTAMPTZ NOT NULL,
    data JSONB NOT NULL,
    PRIMARY KEY (session_id, t)
);
CREATE INDEX diagnostics_t_idx ON diagnostics (t DESC);
