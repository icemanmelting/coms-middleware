(ns coms-middleware.core
  (:require [clojure.tools.logging :as log]
            [clojure-data-grinder-core.protocols.protocols :as c]
            [clojure-data-grinder-core.protocols.impl :as impl]
            [clojure-data-grinder-core.common :as common]
            [clojure-data-grinder-core.validation :as v]
            [clojure.string :as str]
            [coms-middleware.comm-protocol-interpreter :as interpreter]
            [clojure-data-grinder-tx-manager.protocols.transaction-shard :as tx-shard]
            [clojure.core.async.impl.concurrent :as conc]
            [next.jdbc.sql :as jdbc-sql]
            [next.jdbc :as jdbc]
            [clojure-data-grinder-core.protocols.protocols :as p]
            [clojure-data-grinder-tx-manager.protocols.transaction-manager :as tx-mng]
            [clojure-data-grinder-tx-manager.protocols.common :as tx-common]
            [clojure-data-grinder-core.statistics :as stats]
            [clojure-data-grinder-tx-manager.protocols.transaction :as tx]
            [clojure.core.async :as async])
  (:import (java.net DatagramPacket DatagramSocket InetSocketAddress Socket)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream)
           (clojure.lang Symbol)
           (java.util.concurrent Executors)
           (java.util UUID))
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

(defrecord MCUSource [state name conf poll-frequency-ms]
  c/Source
  c/Step
  (init [this]
    (let [out-ch (-> conf deref :channels :out :output-channel)
          port (-> conf deref :port)
          socket (make-socket port)
          executor (Executors/newScheduledThreadPool 1 (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))]
      (swap! conf assoc
             :socket socket
             :scheduled-fns [(p/->future-loop this
                                              executor
                                              1
                                              out-ch
                                              name
                                              state
                                              poll-frequency-ms
                                              (fn [_]
                                                (log/debug "Calling out MCUSource" name)
                                                (-> socket
                                                    (receive-packet)
                                                    (byte-array)
                                                    process-command)))]
             :executor executor)
      (log/info "Initialized MCUSource " name)))
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :port)) (conj "No listening port configured")
                   (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                   (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                   (not (-> conf deref :tx :clean-up-fn)) (conj "Does not contain cleanup function"))]
      (if (seq result)
        (throw (ex-info "Problem validating MCUSource conf!" {:message (str/join ", " result)}))
        (log/debug "Source " name " validated"))))
  (getState [_] @state)
  (getConf [_] @conf)
  (isFailFast [_ _] (-> @conf :tx :fail-fast?))
  (stop [_]
    (log/info "Stopping MCUSource" name)
    (impl/stop-common-step state conf)
    (.close (:socket @conf))
    (log/info "Stopped MCUSource" name)))

