(ns coms-middleware.readers.ignition-reader
  (:import (pt.iceman.middleware.cars.ice ICEBased)
           (pt.iceman.middleware.cars SimpleCommand Trip)
           (java.util UUID)
           (java.sql Timestamp)))

(defn ->ignition-on [basecommand trip _]
  (let [basecommand (if-not (.isIgnition basecommand)
                      (let [trip-id (UUID/randomUUID)]
                        (doto ^Trip trip
                          (.setId trip-id)
                          (.setInitialized true)
                          (.setSaved false)
                          (.setCarId (.getCarId basecommand))
                          (.setStartKm (.getTotalDistance basecommand))
                          (.setStartTemp (.getEngineTemperature basecommand))
                          (.setStartTime (Timestamp. (System/currentTimeMillis)))
                          (.setMaxTemp (.getEngineTemperature basecommand))
                          (.setMaxSpeed (.getSpeed basecommand)))
                        (doto basecommand
                          (.setIgnition true)
                          (.setTripSaved false)
                          (.setTripId trip-id)))
                      basecommand)]
    [basecommand
     (SimpleCommand. "ignition" true)
     (SimpleCommand. "total-distance" (.getTotalDistance ^ICEBased basecommand))]))

(defn- clear-state-values [^ICEBased basecommand]
  (doto basecommand
    (.setIgnition false)
    (.setAbsAnomaly false)
    (.setHighBeamOn false)
    (.setFuelLevel 0)
    (.setSpeed 0)
    (.setBattery12vNotCharging false)
    (.setBrakesHydraulicFluidLevelLow false)
    (.setEngineTemperature 0)
    (.setRpm 0)
    (.setSparkPlugOn false)
    (.setTurningSigns false)))

(defn ->ignition-off [base-command trip _]
  (let [base-command (if (.isIgnition base-command)
                       (do
                         (doto trip
                           (.setEndKm (.getTotalDistance base-command))
                           (.setEndTemp (.getEngineTemperature base-command))
                           (.setEndTime (Timestamp. (System/currentTimeMillis)))
                           (.setEndFuel (.getFuelLevel base-command))
                           (.setInitialized false))
                         (clear-state-values base-command))
                       base-command)]
    [base-command
     (SimpleCommand. "ignition" false)
     (SimpleCommand. "abs" false)
     (SimpleCommand. "high-beams" false)
     (SimpleCommand. "battery" false)
     (SimpleCommand. "brakes" false)
     (SimpleCommand. "spark-plug" false)
     (SimpleCommand. "turn-signs" false)
     (SimpleCommand. "fuel" (double 0))
     (SimpleCommand. "rpm" (int 0))
     (SimpleCommand. "speed" (long 0))
     (SimpleCommand. "temp" (double 0))]))
