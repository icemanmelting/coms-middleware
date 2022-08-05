(ns coms-middleware.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [timeout alts!! thread <!!]]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager-impl :refer [set-transaction-manager]]
            [coms-middleware.core :refer [make-socket
                                          serialize
                                          send-packet
                                          receive-packet
                                          ->MCUSource
                                          ->BaseCommandGrinder
                                          ->DashboardSink]]
            [coms-middleware.comm-protocol-interpreter :as interpreter]
            [clojure-data-grinder-core.protocols.protocols :as c]
            [clojure-data-grinder-core.protocols.impl :refer [->LocalQueue
                                                              main-channel]]
            [clojure-data-grinder-core.common :refer [queues]]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager :as tx-mng]
            [clojure-data-grinder-tx-manager.protocols.transaction :as tx]
            [clojure-data-grinder-core.protocols.impl :as impl]
            [next.jdbc :as jdbc]
            [clojure.java.io :as io]
            [clojure-data-grinder-tx-manager.protocols.transaction-shard :as tx-shard])
  (:import
    (java.nio ByteBuffer)
    (pt.iceman.middleware.cars.ice ICEBased)
    (coms_middleware.core MCUSource)
    (java.util UUID)))

(set! *unchecked-math* true)

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

(defn- short-to-2-bytes [^Short s]
  (let [b1 (bit-and s 0xFF)
        b2 (bit-and (bit-shift-right s 8) 0xFF)]
    [b1 b2]))

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
      (let [^MCUSource mcu-source (->MCUSource state name conf 1000 "ms")
            [b1 b2] (short-to-2-bytes (short 69))]
        (.init mcu-source)

        (Thread/sleep 2000)

        (send-packet @socket (byte-array 3 [interpreter/temperature-value b1 b2]) "localhost" 9999)

        (let [{shard :value} (c/take! queue)
              value (.getValue shard)
              status (.getStatus shard)
              {pb :total-step-calls sb :successful-step-calls} (.getState mcu-source)]
          (are [x y] (= x y)
                     {:command 192
                      :value 69} value
                     :ok status
                     pb 1
                     sb 1))

        (.stop mcu-source)))))

(deftest test-basecommand-grinder
  (let [db-cfg {:dbtype "h2" :dbname "test"}
        ds (jdbc/get-datasource db-cfg)
        car-id (UUID/randomUUID)
        _ (try (jdbc/execute! ds ["create table cars (id uuid primary key,
                                  constant_kilometers double,
                                  trip_kilometers double,
                                  tyre_offset double)"])
               (catch Exception _))
        _ (jdbc/execute! ds ["INSERT INTO cars (id, constant_kilometers, trip_kilometers, tyre_offset) VALUES (?, ?, ?, ?);"
                             car-id
                             20000
                             500.2
                             1.4])
        tm (set-transaction-manager :mem {} :local main-channel queues)
        gsm (impl/set-global-state-manager :mem nil)
        state (atom {:successful-step-calls 0
                     :unsuccessful-step-calls 0
                     :total-step-calls 0
                     :stopped false})
        name "test-source"
        queue (new-queue "test" 4)
        queue2 (new-queue "test2" 4)
        value {:command 171}
        value2 {:command 180
                :value 3500}
        value3 {:command 176
               :value 123}
        value4 {:command 224
                :value 73}
        conf (atom {:db-cfg db-cfg
                    :car-id (str car-id)
                    :channels {:in "test"
                               :out {:output-channel "test2"}}
                    :destination-port 9998
                    :destination-host "localhost"
                    :source-port 9999})]
    (with-redefs [queues (atom {"test" queue
                                "test2" queue2})
                  coms-middleware.core/db-settings->basecommand (fn [res]
                                                                  (doto coms-middleware.core/base-command
                                                                    (.setIgnition false)
                                                                    (.setSpeed 0)
                                                                    (.setRpm 0)
                                                                    (.setFuelLevel 0)
                                                                    (.setEngineTemperature 0)
                                                                    (.setCarId (:CARS/ID res))
                                                                    (.setTripDistance (:CARS/TRIP_KILOMETERS res))
                                                                    (.setTotalDistance (:CARS/CONSTANT_KILOMETERS res))))]
      (let [base-command-grinder (->BaseCommandGrinder state name 1 conf 450 "ms")
            tx (tx-mng/startTx tm conf)
            shard (tx/addShard tx value "test" :ok)
            tx2 (tx-mng/startTx tm conf)
            shard2 (tx/addShard tx2 value2 "test" :ok)
            tx3 (tx-mng/startTx tm conf)
            shard3 (tx/addShard tx3 value3 "test" :ok)
            tx4 (tx-mng/startTx tm conf)
            shard4 (tx/addShard tx4 value4 "test" :ok)]
        (c/put! queue shard)
        (c/put! queue shard2)
        (c/put! queue shard3)
        (c/put! queue shard4)

        (.init base-command-grinder)

        (Thread/sleep 3000)

        (let [s1 (c/take! queue2)
              s2 (c/take! queue2)
              s3 (c/take! queue2)
              {shard :value} (c/take! queue2)
              st coms-middleware.core/base-command]
          (are [x y] (= x y)
                     500.2039537412697 (.getTripDistance st)
                     20000.00395374127 (.getTotalDistance st)
                     123 (.getSpeed st)
                     20322 (.getRpm st)
                     27 (.getFuelLevel st)
                     st (first (tx-shard/getValue shard)))

          (.stop base-command-grinder)

          (jdbc/execute! ds ["DROP TABLE cars"]))))))

#_(deftest test-dashboard-sink
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
      (let [dashboard-sink (->DashboardSink state name 1 conf 1000 "ms")
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
