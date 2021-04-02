-- :name select-car-trip :query :one
SELECT * FROM car_trips WHERE id=:id AND car_id=:car_id;

-- :name insert-car-trip :execute :affected
INSERT INTO car_trips (id, car_id, starting_km, start_time) VALUES (:id, :car_id, :starting_km, NOW());

-- :name update-car-trip :execute :affected
UPDATE car_trips
SET ending_km = :ending_km,
  end_time=NOW()
--~ (when (contains? params :trip_l) ",trip_length_km=:trip_l")
--~ (when (contains? params :max_temp) ",max_temperature=:max_temp")
--~ (when (contains? params :max_speed) ",max_speed=:max_speed")
--~ (when (contains? params :trip_duration) ",trip_duration=:trip_duration")
--~ (when (contains? params :average_speed) ",average_speed=:average_speed")
WHERE id = :id;
