ALTER TABLE sessions ADD COLUMN device TEXT NOT NULL DEFAULT '';
ALTER TABLE sessions ALTER COLUMN vehicle SET DEFAULT 'obd-ii';
CREATE INDEX sessions_device_started_at ON sessions(device, started_at DESC);
