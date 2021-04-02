-- :name get-car :query :one
SELECT * FROM cars WHERE id=:id;

-- :name get-cars :query :many
SELECT id FROM cars;

-- :name create-car :execute :affected
INSERT INTO cars(id, constant_kilometers, trip_kilometers) VALUES (:id, :cnst_km, :trip_km);

-- :name update-car :execute :affected
UPDATE cars
SET constant_kilometers = :constant_km,
  trip_kilometers = :trip_km
  --~ (when (contains? params :trip_init_f) ",trip_initial_fuel_level=:trip_init_f")
  --~ (when (contains? params :avg_fuel_c) ",average_fuel_consumption=:avg_fuel_c")
  --~ (when (contains? params :dashboard_type) ",dashboard_type=:dashboard_type")
  --~ (when (contains? params :tyre_offset) ",tyre_offset=:tyre_offset")
  --~ (when (contains? params :next_oil_change) ",next_oil_change=:next_oil_change")
  WHERE id = :id;

--:name clear-cars :execute :affected
TRUNCATE TABLE cars CASCADE;
