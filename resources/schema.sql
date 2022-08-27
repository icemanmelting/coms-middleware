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
    tyre_offset DOUBLE PRECISION,

    PRIMARY KEY (id)
--     FOREIGN KEY (owner) REFERENCES users (login)
    );

DROP TABLE IF EXISTS trips CASCADE;
CREATE TABLE IF NOT EXISTS trips (
                                         id UUID,
                                         car_id UUID,
                                         start_km DOUBLE PRECISION,
                                         start_temp DOUBLE PRECISION,
                                         start_fuel DOUBLE PRECISION,
                                         start_time TIMESTAMP,
                                         trip_length_km DOUBLE PRECISION,
                                         max_temp DOUBLE PRECISION,
                                         max_speed DOUBLE PRECISION,
                                         end_km DOUBLE PRECISION,
                                         end_temp DOUBLE PRECISION,
                                         end_fuel DOUBLE PRECISION,
                                         end_time TIMESTAMP,
                                         trip_duration_ms DOUBLE PRECISION,
                                         avg_speed DOUBLE PRECISION,

                                         PRIMARY KEY (id),
    FOREIGN KEY (car_id) REFERENCES cars(id)
    );

DROP TABLE IF EXISTS speed_data CASCADE;
CREATE TABLE IF NOT EXISTS speed_data (
                                          id UUID,
                                          car_id UUID,
                                          trip_id UUID,
                                          speed DOUBLE PRECISION,
                                          rpm DOUBLE PRECISION,
                                          gear INT,
                                          timestamp TIMESTAMP,

                                          FOREIGN KEY (car_id) REFERENCES cars (id),
                                          FOREIGN KEY (trip_id) REFERENCES trips (id),
    PRIMARY KEY (id)
    );

DROP TABLE IF EXISTS temperature_data CASCADE;
CREATE TABLE IF NOT EXISTS temperature_data (
                                                id UUID,
                                                car_id UUID,
                                                trip_id UUID,
                                                value DOUBLE PRECISION,
                                                timestamp TIMESTAMP,

                                                FOREIGN KEY (car_id) REFERENCES cars (id),
                                                FOREIGN KEY (trip_id) REFERENCES trips (id),
    PRIMARY KEY (id)
    );

DROP TABLE IF EXISTS car_logs CASCADE;
CREATE TABLE IF NOT EXISTS car_logs (
                                        id UUID,
                                        car_id UUID,
                                        trip_id UUID,
                                        message TEXT,
                                        timestamp TIMESTAMP,
                                        log_level TEXT,

                                        FOREIGN KEY (car_id) REFERENCES cars (id),
                                        FOREIGN KEY (trip_id) REFERENCES trips (id),
    PRIMARY KEY (id)
    );

DROP TABLE IF EXISTS car_positions CASCADE;
CREATE TABLE IF NOT EXISTS car_positions (
                                             id UUID,
                                             car_id UUID,
                                             trip_id UUID,
                                             pos_lat DOUBLE PRECISION,
                                             pos_lon DOUBLE PRECISION,
                                             timestamp TIMESTAMP,

                                             FOREIGN KEY (car_id) REFERENCES cars(id),
                                             FOREIGN KEY (trip_id) REFERENCES trips(id),

                                             PRIMARY KEY (id)
    );
