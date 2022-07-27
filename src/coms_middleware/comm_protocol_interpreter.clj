(ns coms-middleware.comm-protocol-interpreter
  (:require [coms-middleware.readers.ignition-reader :as ignition]
            [coms-middleware.readers.speed-rpm-reader :as speed]
            [coms-middleware.readers.fuel-reader :as fuel]
            [coms-middleware.readers.temperature-reader :as temp]
            [coms-middleware.readers.common :refer [car-running?]]))

(def ^:const abs-anomaly-off 128)
(def ^:const abs-anomaly-on 143)
(def ^:const battery-off 32)
(def ^:const battery-on 47)
(def ^:const brakes-oil-off 64)
(def ^:const brakes-oil-on 79)
(def ^:const high-beam-off 144)
(def ^:const high-beam-on 159)
(def ^:const oil-pressure-off 16)
(def ^:const oil-pressure-on 31)
(def ^:const parking-brake-off 48)
(def ^:const parking-brake-on 63)
(def ^:const turning-signs-off 80)
(def ^:const turning-signs-on 95)
(def ^:const spark-plugs-off 112)
(def ^:const spark-plugs-on 127)
(def ^:const reset-trip-km 1)
(def ^:const rpm-pulse 180)
(def ^:const speed-pulse 176)
(def ^:const temperature-value 192)
(def ^:const fuel-value 224)
(def ^:const ignition-on 171)
(def ^:const ignition-off 170)
(def ^:const turn-off 168)

(defmulti command->action-ignition-off (fn [_ cmd-map] (:command cmd-map)))

(defmethod command->action-ignition-off ignition-on [basecommand cmd-map]
  (ignition/->ignition-on basecommand cmd-map))

(defmethod command->action-ignition-off turn-off [basecommand cmd-map]
  (ignition/->ignition-off basecommand cmd-map))

(defmethod command->action-ignition-off :default [_ _])

(defmulti command->action-ignition-on (fn [_ cmd-map] (:command cmd-map)))

(defmethod command->action-ignition-on abs-anomaly-off [basecommand cmd-map]
  (.setAbsAnomaly basecommand false)
  basecommand)

(defmethod command->action-ignition-on abs-anomaly-on [basecommand cmd-map]
  (.setAbsAnomaly basecommand true)
  (when (car-running? basecommand)
    #_(create-log "ERROR" "ABS sensor error. Maybe one of the speed sensors is broken?"))
  basecommand)

(defmethod command->action-ignition-on battery-off [basecommand cmd-map]
  (.setBattery12vNotCharging basecommand false)
  basecommand)

(defmethod command->action-ignition-on battery-on [basecommand cmd-map]
  (.setBattery12vNotCharging basecommand true)
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Battery not charging. Something wrong with the alternator."))
  basecommand)

(defmethod command->action-ignition-on brakes-oil-off [basecommand cmd-map]
  (.setBrakesHydraulicFluidLevelLow basecommand false)
  basecommand)

(defmethod command->action-ignition-on brakes-oil-on [basecommand cmd-map]
  (.setBrakesHydraulicFluidLevelLow basecommand true)
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Brakes Oil pressure is too low!"))
  basecommand)

(defmethod command->action-ignition-on high-beam-off [basecommand cmd-map]
  #_(create-log "INFO" "High beams off")
  (.setHighBeamOn basecommand false)
  basecommand)

(defmethod command->action-ignition-on high-beam-on [basecommand cmd-map]
  #_(create-log "INFO" "High beams on")
  (.setHighBeamOn basecommand true)
  basecommand)

(defmethod command->action-ignition-on oil-pressure-off [basecommand cmd-map]
  (.setOilPressureLow basecommand false)
  basecommand)

(defmethod command->action-ignition-on oil-pressure-on [basecommand cmd-map]
  (.setOilPressureLow basecommand true)
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Engine's oil pressure is low."))
  basecommand)

(defmethod command->action-ignition-on parking-brake-off [basecommand cmd-map]
  #_(create-log "INFO" "Car park brake disengaged")
  (.setParkingBrakeOn basecommand false)
  basecommand)

(defmethod command->action-ignition-on parking-brake-on [basecommand cmd-map]
  #_(create-log "INFO" "Car park brake engaged")
  (.setParkingBrakeOn basecommand true)
  basecommand)

(defmethod command->action-ignition-on turning-signs-off [basecommand cmd-map]
  (.setTurningSigns basecommand false)
  basecommand)

(defmethod command->action-ignition-on turning-signs-on [basecommand cmd-map]
  (.setTurningSigns basecommand true)
  basecommand)

(defmethod command->action-ignition-on spark-plugs-off [basecommand cmd-map]
  (.setSparkPlugOn basecommand false)
  basecommand)

(defmethod command->action-ignition-on spark-plugs-on [basecommand cmd-map]
  (.setSparkPlugOn basecommand true)
  basecommand)

(defmethod command->action-ignition-on reset-trip-km [basecommand cmd-map]
  #_(create-log "INFO" "Trip km reseted")
  (.setTripDistance basecommand 0)
  basecommand)

(defmethod command->action-ignition-on speed-pulse [basecommand {val :value}]
  (speed/speed-distance-interpreter basecommand val))

(defmethod command->action-ignition-on rpm-pulse [basecommand {val :value}]
  (speed/set-rpm basecommand val))

(defmethod command->action-ignition-on fuel-value [basecommand cmd-map]
  (let [fuel (fuel/fuel-interpreter cmd-map)]
    (doto basecommand
      (.setFuelLevel fuel))))

(defmethod command->action-ignition-on temperature-value [basecommand cmd-map]
  (let [temp (temp/temperature-interpreter cmd-map)]
  (when (> temp 110)
    #_(create-log "INFO" "Engine temperature critical!"))
  (doto basecommand
    (.setEngineTemperature temp))))

(defmethod command->action-ignition-on ignition-off [basecommand cmd-map]
  (ignition/->ignition-off basecommand cmd-map))

(defmethod command->action-ignition-on :default [basecommand _] basecommand)

(defmulti ignition-state (fn [ignition _ _] ignition))

(defmethod ignition-state false [_ basecommand cmd-map]
  (command->action-ignition-off basecommand cmd-map))

(defmethod ignition-state true [_ basecommand cmd-map]
  (command->action-ignition-on basecommand cmd-map))
