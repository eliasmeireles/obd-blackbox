CREATE TABLE sessions (
 id UUID PRIMARY KEY, vehicle TEXT NOT NULL DEFAULT 'obd-ii',
 source TEXT NOT NULL CHECK(source IN ('sim','obd')), app_version TEXT NOT NULL DEFAULT '',
 started_at TIMESTAMPTZ NOT NULL, last_sample_at TIMESTAMPTZ, sample_count BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE samples (
 session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, t TIMESTAMPTZ NOT NULL,
 speed_kmh REAL, rpm REAL, coolant_c REAL, load_pct REAL, timing_deg REAL, pedal_pct REAL, throttle_pct REAL,
 cmd_throttle_pct REAL, map_kpa REAL, intake_c REAL, stft_pct REAL, ltft_pct REAL, o2_b1s1_v REAL,
 voltage_v REAL, fuel_level_pct REAL, fuel_rate_lph_est REAL,
 extra JSONB, alerts TEXT[] NOT NULL DEFAULT '{}', event TEXT, PRIMARY KEY(session_id,t)
);
CREATE INDEX samples_t_brin ON samples USING BRIN(t);
CREATE TABLE events (
 session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, t TIMESTAMPTZ NOT NULL,
 label TEXT NOT NULL, automatic BOOLEAN NOT NULL, PRIMARY KEY(session_id,t,label)
);
CREATE INDEX events_t_idx ON events(t DESC);
CREATE TABLE dtcs (
 session_id UUID NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, t TIMESTAMPTZ NOT NULL,
 kind TEXT NOT NULL CHECK(kind IN ('stored','pending','permanent')), code TEXT NOT NULL,
 PRIMARY KEY(session_id,t,kind,code)
);
