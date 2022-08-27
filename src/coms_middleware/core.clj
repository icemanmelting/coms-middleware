(ns coms-middleware.core
  (:require [clojure.tools.logging :as log]
            [clojure-data-grinder-core.protocols.impl :as impl]
            [clojure.string :as str]
            [coms-middleware.comm-protocol-interpreter :as interpreter]
            [clojure-data-grinder-tx-manager.protocols.transaction-shard :as tx-shard]
            [clojure.core.async.impl.concurrent :as conc]
            [next.jdbc.sql :as jdbc-sql]
            [next.jdbc :as jdbc]
            [clojure-data-grinder-core.protocols.protocols :as p]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager :as tx-mng]
            [clojure-data-grinder-tx-manager.protocols.common :as tx-common]
            [clojure-data-grinder-tx-manager.protocols.transaction :as tx])
  (:import (java.net DatagramPacket DatagramSocket InetSocketAddress Socket ServerSocket)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream InputStream)
           (java.util.concurrent Executors)
           (java.util UUID)
           (pt.iceman.middleware.cars Trip)
           (java.sql Timestamp))
  (:gen-class))

(defn make-socket
  ([] (new DatagramSocket))
  ([port] (new DatagramSocket port)))

(defn receive-packet
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload message as a string."
  [^DatagramSocket socket]
  (let [buffer (byte-array 3)
        packet (DatagramPacket. buffer 3)]
    (when-not (.isClosed socket)
      (.receive socket packet)
      (.getData packet))))

(defn send-packet
  "Send a short textual message over a DatagramSocket to the specified
  host and port. If the string is over 512 bytes long, it will be
  truncated."
  [socket payload host port]
  (let [length (alength payload)
        address (InetSocketAddress. ^String host ^long port)
        packet (DatagramPacket. payload length address)]
    (.send socket packet)))

(defn bytes-to-int
  ([bytes]
   (bit-or (bit-and (first bytes)
                    0xFF)
           (bit-and (bit-shift-left (second bytes) 8)
                    0xFF00))))

(defn byte-to-int [byte]
  (bit-and byte 0xFF))

(defn- process-command [cmd-ar]
  (let [ar-size (count cmd-ar)
        command (byte-to-int (first cmd-ar))]
    (if (= 1 ar-size)
      {:command command}
      {:command command
       :value (bytes-to-int (rest cmd-ar))})))

(defrecord MCUSource [name connects-to tx poll-frequency time-unit execution-state mutable-state port]
  p/Source
  p/Step
  (init [this]
    (reset! execution-state (impl/make-standard-state))
    (let [socket (make-socket port)
          executor (Executors/newScheduledThreadPool 1 (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))]
      (swap! mutable-state assoc
             :socket socket
             :scheduled-fns [(p/->future-loop this
                                              executor
                                              1
                                              connects-to
                                              name
                                              execution-state
                                              poll-frequency
                                              time-unit
                                              (fn [_]
                                                (log/debug "Calling out MCUSource" name)
                                                (-> socket
                                                    (receive-packet)
                                                    (byte-array)
                                                    process-command)))]
             :executor executor)
      (log/info "Initialized MCUSource " name)))
  (validate [_]
    (let [result (cond-> (impl/validate-tx-config tx)
                   (not port) (conj "Does not contain port")
                   (not (number? port)) (conj "port needs to be a numeric value from 0 to 65535"))]
      (if (seq result)
        (throw (ex-info "Problem validating MCUSource conf!" {:message (str/join ", " result)}))
        (log/debug "MCUSource " name " validated"))))
  (getState [_] @execution-state)
  (getTxConf [_] tx)
  (isFailFast [_ _] (-> tx :fail-fast?))
  (stop [_]
    (log/info "Stopping MCUSource" name)
    (impl/stop-common-step execution-state mutable-state)
    (.close (:socket @mutable-state))
    (log/info "Stopped MCUSource" name)))