(def ^:private mcu-source-impl-fmt {:state v/atomic
                                    :name v/non-empty-str
                                    :conf v/atomic
                                    :threads v/numeric
                                    :poll-frequency-ms v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->MCUSource" [_ setup]
  (let [[_ err] (v/validate mcu-source-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating MCUSource" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->MCUSource"
  [impl {name :name
         conf :conf
         {^Symbol clean-up-fn :clean-up-fn fail-fast? :fail-fast? retries :retries} :tx
         pf :poll-frequency-ms
         threads :threads}]
  (let [[clean-up-fn] (common/resolve-all-functions clean-up-fn)
        conf (assoc conf :tx {:clean-up-fn clean-up-fn
                              :fail-fast? (or fail-fast? false)
                              :retries retries})
        setup {:poll-frequency-ms pf
               :name name
               :conf conf
               :threads threads
               :state (atom {:successful-step-calls 0
                             :unsuccessful-step-calls 0
                             :total-step-calls 0
                             :stopped false})}]
    [(impl/step-config->instance name impl setup)]))

(extend MCUSource c/Dispatcher impl/default-dispatcher-implementation)
(extend MCUSource c/Outputter impl/common-outputter-implementation)
(extend MCUSource c/Taker impl/common-taker-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- db-settings->basecommand [res]
  (let [basecommand (ICEBased.)]
    (doto basecommand
      (.setIgnition false)
      (.setSpeed 0)
      (.setRpm 0)
      (.setFuelLevel 0)
      (.setEngineTemperature 0)
      (.setCarId (:cars/id res))
      (.setTripDistance (:cars/trip_kilometers res))
      (.setTotalDistance (:cars/constant_kilometers res)))))

(defrecord BaseCommandGrinder [state name threads conf poll-frequency-ms]
  c/Grinder
  (grind [_ _ shard]
    (let [v (tx-shard/getValue shard)]
      (log/debug "Grinding value " v " on Grinder " name)
      (let [^ICEBased car-state (c/getStateValue @impl/global-state-manager :car-state)
            [car-state & _ :as res] (interpreter/ignition-state (.isIgnition car-state) car-state v)]
        (c/alterStateValue @impl/global-state-manager :car-state car-state)
        (tx-shard/updateValue shard res))
      shard))
  c/Step
  (init [this]
    (let [in (-> conf deref :channels :in)
          out-ch (-> conf deref :channels :out :output-channel)
          car-id (-> conf deref :car-id (UUID/fromString))
          db-cfg (-> conf deref :db-cfg)
          ds (jdbc/get-datasource db-cfg)
          car-settings (first (jdbc/execute! ds ["select id, trip_kilometers, constant_kilometers from cars where id = ?" car-id]))
          _ (when-not car-settings (throw (ex-info (str "No settings found for car-id " car-id) {:car-id car-id})))
          basecommand (db-settings->basecommand car-settings)
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))]
      (c/alterStateValue @impl/global-state-manager :car-state basecommand)
      (swap! conf assoc
             :scheduled-fns [(p/take->future-loop this
                                                  executor
                                                  threads
                                                  in
                                                  out-ch
                                                  name
                                                  state
                                                  poll-frequency-ms
                                                  (fn [t shard]
                                                    (c/grind this t shard)))]
             :executor executor)
      (log/info "Initialized BaseCommandGrinder " name)))
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                   (nil? (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast"))]
      (if (seq result)
        (throw (ex-info "Problem validating BaseCommandGrinder conf!" {:message (str/join ", " result)}))
        (log/debug "BaseCommandGrinder " name " validated"))))
  (getState [_] @state)
  (isFailFast [_ _] (-> @conf :tx :fail-fast?))
  (getConf [_] @conf)
  (stop [_]
    (log/info "Stopping BaseCommandGrinder" name)
    (impl/stop-common-step state conf)
    (log/info "Stopped BaseCommandGrinder" name)))

(def ^:private basecommand-grinder-impl-fmt {:state v/atomic
                                             :name v/non-empty-str
                                             :conf v/atomic
                                             :threads v/numeric
                                             :poll-frequency-ms v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->BaseCommandGrinder" [_ setup]
  (let [[_ err] (v/validate basecommand-grinder-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating BaseCommandGrinder" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->BaseCommandGrinder"
  [impl {name :name
         conf :conf
         tx :tx
         pf :poll-frequency-ms
         threads :threads}]
  (let [conf (assoc conf :tx tx)
        setup {:poll-frequency-ms pf
               :name name
               :conf conf
               :threads threads
               :state (atom {:total-step-calls 0
                             :successful-step-calls 0
                             :unsuccessful-step-calls 0
                             :stopped false})}]
    [(impl/step-config->instance name impl setup)]))

(extend BaseCommandGrinder c/Dispatcher impl/default-dispatcher-implementation)
(extend BaseCommandGrinder c/TxPurifier impl/default-tx-purifier-implementation)
(extend BaseCommandGrinder c/Taker impl/common-taker-implementation)
(extend BaseCommandGrinder c/Outputter impl/common-outputter-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn serialize
  "Serializes value, returns a byte array"
  [v]
  (let [buff (ByteArrayOutputStream. 1024)]
    (with-open [dos (ObjectOutputStream. buff)]
      (.writeObject dos v))
    (.toByteArray buff)))

(defrecord DashboardSink [state name threads conf poll-frequency-ms]
  c/Sink
  (sink [_ _ shard]
    (let [[_ & commands :as v] (tx-shard/getValue shard)
          out-stream (:out-stream @conf)]
      (log/debug "Sinking value " v " to " name)
      (doseq [c commands]
        (.writeObject out-stream c)
        (.flush out-stream)
        (.reset out-stream))
      shard))
  c/Step
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :channels :in)) (conj "Does not contain in-channel")
                   (nil? (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                   (not (-> conf deref :destination-host)) (conj "Does not contain destination host")
                   (not (-> conf deref :destination-port)) (conj "Does not contain destination port"))]
      (if (seq result)
        (throw (ex-info "Problem validating DashboardSink conf!" {:message (str/join ", " result)}))
        (log/debug "DashboardSink " name " validated"))))
  (init [this]
    (let [in (-> conf deref :channels :in)
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))
          socket (Socket. (:destination-host @conf) (:destination-port @conf))
          out-stream (ObjectOutputStream. (.getOutputStream socket))
          _ (.flush out-stream)]
      (swap! conf
             assoc
             :out-stream out-stream
             :scheduled-fns [(p/take->future-loop this
                                                  executor
                                                  threads
                                                  in
                                                  nil
                                                  name
                                                  state
                                                  poll-frequency-ms
                                                  (fn [t shard]
                                                    (c/sink this t shard)))]
             :executor executor)
      (log/info "Initialized DashboardSink " name)))
  (getState [_] @state)
  (isFailFast [_ _] (-> @conf :tx :fail-fast?))
  (getConf [_] @conf)
  (stop [_]
    (log/info "Stopping DashboardSink" name)
    (impl/stop-common-step state conf)
    (when-let [out-stream (:out-stream @conf)]
      (.close out-stream))
    (log/info "Stopped DashboardSink" name)))

