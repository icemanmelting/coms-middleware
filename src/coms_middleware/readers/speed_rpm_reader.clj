(ns coms-middleware.readers.speed-rpm-reader)

(defn calculate-distance [speed]
  (* (* 0.89288 (Math/pow 1.0073 speed) 0.00181)))

(defn speed-distance-interpreter [basecommand speed]
  (if (and (> speed 0) (<= speed 220) (> (.getRpm basecommand) 0))
    (let [abs-km (.getTotalDistance basecommand)
          distance (calculate-distance speed)
          trip (+ (.getTripDistance basecommand) distance)
          abs (+ abs-km distance)
          #_gear #_(ai/get-gear speed @rpm-atom)]
      (doto basecommand
        (.setTripDistance trip)
        (.setTotalDistance abs)
        (.setSpeed speed)
        #_(.setGear gear)))
    basecommand))

(defn set-rpm [basecommand rpm-analog]
  (let [rpm (int (/ (* rpm-analog 900) 155))]
    (doto basecommand
      (.setRpm rpm))))
