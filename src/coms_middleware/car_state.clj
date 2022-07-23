(ns coms-middleware.car-state
  (:import (java.util UUID))
  (:gen-class))

(defprotocol CarState
  (setIgnition [this command])
  (setFuelLevel [this command])
  (setTemperature [this command])
  (setTyreCircumference [this value])
  (checkSpeedIncreaseDistance [this command])
  (resetTripDistance [this])
  (newTrip [this])
  (started? [this])
  (getTripDistance [this])
  (getTotalDistance [this])
  (getFuelLevel [this])
  (getRpm [this])
  (getSpeed [this])
  (getTemperature [this])
  (processCommand [this command conf]))

(defn avg [ar] (/ (reduce + ar) (count ar)))

(def car-pullup-resistor-value 975)
(def voltage-level 10.05)
(def pin-resolution 1023)
(def step (/ 15 pin-resolution))

(def car-thermistor-alpha-value -0.00001423854206)
(def car-thermistor-beta-value 0.0007620444171)
(def car-thermistor-c-value -0.000006511973919)

;(def temp-buffer (atom []))

;(def temperature-buffer-size 256) todo buffer logic will need to move to the plc;

(defn- get-temperature [analog-level]
  (let [v-lvl (* analog-level step)
        resistance (/ (* v-lvl car-pullup-resistor-value) (- voltage-level v-lvl))
        temp (- (/ 1 (+ car-thermistor-alpha-value
                        (* car-thermistor-beta-value (Math/log resistance))
                        (* car-thermistor-c-value (Math/pow (Math/log resistance) 3))))
                273.15)]
    temp))

;(def fuel-buffer-size 512) todo buffer logic will need to move to the plc;

(defn- get-fuel [analog-level]
  (let [v-lvl (* analog-level step)
        resistance (/ (* v-lvl car-pullup-resistor-value) (- voltage-level v-lvl))
        fuel-lvl (+ -50153.53 (/ (- 104.5813 -50153.53) (+ 1 (Math/pow (/ resistance 16570840000) 0.3447283))))]
    (if (< fuel-lvl 0)
      0
      fuel-lvl)))

(defn- get-rpm [v conf]
  (let [idle-rpm (-> conf deref :idle-rpm)
        idle-rpm-freq (-> conf deref :idle-rpm-freq)]
    (/ (* v idle-rpm) idle-rpm-freq)))

(defrecord ICEState [trip-id
                     trip-distance
                     total-distance
                     ignition
                     speed
                     rpm
                     temperature
                     fuel-level
                     tyre-circumference]
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
  (setTemperature [_ command]
    (swap! temperature (.getEngineTemperature command))
    command)
  (setTyreCircumference [_ value]
    (reset! tyre-circumference value))
  (checkSpeedIncreaseDistance [_ command]
    (let [rcv-speed (.getSpeed command)
          curr-speed @speed]
      (compare-and-set! speed curr-speed rcv-speed)
      (if (> @speed 0)
        (let [distance-per-rotation (/ @tyre-circumference 1000)
              distance-traveled (* (* 0.89288 (Math/pow 1.0073 @speed) distance-per-rotation))]
          (swap! trip-distance + distance-traveled)
          (swap! total-distance + distance-traveled)
          (doto command
            (.setTotalDistance @total-distance)))
        command)))
  (resetTripDistance [_] (reset! trip-distance 0))
  (newTrip [_] (compare-and-set! trip-id @trip-id (UUID/randomUUID)))
  (started? [_] (and @ignition (> @rpm 0)))
  (getTripDistance [_] @trip-distance)
  (getTotalDistance [_] @total-distance)
  (getFuelLevel [_] @fuel-level)
  (getTemperature [_] @temperature)
  (getRpm [_] @rpm)
  (getSpeed [_] @speed)
  (processCommand [this value conf]
    (let [fuel-analog-level (.getFuelAnalogLevel value)
          fuel (get-fuel fuel-analog-level)
          temp-analog-level (.getEngineTemperatureAnalogLevel value)
          temp (get-temperature temp-analog-level)
          rpm-analog-level (.getRpmAnalogLevel value)
          rpm-rcv (get-rpm rpm-analog-level conf)
          value (doto value
                  (.setFuelLevel fuel)
                  (.setEngineTemperature temp)
                  (.setRpm rpm-rcv))]
      (reset! rpm rpm-rcv)
      (reset! temperature temp)
      (reset! fuel-level fuel)
      (reset! fuel-level fuel)
      (if (.setIgnition this value)
        (.checkSpeedIncreaseDistance this value)
        value))))

(defmulti get-car-state (fn [type] type))

(defmethod get-car-state :ice [_]
  (->ICEState (atom (UUID/randomUUID))
              (atom 0)
              (atom 0)
              (atom false)
              (atom 0)
              (atom 0)
              (atom 0)
              (atom 0)
              (atom 0)))

(defmethod get-car-state :default [_] nil)
