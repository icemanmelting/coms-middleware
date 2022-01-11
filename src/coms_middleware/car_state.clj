(ns coms-middleware.car-state
  (:import (pt.iceman.middleware.cars.ice ICEBased)
           (java.util UUID))
  (:gen-class))

(defprotocol CarState
  (setIgnition [this command])
  (setFuelLevel [this command])
  (setTyreCircumference [this value])
  (checkSpeedIncreaseDistance [this command])
  (resetTripDistance [this])
  (newTrip [this])
  (started? [this])
  (getTripDistance [this])
  (getTotalDistance [this])
  (getFuelLevel [this]))

(defrecord ICEState [trip-id ignition speed trip-distance total-distance fuel-level tyre-circumference]
  CarState
  (setIgnition [_ command]
    (swap! ignition
           (fn [curr new]
             (if-not (and curr new)
               new
               curr))
           (.isIgnition command)))
  (setFuelLevel [_ command]
    (swap! fuel-level (.getFuelLevel command))
    command)
  (setTyreCircumference [_ value]
    (reset! tyre-circumference value))
  (checkSpeedIncreaseDistance [_ ^ICEBased command]
    (let [rcv-speed (.getSpeed command)
          curr-speed @speed]
      (compare-and-set! speed curr-speed rcv-speed)
      (if (> speed 0)
        (let [distance-per-rotation (/ tyre-circumference 1000)
              distance-traveled (* (* 0.89288 (Math/pow 1.0073 speed) distance-per-rotation))]
          (swap! trip-distance + distance-traveled)
          (swap! total-distance + distance-traveled)
          (doto command
            (.setTotalDistance @total-distance)))
        command)))
  (resetTripDistance [_]
    (reset! trip-distance 0))
  (newTrip [_] (compare-and-set! trip-id @trip-id (UUID/randomUUID)))
  (started? [_] @ignition)
  (getTripDistance [_] @trip-distance)
  (getTotalDistance [_] @total-distance)
  (getFuelLevel [_] @fuel-level))

(defmulti get-car-state (fn [type] type))

(defmethod get-car-state :ice []
  (->ICEState (atom (UUID/randomUUID)) (atom false) (atom 0) (atom 0) (atom 0) (atom 0) (atom 0)))

(defmethod get-car-state :default [] nil)
