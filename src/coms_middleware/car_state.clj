(ns coms-middleware.car-state
  (:require [coms-middleware.postgres :as pg])
  (:import (pt.iceman.middleware.cars.ice ICEBased)))

(defprotocol CarState
  (setIgnition [this command])
  (setFuelLevel [this command])
  (checkSpeedIncreaseDistance [this command])
  (resetTripDistance [this])
  (newTrip [this])
  (started? [this])
  (getTripDistance [this])
  (getTotalDistance [this])
  (getFuelLevel [this]))

(defrecord ICEState [trip-id ignition speed trip-distance total-distance fuel-level tyre-circumference]
  CarState
  (setIgnition [this command]
    (swap! ignition not))
  (setFuelLevel [this command]
    (swap! fuel-level (.getFuelLevel command))
    command)
  (checkSpeedIncreaseDistance [_ command]
    (let [rcv-speed (.getSpeed command)
          curr-speed @speed
          alter-speed? (compare-and-set! speed curr-speed rcv-speed)]
      (if alter-speed?
        (if (> 0 speed)
          (let [distance-per-rotation (/ tyre-circumference 1000)
                distance-traveled (* (* 0.89288 (Math/pow 1.0073 speed) distance-per-rotation))]
            (swap! trip-distance + distance-traveled)
            (swap! total-distance + distance-traveled)
            command)
          command)
        (ICEBased. command curr-speed))))
  (resetTripDistance [_]
    (reset! trip-distance 0))
  (newTrip [_] (compare-and-set! trip-id @trip-id (pg/uuid)))
  (started? [_] @ignition)
  (getTripDistance [_] @trip-distance)
  (getTotalDistance [_] @total-distance)
  (getFuelLevel [_] @fuel-level))

(defmulti get-car-state (fn [type] type))

(defmethod get-car-state :ice []
  (->ICEState (atom (pg/uuid)) (atom false) (atom 0) (atom 0) (atom 0) (atom 0) (atom 0)))

(defmethod get-car-state :default [] nil)

;private static final float CAR_TYRE_CIRCUNFERENCE_M = 1.81f;
;private static final double CAR_DISTANCE_PER_ROTATION = ((CAR_TYRE_CIRCUNFERENCE_M) / (double) 1000);