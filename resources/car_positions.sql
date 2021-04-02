--:name create-position :execute :affected
INSERT INTO car_positions (id, car_id, pos_lat, pos_lon, created) VALUES (:id, :car_id, :pos_lat, :pos_lon, now());

--:name get-latest-position :query :one
SELECT * FROM car_positions WHERE car_id=:car_id ORDER BY created DESC LIMIT 1;

--:name get-last-positions :query :many
SELECT *  FROM car_positions WHERE car_id=:car_id AND created > :created ORDER BY created ASC;
