-- DROP TABLE IF EXISTS users CASCADE;
-- CREATE TABLE users (
--                        login VARCHAR(128),
--                        password CHAR(64),
--                        salt VARCHAR(64),
--
--                        PRIMARY KEY (login)
-- );
--
-- DROP TABLE IF EXISTS sessions CASCADE;
-- CREATE TABLE sessions (
--                           id UUID,
--                           login VARCHAR(128) NOT NULL,
--                           seen TIMESTAMP NOT NULL,
--
--                           PRIMARY KEY (id),
--                           FOREIGN KEY (login) REFERENCES users (login)
-- );

DROP TABLE IF EXISTS cars CASCADE;
CREATE TABLE IF NOT EXISTS cars (
    id UUID,
    constant_kilometers DOUBLE PRECISION,
    trip_kilometers DOUBLE PRECISION,
    trip_initial_fuel_level DOUBLE PRECISION,
    tyre_offset DOUBLE PRECISION,

    PRIMARY KEY (id)
--     FOREIGN KEY (owner) REFERENCES users (login)
    );

DROP TABLE IF EXISTS car_trips CASCADE;
CREATE TABLE IF NOT EXISTS car_trips (
                                         id UUID,
                                         car_id UUID,
                                         starting_km DOUBLE PRECISION,
                                         ending_km DOUBLE PRECISION,
                                         trip_length_km DOUBLE PRECISION,
                                         max_temperature DOUBLE PRECISION,
                                         max_speed DOUBLE PRECISION,
                                         start_time TIMESTAMP,
                                         end_time TIMESTAMP,
                                         trip_duration DOUBLE PRECISION,
                                         average_speed DOUBLE PRECISION,

                                         PRIMARY KEY (id),
    FOREIGN KEY (car_id) REFERENCES cars(id)
    );

DROP TABLE IF EXISTS speed_data CASCADE;
CREATE TABLE IF NOT EXISTS speed_data (
                                          id UUID,
                                          trip_id UUID,
                                          speed DOUBLE PRECISION,
                                          rpm DOUBLE PRECISION,
                                          gear INT,
                                          ts TIMESTAMP,

                                          FOREIGN KEY (trip_id) REFERENCES car_trips (id),
    PRIMARY KEY (id, ts)
    );

DROP TABLE IF EXISTS temperature_data CASCADE;
CREATE TABLE IF NOT EXISTS temperature_data (
                                                id UUID,
                                                trip_id UUID,
                                                value DOUBLE PRECISION,
                                                ts TIMESTAMP,

                                                FOREIGN KEY (trip_id) REFERENCES car_trips (id),
    PRIMARY KEY (id)
    );

DROP TABLE IF EXISTS car_logs CASCADE;
CREATE TABLE IF NOT EXISTS car_logs (
                                        id UUID,
                                        trip_id UUID,
                                        message TEXT,
                                        ts TIMESTAMP,
                                        log_level TEXT,

                                        FOREIGN KEY (trip_id) REFERENCES car_trips (id),
    PRIMARY KEY (id)
    );

DROP TABLE IF EXISTS car_positions CASCADE;
CREATE TABLE IF NOT EXISTS car_positions (
                                             id UUID,
                                             car_id UUID,
                                             pos_lat DOUBLE PRECISION,
                                             pos_lon DOUBLE PRECISION,
                                             created TIMESTAMP,

                                             PRIMARY KEY (id),
    FOREIGN KEY (car_id) REFERENCES cars(id)
    );