(ns coms-middleware.readers.fuel-reader
  (:require [coms-middleware.readers.common :refer [avg step car-pullup-resistor-value voltage-level create-log]]))

(def fuel-buffer-size 512)
(def fuel-buffer (atom []))

(defn- calculate-fuel-level [analog-level]
  (let [v-lvl (* analog-level step)
        resistance (/ (* v-lvl car-pullup-resistor-value) (- voltage-level v-lvl))
        fuel-lvl (+ -50153.53 (/ (- 104.5813 -50153.53) (+ 1 (Math/pow (/ resistance 16570840000) 0.3447283))))
        fuel-lvl (if (< fuel-lvl 0) 0 fuel-lvl)]
    (when (< fuel-lvl 6)
      (create-log "INFO" "Reserve fuel reached. Please refill tank!"))
    fuel-lvl))

(defn fuel-interpreter [{val :value}]
  (if (< (count @fuel-buffer) fuel-buffer-size)
    (swap! fuel-buffer conj val)
    (swap! fuel-buffer (fn [v] (conj (vec (rest v)) val))))
  (calculate-fuel-level (avg @fuel-buffer)))
