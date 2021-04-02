-- :name select-log :query :one
SELECT * FROM car_logs WHERE id=:id;

-- :name select-logs-by-trip-id :query :many
SELECT * FROM car_logs WHERE trip_id=:id ORDER BY ts ASC;

-- :name create-log :execute :affected
INSERT INTO car_logs (id, trip_id, message, ts, log_level)
VALUES (:id, :trip_id, :msg, NOW(), :log_level);