(extend MCUSource p/Dispatcher impl/default-dispatcher-implementation)
(extend MCUSource p/Outputter impl/common-outputter-implementation)
(extend MCUSource p/Taker impl/common-taker-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord SocketClientHandler [in-stream tx connects-to state]
  Runnable
  (run [this]
    (while true
      (let [buff (byte-array 3)
            _ (.read ^InputStream in-stream buff)
            v (process-command buff)
            tx (tx-mng/startTx @tx-common/transaction-manager tx)
            shard (tx/addShard tx v connects-to :ok)]
        (swap! state #(impl/m->inc-vals % [:total-step-calls :successful-step-calls]))
        (p/output-to-channel this name nil nil connects-to shard)))))

(defrecord SocketServerSource [name connects-to tx execution-state mutable-state port]
  p/Source
  p/Step
  (init [_]
    (reset! execution-state (impl/make-standard-state))
    (let [^ServerSocket socket (ServerSocket. port)]
      (swap! mutable-state assoc
             :socket socket
             :scheduled-fns [(future
                               (loop []
                                 (let [^Socket client (.accept socket)
                                       _ (log/info "Client Connected" client)
                                       handler (SocketClientHandler. (.getInputStream client)
                                                             tx
                                                             connects-to
                                                             execution-state)]
                                   (.start (Thread. handler)))
                                 (recur)))]))
    (log/info "Initialized SocketServerSource" name))
  (validate [_]
    (let [result (cond-> (impl/validate-tx-config tx)
                   (not port) (conj "Does not contain port")
                   (not (number? port)) (conj "port needs to be a numeric value from 0 to 65535"))]
      (if (seq result)
        (throw (ex-info "Problem validating SocketServerSource conf!" {:message (str/join ", " result)}))
        (log/debug "SocketServerSource " name " validated"))))
  (getState [_] (dissoc @execution-state :execution-times))
  (getTxConf [_] tx)
  (isFailFast [_ _] (-> tx :fail-fast?))
  (stop [_]
    (log/info "Stopping SocketServerSource" name)
    (.close (:socket @mutable-state))
    (impl/stop-common-step execution-state mutable-state)
    (log/info "Socket connection released")
    (log/info "Stopped SocketServerSource" name)))

(extend SocketClientHandler p/Outputter impl/common-outputter-implementation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def base-command (ICEBased.))

(def trip (Trip.))

(defn- db-settings->basecommand [res]
  (doto base-command
    (.setIgnition false)
    (.setSpeed 0)
    (.setRpm 0)
    (.setFuelLevel 0)
    (.setEngineTemperature 0)
    (.setCarId (:cars/id res))
    (.setTripDistance (:cars/trip_kilometers res))
    (.setTotalDistance (:cars/constant_kilometers res))))

(defrecord BaseCommandGrinder [name threads connects-to tx poll-frequency time-unit execution-state mutable-state car-id db-cfg]
  p/Grinder
  (grind [_ _ shard]
    (let [v (tx-shard/getValue shard)]
      (log/debug "Grinding value " v " on Grinder " name)
      (let [res (interpreter/ignition-state (.isIgnition base-command) base-command trip v)]
        (tx-shard/updateValue shard res))
      shard))
  p/Step
  (init [this]
    (reset! execution-state (impl/make-standard-state))
    (let [car-id (-> car-id (UUID/fromString))
          ds (jdbc/get-datasource db-cfg)
          car-settings (first (jdbc/execute! ds ["select id, trip_kilometers, constant_kilometers from cars where id = ?" car-id]))
          _ (when-not car-settings (throw (ex-info (str "No settings found for car-id " car-id) {:car-id car-id})))
          _ (db-settings->basecommand car-settings)
          _ (doto trip (.setInitialized false))
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))]
      (swap! mutable-state assoc
             :scheduled-fns [(p/take->future-loop this
                                                  executor
                                                  threads
                                                  connects-to
                                                  name
                                                  execution-state
                                                  poll-frequency
                                                  time-unit
                                                  (fn [t shard]
                                                    (p/grind this t shard)))]
             :executor executor)
      (log/info "Initialized BaseCommandGrinder " name)))
  (validate [_]
    (let [result (cond-> (concat (impl/validate-threads threads)
                                 (impl/validate-polling poll-frequency time-unit)
                                 (impl/validate-optional-tx-config tx))
                   (not car-id) (conj "car-id is missing")
                   (not db-cfg) (conj "db-cfg is missing"))]
      (if-not (seq result)
        (log/debug "Grinder " name " validated")
        (throw (ex-info "Problem validating Grinder conf!" {:message (str/join ", " result)})))))
  (getState [_] @execution-state)
  (isFailFast [_ _] (-> tx :fail-fast?))
  (getTxConf [_] tx)
  (stop [_]
    (log/info "Stopping BaseCommandGrinder" name)
    (impl/stop-common-step execution-state mutable-state)
    (log/info "Stopped BaseCommandGrinder" name)))

(extend BaseCommandGrinder p/Dispatcher impl/default-dispatcher-implementation)
(extend BaseCommandGrinder p/TxPurifier impl/default-tx-purifier-implementation)
(extend BaseCommandGrinder p/Taker impl/common-taker-implementation)
(extend BaseCommandGrinder p/Outputter impl/common-outputter-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn serialize
  "Serializes value, returns a byte array"
  [v]
  (let [buff (ByteArrayOutputStream. 1024)]
    (with-open [dos (ObjectOutputStream. buff)]
      (.writeObject dos v))
    (.toByteArray buff)))

