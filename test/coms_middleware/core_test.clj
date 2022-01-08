(ns coms-middleware.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [timeout alts!! chan <!! >! go]]
            [clojure-data-grinder-core.core :as c]
            [coms-middleware.core :refer :all]
            [clojure.core.async :as async])
  (:import
    (java.nio ByteBuffer)
    (pt.iceman.middleware.cars.ice ICEBased)
    (java.io ByteArrayOutputStream ObjectOutputStream)
    (coms_middleware.core MCUSource MCUOutGrinder)))

(defn <!!?
  "Reads from chan synchronously, waiting for a given maximum of milliseconds.
  If the value does not come in during that period, returns :timed-out. If
  milliseconds is not given, a default of 1000 is used."
  ([chan]
   (<!!? chan 1000))
  ([chan milliseconds]
   (let [timeout (timeout milliseconds)
         [value port] (alts!! [chan timeout])]
     (if (= chan port)
       value
       :timed-out))))

(def socket (atom nil))

(defn cleaning-fixture [f]
  (reset! socket (make-socket 9998))
  (f)
  (.close @socket))

(use-fixtures :each cleaning-fixture)

(deftest mcu-source-test
  (let [tm (c/set-transaction-manager :mem {})
        state (atom {:successful-source-calls 0
                     :unsuccessful-source-calls 0
                     :source-calls 0
                     :stopped false})
        name "test-source"
        out (chan)
        conf (atom {:port 9999
                    :buffer-size 14
                    :channels {:out {:output-channel out}}})
        ^MCUSource mcu-source (->MCUSource state name conf 1000)]

    (.init mcu-source)

    (Thread/sleep 2000)

    (send-packet @socket (.getBytes "this is a test") "localhost" 9999)

    (let [{v :value tx-id :tx-id} (<!!? out 2000)
          tx (.getTransaction tm tx-id)
          {pb :source-calls sb :successful-source-calls :as state} (.getState mcu-source)]
      (are [x y] (= x y)
                 (ByteBuffer/wrap (.getBytes "this is a test")) v
                 :initialized (.getStatus tx)
                 pb 1
                 sb 1))

    (.stop mcu-source)))

(defn- short-to-2-bytes [^Short s]
  (let [b1 (bit-and s 0xFF)
        b2 (bit-and (bit-shift-right s 8) 0xFF)]
    [b1 b2]))

(deftest mcu-grinder-test
  (let [speed (short-to-2-bytes 50)
        temperature (short-to-2-bytes 80)
        rpm (short-to-2-bytes 3000)
        fuel (short-to-2-bytes 32)
        arr (byte-array [0x00
                         0x00
                         0x00
                         0xFF
                         0xFF
                         0x00
                         0xFF
                         (first speed)
                         (second speed)
                         0x00
                         0xFF
                         (first rpm)
                         (second rpm)
                         (first fuel)
                         (second fuel)
                         (first temperature)
                         (second temperature)])
        buff (ByteBuffer/wrap arr)
        state (atom {:successful-grinding-operations 0
                     :unsuccessful-grinding-operations 0
                     :grinding-operations 0
                     :stopped false})
        in (chan 1)
        out (chan 1)
        conf (atom {:type :ice
                    :channels {:in in
                               :out {:output-channel out}}})
        ^MCUOutGrinder mcu-grinder (->MCUOutGrinder state "test" 1 conf 1000)
        tm (c/set-transaction-manager :mem {})
        tx (c/initiate-tx mcu-grinder (atom {}))]


    (async/>!! in {:value buff
                   :tx-id (.getId tx)})

    (.init mcu-grinder)

    (Thread/sleep 3000)

    (let [{^ICEBased ice :value tx-id :tx-id} (<!!? out 2000)]
      (are [x y] (= x y)
                 false (.isOilPressureLow ice)
                 true (.isSparkPlugOn ice)
                 false (.isBattery12vNotCharging ice)
                 true (.isTurningSigns ice)
                 true (.isAbsAnomaly ice)
                 false (.isParkingBrakeOn ice)
                 false (.isBrakesHydraulicFluidLevelLow ice)
                 false (.isHighBeamOn ice)
                 true (.isIgnition ice)
                 50 (.getSpeed ice)
                 3000 (.getRpm ice)
                 80 (.getEngineTemperature ice)))

    (.stop mcu-grinder)))

#_(deftest test-dashboard-sink
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
