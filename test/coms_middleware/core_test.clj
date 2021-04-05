(ns coms-middleware.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan <!! >! go]]
            [coms-middelware.core :refer :all])
  (:import
    (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ice ICEBased)
           (coms_middelware.core MCUSource)
           (java.io ByteArrayOutputStream ObjectOutputStream)))

(defonce socket (make-socket 9998))

(deftest mcu-source-test
  (let [state (atom {})
        name "test-source"
        conf (atom {:port 9999
                    :buffer-size 14})
        out (chan)
        ^MCUSource mcu-source (->MCUSource state name conf nil out)]
    (.init mcu-source)
    (send-packet socket (.getBytes "this is a test") "localhost" 9999)
    (let [value (<!! out)]
      (is (= "this is a test" (String. (.array value)))))
    (.stop mcu-source)))

(defn- short-to-2-bytes[^Short s]
  (let [b1 (bit-and s 0xFF)
        b2 (bit-and (bit-shift-right s 8) 0xFF)]
    [b1 b2]))

(deftest mcu-grinder-test
  (let [speed (short-to-2-bytes 50)
        temperature (short-to-2-bytes 80)
        rpm (short-to-2-bytes 3000)
        fuel (short-to-2-bytes 32)
        arr (byte-array [0x00
                         0xFF
                         0xFF
                         0x00
                         0x00
                         0xFF
                         0xFF
                         (first speed)
                         (second speed)
                         0x00
                         0x00
                         (first rpm)
                         (second rpm)
                         (first fuel)
                         (second fuel)
                         (first temperature)
                         (second temperature)])
        buff (ByteBuffer/wrap arr)
        state (atom {:stopped false})
        conf (atom {:type :ice})
        in (chan)
        out (chan)
        mcu-grinder (->MCUOutGrinder state name conf nil in out)]
    (go (>! in buff))
    (.init mcu-grinder)
    (let [^ICEBased ice (<!! out)]
      (are [x y] (= x y)
        false (.isOilPressureLow ice)
        false (.isSparkPlugOn ice)
        false (.isBattery12vNotCharging ice)
        false (.isTurningSigns ice)
        false (.isAbsAnomaly ice)
        true (.isParkingBrakeOn ice)
        true (.isBrakesHydraulicFluidLevelLow ice)
        true (.isHighBeamOn ice)
        true (.isIgnition ice)
        50 (.getSpeed ice)
        3000 (.getRpm ice)
        80 (.getEngineTemperature ice)))))

(deftest test-dashboard-sink
  (let [speed (short-to-2-bytes 50)
        temperature (short-to-2-bytes 80)
        rpm (short-to-2-bytes 3000)
        fuel (short-to-2-bytes 32)
        arr (byte-array [0x00
                         0xFF
                         0xFF
                         0x00
                         0x00
                         0xFF
                         0xFF
                         (first speed)
                         (second speed)
                         0x00
                         0x00
                         (first rpm)
                         (second rpm)
                         (first fuel)
                         (second fuel)
                         (first temperature)
                         (second temperature)])
        buff (ByteBuffer/wrap arr)
        value (ICEBased. buff)
        expected (serialize value)
        state (atom {:stopped false})
        conf (atom {:destination-port 9998
                    :destination-host "localhost"
                    :source-port 9999})
        in (chan)
        dashboard-sink (->DashboardSink state name conf nil in)]
    (go (>! in value))
    (.init dashboard-sink)
    (let [result (-> socket
                     (receive-packet (alength expected))
                     (byte-array))]
      (is (= (seq expected) (seq result))))))
