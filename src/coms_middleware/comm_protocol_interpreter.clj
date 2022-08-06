(ns coms-middleware.comm-protocol-interpreter
  (:require [coms-middleware.readers.ignition-reader :as ignition]
            [coms-middleware.readers.speed-rpm-reader :as speed]
            [coms-middleware.readers.fuel-reader :as fuel]
            [coms-middleware.readers.temperature-reader :as temp]
            [coms-middleware.readers.common :refer [car-running?]])
  (:import (com.ibm.icu.impl SimpleCache)
           (pt.iceman.middleware.cars SimpleCommand BaseCommand Trip)
           (pt.iceman.middleware.cars.ice ICEBased)))

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

(defmulti command->action-ignition-off (fn [_ _ cmd-map] (:command cmd-map)))

(defmethod command->action-ignition-off ignition-on [basecommand trip cmd-map]
  (ignition/->ignition-on basecommand trip cmd-map))

(defmethod command->action-ignition-off ignition-off [basecommand trip cmd-map]
  (ignition/->ignition-off basecommand trip cmd-map))

(defmethod command->action-ignition-off :default [basecommand _ _] [basecommand])

(defmulti command->action-ignition-on (fn [_ _ cmd-map] (:command cmd-map)))

(defmethod command->action-ignition-on abs-anomaly-off [basecommand _ _]
  [(doto basecommand
     (.setAbsAnomaly false))
   (SimpleCommand. "abs" false)])

(defmethod command->action-ignition-on abs-anomaly-on [basecommand _ _]
  (when (car-running? basecommand)
    #_(create-log "ERROR" "ABS sensor error. Maybe one of the speed sensors is broken?"))
  [(doto basecommand
     (.setAbsAnomaly true))
   (SimpleCommand. "abs" true)])

(defmethod command->action-ignition-on battery-off [basecommand _ _]
  [(doto basecommand
     (.setBattery12vNotCharging false))
   (SimpleCommand. "battery" false)])

(defmethod command->action-ignition-on battery-on [basecommand _ _]
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Battery not charging. Something wrong with the alternator."))
  [(doto basecommand
     (.setBattery12vNotCharging true))
   (SimpleCommand. "battery" true)])

(defmethod command->action-ignition-on brakes-oil-off [basecommand _ _]
  [(doto basecommand
     (.setBrakesHydraulicFluidLevelLow false))
   (SimpleCommand. "brakes" false)])

(defmethod command->action-ignition-on brakes-oil-on [basecommand _ _]
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Brakes Oil pressure is too low!"))
  [(doto basecommand
     (.setBrakesHydraulicFluidLevelLow true))
   (SimpleCommand. "brakes" true)])

(defmethod command->action-ignition-on high-beam-off [basecommand _ _]
  #_(create-log "INFO" "High beams off")
  [(doto basecommand
     (.setHighBeamOn false))
   (SimpleCommand. "high-beams" false)])

(defmethod command->action-ignition-on high-beam-on [basecommand _ _]
  #_(create-log "INFO" "High beams on")
  [(doto basecommand
     (.setHighBeamOn true))
   (SimpleCommand. "high-beams" true)])

(defmethod command->action-ignition-on oil-pressure-off [basecommand _ _]
  [(doto basecommand
     (.setOilPressureLow false))
   (SimpleCommand. "oil-pressure" false)])

(defmethod command->action-ignition-on oil-pressure-on [basecommand _ _]
  (when (car-running? basecommand)
    #_(create-log "ERROR" "Engine's oil pressure is low."))
  [(doto basecommand
     (.setOilPressureLow true))
   (SimpleCommand. "oil-pressure" true)])

(defmethod command->action-ignition-on parking-brake-off [basecommand _ _]
  #_(create-log "INFO" "Car park brake disengaged")
  [(doto basecommand
     (.setParkingBrakeOn false))
   (SimpleCommand. "parking" false)])

(defmethod command->action-ignition-on parking-brake-on [basecommand _ _]
  #_(create-log "INFO" "Car park brake engaged")
  [(doto basecommand
     (.setParkingBrakeOn true))
   (SimpleCommand. "parking" true)])

(defmethod command->action-ignition-on turning-signs-off [^BaseCommand basecommand _ _]
  [(doto basecommand
     (.setTurningSigns false))
   (SimpleCommand. "turn-signs" false)])

(defmethod command->action-ignition-on turning-signs-on [basecommand _ _]
  [(doto basecommand
     (.setTurningSigns true))
   (SimpleCommand. "turn-signs" true)])

(defmethod command->action-ignition-on spark-plugs-off [basecommand _ _]
  [(doto basecommand
     (.setSparkPlugOn false))
   (SimpleCommand. "spark-plug" false)])

(defmethod command->action-ignition-on spark-plugs-on [^ICEBased basecommand _ _]
  [(doto basecommand
     (.setSparkPlugOn true))
   (SimpleCommand. "spark-plug" true)])

(defmethod command->action-ignition-on reset-trip-km [basecommand _ _]
  #_(create-log "INFO" "Trip km reseted")
  [(doto basecommand
     (.setTripDistance basecommand 0))])

(defmethod command->action-ignition-on speed-pulse [basecommand trip {val :value}]
  (speed/speed-distance-interpreter basecommand trip val))

(defmethod command->action-ignition-on rpm-pulse [basecommand _ {val :value}]
  (speed/set-rpm basecommand val))

(defmethod command->action-ignition-on fuel-value [basecommand _ cmd-map]
  (let [fuel (fuel/fuel-interpreter cmd-map)]
    [(doto basecommand
       (.setFuelLevel fuel))
     (SimpleCommand. "fuel" fuel)]))

(defmethod command->action-ignition-on temperature-value [basecommand trip cmd-map]
  (let [temp (temp/temperature-interpreter cmd-map)]
    (when (> temp (.getMaxTemp ^Trip trip))
      (.setMaxTemp trip temp))
    (when (> temp 110)
      #_(create-log "INFO" "Engine temperature critical!"))
    [(doto basecommand
       (.setEngineTemperature temp))
     (SimpleCommand. "temp" temp)]))

(defmethod command->action-ignition-on ignition-off [basecommand trip cmd-map]
  (ignition/->ignition-off basecommand trip cmd-map))

(defmethod command->action-ignition-on :default [basecommand _ _] [basecommand])

(defmulti ignition-state (fn [ignition _ _ _] ignition))

(defmethod ignition-state false [_ basecommand trip cmd-map]
  (command->action-ignition-off basecommand trip cmd-map))

(defmethod ignition-state true [_ basecommand trip cmd-map]
  (command->action-ignition-on basecommand trip cmd-map))