(defrecord DashboardSink [name threads connects-to fns tx poll-frequency time-unit execution-state mutable-state destination-host
                          destination-port]
  p/Sink
  (sink [_ _ shard]
    (let [[_ & commands :as v] (tx-shard/getValue shard)
          out-stream (:out-stream @mutable-state)]
      (log/debug "Sinking value " v " to " name)
      (when (seq commands)
        (doseq [c commands]
          (.writeObject out-stream c)
          (.flush out-stream)
          (.reset out-stream)))
      shard))
  p/Step
  (validate [_]
    (let [result (cond-> (concat (impl/validate-threads threads)
                                 (impl/validate-polling poll-frequency time-unit)
                                 (impl/validate-tx-config tx))
                   (not destination-host) (conj "Does not contain destination host")
                   (not destination-port) (conj "Does not contain destination port"))]
      (if (seq result)
        (throw (ex-info "Problem validating DashboardSink conf!" {:message (str/join ", " result)}))
        (log/debug "DashboardSink " name " validated"))))
  (init [this]
    (reset! execution-state (impl/make-standard-state))
    (let [executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))
          socket (Socket. destination-host destination-port)
          out-stream (ObjectOutputStream. (.getOutputStream socket))
          _ (.flush out-stream)]
      (swap! mutable-state
             assoc
             :out-stream out-stream
             :scheduled-fns [(p/take->future-loop this
                                                  executor
                                                  threads
                                                 connects-to
                                                  name
                                                  execution-state
                                                  poll-frequency
                                                  time-unit
                                                  (fn [t shard]
                                                    (p/sink this t shard)))]
             :executor executor)
      (log/info "Initialized DashboardSink " name)))
  (getState [_] @execution-state)
  (isFailFast [_ _] (-> tx :fail-fast?))
  (getTxConf [_] tx)
  (stop [_]
    (log/info "Stopping DashboardSink" name)
    (impl/stop-common-step execution-state mutable-state)
    (when-let [out-stream (:out-stream @mutable-state)]
      (try (.close out-stream) (catch Exception _)))
    (log/info "Stopped DashboardSink" name)))

(extend DashboardSink p/Dispatcher impl/sink-dispatcher-implementation)
(extend DashboardSink p/TxPurifier impl/default-tx-purifier-implementation)
(extend DashboardSink p/Taker impl/common-taker-implementation)
(extend DashboardSink p/Outputter impl/common-outputter-implementation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<Jobs>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn save-base-command [] base-command)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</Jobs>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<JDBCSink>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- command->update-km [command]
  {:constant_kilometers (.getTotalDistance command)
   :trip_kilometers (.getTripDistance command)})

(defn- avg-speed [start-km end-km start-time end-time]
  (let [ms (- (.getTime end-time) (.getTime start-time))
        hours (-> ms (/ 1000) (/ 3600) double)]
    (double (/ (- end-km start-km) hours))))

(defn add-to-db [conn _]
  (jdbc-sql/update! conn
                    :cars
                    (command->update-km base-command)
                    ["id=?" (.getCarId base-command)])
  (when (and (not (.isSaved trip))
             (.isInitialized trip))
    (jdbc-sql/insert! conn :trips {:id (.getId trip)
                                   :car_id (.getCarId trip)
                                   :start_km (.getStartKm trip)
                                   :start_temp (.getStartTemp trip)
                                   :start_fuel (.getStartFuel trip)
                                   :start_time (.getStartTime trip)})
    (doto trip (.setSaved true)))
  (when (and (.isSaved trip)
             (not (.isUpdated trip))
             (not (.isIgnition base-command)))
    (jdbc-sql/update! conn
                      :trips
                      {:end_km (.getEndKm trip)
                       :end_temp (.getEndTemp trip)
                       :end_fuel (.getEndFuel trip)
                       :max_temp (.getMaxTemp trip)
                       :max_speed (.getMaxSpeed trip)
                       :trip_length_km (- (.getEndKm trip) (.getStartKm trip))
                       :trip_duration_ms (- (.getTime (.getEndTime trip))
                                         (.getTime (.getStartTime trip)))
                       :end_time (.getEndTime trip)
                       :avg_speed (avg-speed (.getStartKm trip)
                                             (.getEndKm trip)
                                             (.getStartTime trip)
                                             (.getEndTime trip))}
                      ["id=?" (.getId trip)])
    (doto trip (.setUpdated true)))

  (when (and (.isSaved trip)
             (.isIgnition base-command))
    (jdbc-sql/insert! conn :speed_data {:id (UUID/randomUUID)
                                        :car_id (.getCarId base-command)
                                        :trip_id (.getId trip)
                                        :speed (.getSpeed base-command)
                                        :rpm (.getRpm base-command)
                                        :gear 0
                                        :timestamp (Timestamp. (System/currentTimeMillis))})
    (jdbc-sql/insert! conn :temperature_data {:id (UUID/randomUUID)
                                              :car_id (.getCarId base-command)
                                              :trip_id (.getId trip)
                                              :value (.getEngineTemperature base-command)
                                              :timestamp (Timestamp. (System/currentTimeMillis))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</JDBCSink>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
