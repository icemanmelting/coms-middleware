(ns coms-middleware.readers.temperature-reader
  (:require [coms-middleware.readers.common :refer [step car-pullup-resistor-value voltage-level avg]]))

(def car-thermistor-alpha-value -0.00001423854206)
(def car-thermistor-beta-value 0.0007620444171)
(def car-thermistor-c-value -0.000006511973919)

(def max-temp (atom 0))

(def temperature-buffer-size 256)
(def temperature-buffer (atom []))

(defn reset-temp-atom [val]
  (reset! max-temp val))

(defn- calculate-temperature [analog-level]
  (let [v-lvl (* analog-level step)
        resistance (/ (* v-lvl car-pullup-resistor-value) (- voltage-level v-lvl))
        temp (- (/ 1 (+ car-thermistor-alpha-value
                        (* car-thermistor-beta-value (Math/log resistance))
                        (* car-thermistor-c-value (Math/pow (Math/log resistance) 3))))
                273.15)]
    (when (> temp @max-temp)
      (reset-temp-atom temp))
    temp))

(defn temperature-interpreter [{val :value :as cmd-map}]
  (if (< (count @temperature-buffer) temperature-buffer-size)
    (swap! temperature-buffer conj val)
    (swap! temperature-buffer (fn [v] (conj (vec (rest v)) val))))
  (calculate-temperature (avg @temperature-buffer)))
