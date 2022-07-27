(ns coms-middleware.readers.common)

(def car-pullup-resistor-value 975)

(def voltage-level 10.05)

(def pin-resolution 1023)

(def step (/ 15 pin-resolution))

(defn avg [ar] (/ (reduce + ar) (count ar)))

(defn create-log [type msg]
  #_(make-request {:op_type "car_log_new"
                 :id (uuid)
                 :trip_id @trip-id
                 :msg msg
                 :log_level type}))

(defn car-running? [basecommand]
  (> (.getRpm basecommand) 0))

(defn reset-dashboard [dashboard]
  (doto dashboard
    (.setDiesel 0)
    (.setTemp 0)
    (.setRpm 0)
    (.setSpeed 0)
    (.setGear 0)
    (.setAbs false)
    (.setSparkPlug false)
    (.setParking false)
    (.setTurnSigns false)
    (.setOilPressure false)
    (.setBrakesOil false)
    (.setBattery false)))