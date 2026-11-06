CREATE TABLE app_logs (
 id UUID PRIMARY KEY,
 device TEXT NOT NULL,
 t TIMESTAMPTZ NOT NULL,
 level TEXT NOT NULL,
 tag TEXT NOT NULL,
 message TEXT NOT NULL,
 session_id UUID NULL,
 received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX app_logs_t ON app_logs (t DESC);
CREATE INDEX app_logs_device_t ON app_logs (device, t DESC);