(def ^:private dashboard-sink-fmt {:state v/atomic
                                   :name v/non-empty-str
                                   :conf v/atomic
                                   :threads v/numeric
                                   :poll-frequency-ms v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->DashboardSink" [_ setup]
  (let [[_ err] (v/validate dashboard-sink-fmt setup)]
    (when err
      (throw (ex-info "Problem validating SinkImpl" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->DashboardSink"
  [impl {name :name
         conf :conf
         {^Symbol clean-up-fn :clean-up-fn fail-fast? :fail-fast? retries :retries} :tx
         pf :poll-frequency-ms
         threads :threads}]
  (let [clean-up-fn (common/resolve-function clean-up-fn)
        conf (assoc conf :tx {:clean-up-fn clean-up-fn
                              :fail-fast? (or fail-fast? false)
                              :retries retries})
        setup {:name name
               :conf conf
               :threads threads
               :state (atom {:total-step-calls 0
                             :successful-step-calls 0
                             :unsuccessful-step-calls 0
                             :stopped false})
               :poll-frequency-ms pf}]
    [(impl/step-config->instance name impl setup)]))

(extend DashboardSink c/Dispatcher impl/sink-dispatcher-implementation)
(extend DashboardSink c/TxPurifier impl/default-tx-purifier-implementation)
(extend DashboardSink c/Taker impl/common-taker-implementation)
(extend DashboardSink c/Outputter impl/common-outputter-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<JDBCSink>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- command->update-km [command]
  {:constant_kilometers (.getTotalDistance command)
   :trip_kilometers (.getTripDistance command)})

(defn add-to-db [conn [basecommand & _]]
  (jdbc-sql/update! conn
                    :cars
                    (command->update-km basecommand)
                    ["id=?" (.getCarId basecommand)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</JDBCSink>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
