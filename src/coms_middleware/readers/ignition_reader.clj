(ns coms-middleware.readers.ignition-reader
  (:import (pt.iceman.middleware.cars.ice ICEBased)))

(defn ->ignition-on [basecommand _]
  (.setIgnition basecommand true)
  basecommand)

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

(defn ->ignition-off [basecommand _]
  (clear-state-values basecommand))
