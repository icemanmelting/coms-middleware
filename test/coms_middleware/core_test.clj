(ns coms-middleware.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [timeout alts!! thread <!!]]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager-impl :refer [set-transaction-manager]]
            [coms-middleware.core :refer :all]
            [clojure.core.async :as async]
            [clojure-data-grinder-core.protocols.protocols :as c]
            [clojure-data-grinder-core.protocols.impl :refer [->LocalQueue
                                                              ->GrinderImpl
                                                              main-channel]]
            [clojure-data-grinder-core.common :refer [queues]]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager :as tx-mng]
            [clojure-data-grinder-tx-manager.protocols.transaction :as tx]
            [clojure-data-grinder-core.protocols.impl :as impl])
  (:import
    (java.nio ByteBuffer)
    (pt.iceman.middleware.cars.ice ICEBased)
    (coms_middleware.core MCUSource)))

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

(defn- new-queue [name buffer-size]
  (let [queue (->LocalQueue (atom {}) {:name name :buffer-size buffer-size})]
    (c/init! queue)
    queue))

(def socket (atom nil))

(defn cleaning-fixture [f]
  (reset! socket (make-socket 9998))
  (f)
  (.close @socket))

(use-fixtures :each cleaning-fixture)

(deftest mcu-source-test
  (let [_ (set-transaction-manager :mem {} :local main-channel queues)
        state (atom {:successful-step-calls 0
                     :unsuccessful-step-calls 0
                     :total-step-calls 0
                     :stopped false})
        name "test-source"
        queue (new-queue "test" 1)
        conf (atom {:port 9999
                    :buffer-size 14
                    :channels {:out {:output-channel "test"}}})]
    (with-redefs [queues (atom {"test" queue})]
      (let [^MCUSource mcu-source (->MCUSource state name conf 1000)]
        (.init mcu-source)

        (Thread/sleep 2000)

        (send-packet @socket (.getBytes "this is a test") "localhost" 9999)

        (let [{shard :value} (c/take! queue)
              value (.getValue shard)
              status (.getStatus shard)
              {pb :total-step-calls sb :successful-step-calls} (.getState mcu-source)]
          (are [x y] (= x y)
                     (ByteBuffer/wrap (.getBytes "this is a test")) value
                     :ok status
                     pb 1
                     sb 1))

        (.stop mcu-source)))))

(defn- short-to-2-bytes [^Short s]
  (let [b1 (bit-and s 0xFF)
        b2 (bit-and (bit-shift-right s 8) 0xFF)]
    [b1 b2]))

(deftest mcu-grinder-test
  (let [tm (set-transaction-manager :mem {} :local main-channel queues)
        state (atom {:successful-step-calls 0
                     :unsuccessful-step-calls 0
                     :total-step-calls 0
                     :stopped false})
        name "test-source"
        queue (new-queue "test" 1)
        queue2 (new-queue "test2" 1)
        conf (atom {:type :ice
                    :channels {:in "test"
                               :out {:output-channel "test2"}}})
        speed (short-to-2-bytes 50)
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
        buff (ByteBuffer/wrap arr)]
    (with-redefs [queues (atom {"test" queue
                                "test2" queue2})]
      (let [mcu-grinder (->MCUOutGrinder state
                                         name
                                         1
                                         conf
                                         500)
            tx (tx-mng/startTx tm conf)
            shard (tx/addShard tx buff "test" :ok)]
        (c/put! queue shard)

        (.init mcu-grinder)

        (Thread/sleep 1000)

        (let [{shard :value} (c/take! queue2)
              ice (.getValue shard)]
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
                     3000 (.getRpmAnalogLevel ice)
                     32 (.getFuelAnalogLevel ice)
                     80 (.getEngineTemperatureAnalogLevel ice)))

        (.stop mcu-grinder)))))

(deftest test-basecommand-grinder
  (let [tm (set-transaction-manager :mem {} :local main-channel queues)
        gsm (impl/set-global-state-manager :mem nil)
        state (atom {:successful-step-calls 0
                     :unsuccessful-step-calls 0
                     :total-step-calls 0
                     :stopped false})
        name "test-source"
        queue (new-queue "test" 1)
        queue2 (new-queue "test2" 1)
        speed (short-to-2-bytes 50)
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
        conf (atom {:type :ice
                    :tyre-circumference 3.14
                    :idle-rpm 738
                    :idle-rpm-freq 40
                    :channels {:in "test"
                               :out {:output-channel "test2"}}
                    :destination-port 9998
                    :destination-host "localhost"
                    :source-port 9999})]
    (with-redefs [queues (atom {"test" queue
                                "test2" queue2})]
      (let [base-command-grinder (->BaseCommandGrinder state name 1 conf 1000)
            tx (tx-mng/startTx tm conf)
            shard (tx/addShard tx value "test" :ok)]
        (c/put! queue shard)

        (.init base-command-grinder)

        (Thread/sleep 3000)

        (let [_ (c/take! queue2)
              st (c/getStateValue gsm :car-state)]
          (are [x y] (= x y)
                     0.004033336352993614 (.getTripDistance st)
                     0.004033336352993614 (.getTotalDistance st)
                     50 (.getSpeed st)
                     55350 (.getRpm st)
                     66.83039949173809 (.getTemperature st)
                     47.48561277403496 (.getFuelLevel st))

          (.stop base-command-grinder))))))

(deftest test-dashboard-sink
  (let [tm (set-transaction-manager :mem {} :local main-channel queues)
        state (atom {:successful-step-calls 0
                     :unsuccessful-step-calls 0
                     :total-step-calls 0
                     :stopped false})
        name "test-source"
        queue (new-queue "test" 1)
        speed (short-to-2-bytes 50)
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
        conf (atom {:type :ice
                    :channels {:in "test"}
                    :destination-port 9998
                    :destination-host "localhost"
                    :source-port 9999})]
    (with-redefs [queues (atom {"test" queue})]
      (let [dashboard-sink (->DashboardSink state name 1 conf 1000)
            tx (tx-mng/startTx tm conf)
            shard (tx/addShard tx value "test" :ok)
            res (thread (-> socket
                            deref
                            (receive-packet (alength expected))
                            (byte-array)))]
        (c/put! queue shard)

        (.init dashboard-sink)

        (Thread/sleep 3000)

        (let [result (<!! res)]
          (is (= (seq expected) (seq result)))

          (.stop dashboard-sink))))))
