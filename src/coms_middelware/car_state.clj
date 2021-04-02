(ns coms-middelware.car-state
  (:require [coms-middelware.postgres :as pg])
  (:import (pt.iceman.middleware.cars BaseCommand)
           (pt.iceman.middleware.cars.ice ICEBased)))

(defn- alter-speed [speed newval]
  (compare-and-set! speed @speed newval));;alter speed only if different value

(defn- alter-temperature [temperature newval]
  (compare-and-set! temperature @temperature newval));;alter temperature only if different value

(defn- alter-rpm [rpm newval]
  (compare-and-set! rpm @rpm newval));;alter rpm only if different value

(defn- alter-ignition [ignition newval]
  (compare-and-set! ignition @ignition newval));;alter ignition only if different value

(defprotocol CarState
  (alterState [this command])
  (newTrip [this]))

(defrecord ICEState [trip-id speed engineTemperature rpm ignition]
  CarState
  (alterState [_ command]
    (compare-and-set! speed @speed (.getSpeed command))
    (compare-and-set! engineTemperature @engineTemperature (.getEngineTemperature command))
    (compare-and-set! rpm @rpm (.getRpm command))
    (compare-and-set! ignition @ignition (.isIgnition command)))
  (newTrip [_] (compare-and-set! trip-id @trip-id (pg/uuid))))

(defmulti get-car-state (fn [type] type))

(defmethod get-car-state :ice []
  (->ICEState (atom (pg/uuid)) (atom 0) (atom 0) (atom 0) (atom false)))

(defmethod get-car-state :default [] nil)
