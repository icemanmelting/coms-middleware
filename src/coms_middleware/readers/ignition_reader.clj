(ns coms-middleware.readers.ignition-reader
  (:import (pt.iceman.middleware.cars.ice ICEBased)
           (pt.iceman.middleware.cars SimpleCommand)))

(defn ->ignition-on [basecommand _]
  [ (doto basecommand (.setIgnition true)) (SimpleCommand. "ignition" true)])

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
  [(clear-state-values basecommand)
  (SimpleCommand. "ignition" false)])
