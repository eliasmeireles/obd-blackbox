package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/eliasmeireles/obd-blackbox/backend/internal/telemetry"
)

// logQuery parses optional exact filters, inclusive time bounds and ordering.
func logQuery(r *http.Request) (telemetry.LogQuery, error) {
	values := r.URL.Query()
	q := telemetry.LogQuery{Level: values.Get("level"), Tag: values.Get("tag"), Device: values.Get("device"), SessionID: values.Get("session_id"), Limit: 500, Order: "asc"}
	for _, key := range []string{"level", "tag", "device", "session_id", "limit", "order", "since", "until"} {
		if values.Has(key) && values.Get(key) == "" {
			return q, errors.New("invalid " + key)
		}
	}
	var err error
	q.Since, err = logTime(values.Get("since"))
	if err != nil {
		return q, errors.New("invalid since")
	}
	q.Until, err = logTime(values.Get("until"))
	if err != nil {
		return q, errors.New("invalid until")
	}
	if values.Has("limit") {
		q.Limit, err = strconv.Atoi(values.Get("limit"))
		if err != nil {
			return q, errors.New("invalid limit")
		}
	}
	if values.Has("order") {
		q.Order = values.Get("order")
	}
	return q, telemetry.ValidateLogQuery(q)
}

// logTime accepts RFC3339 timestamps or signed Unix milliseconds.
func logTime(value string) (*time.Time, error) {
	if value == "" {
		return nil, nil
	}
	n, err := strconv.ParseInt(value, 10, 64)
	if err == nil {
		t := time.UnixMilli(n)
		return &t, nil
	}
	t, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		return nil, err
	}
	return &t, nil
}
