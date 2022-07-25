(ns coms-middleware.core
  (:require [clojure.tools.logging :as log]
            [clojure-data-grinder-core.protocols.protocols :as c]
            [clojure-data-grinder-core.protocols.impl :as impl]
            [clojure-data-grinder-core.common :as common]
            [clojure-data-grinder-core.validation :as v]
            [clojure.string :as str]
            [coms-middleware.car-state :as c-state]
            [clojure-data-grinder-tx-manager.protocols.transaction-shard :as tx-shard]
            [clojure.core.async.impl.concurrent :as conc]
            [next.jdbc.sql :as jdbc-sql])
  (:import (java.net DatagramPacket DatagramSocket InetSocketAddress)
           (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ev EVBased)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream)
           (clojure.lang Symbol)
           (coms_middleware.car_state CarState)
           (java.util.concurrent Executors))
  (:gen-class))

(defn make-socket
  ([] (new DatagramSocket))
  ([port] (new DatagramSocket port)))

(defn receive-packet
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload message as a string."
  [^DatagramSocket socket buffer-size]
  (let [buffer (byte-array buffer-size)
        packet (DatagramPacket. buffer buffer-size)]
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

(defrecord MCUSource [state name conf poll-frequency-ms]
  c/Source
  c/Step
  (init [this]
    (let [out-ch (-> conf deref :channels :out :output-channel)
          port (-> conf deref :port)
          executor (Executors/newScheduledThreadPool 1
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))
          socket (make-socket port)]
      (swap! conf assoc
             :socket socket
             :scheduled-fns [(impl/start-loop this
                                             executor
                                             1
                                             out-ch
                                             name
                                             state
                                             poll-frequency-ms
                                             (fn []
                                               (log/debug "Calling out MCUSource" name)
                                               (-> socket
                                                   (receive-packet (:buffer-size @conf))
                                                   (byte-array)
                                                   (ByteBuffer/wrap)))
                                             [:successful-step-calls :total-step-calls]
                                             [:unsuccessful-step-calls :total-step-calls])]
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
                                    :threads v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->MCUSource" [_ setup]
  (let [[_ err] (v/validate mcu-source-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating MCUSource" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->MCUSource"
  [impl {name :name
         conf :conf
         {^Symbol clean-up-fn :clean-up-fn fail-fast? :fail-fast? retries :retries} :tx
         ^Symbol v-fn :v-fn
         ^Symbol x-fn :x-fn
         pf :poll-frequency-ms
         threads :threads}]
  (let [[v-fn x-fn clean-up-fn] (common/resolve-all-functions v-fn x-fn clean-up-fn)
        v-fn (or v-fn (fn [_] nil))
        conf (assoc conf :tx {:clean-up-fn clean-up-fn
                              :fail-fast? (or fail-fast? false)
                              :retries retries})
        setup {:v-fn v-fn
               :x-fn x-fn
               :poll-frequency-ms pf
               :name name
               :conf conf
               :threads threads
               :state (atom {:successful-source-calls 0
                             :unsuccessful-source-calls 0
                             :source-calls 0
                             :stopped false})}]
    [(impl/step-config->instance name impl setup)]))

(extend MCUSource c/Dispatcher impl/default-dispatcher-implementation)
(extend MCUSource c/Outputter impl/common-outputter-implementation)
(extend MCUSource c/Taker impl/common-taker-implementation)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<MCUOutGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti get-command-type (fn [type ^ByteBuffer _] type))

(defmethod get-command-type :ice [_ ^ByteBuffer value]
  (ICEBased. value))

(defmethod get-command-type :ev [_ ^ByteBuffer value]
  (EVBased. value))

(defrecord MCUOutGrinder [state name threads conf poll-frequency-ms]
  c/Grinder
  (grind [_ shard]
    (let [v (tx-shard/getValue shard)]
      (log/debug "Grinding value " v " on Grinder " name)
      (tx-shard/updateValue shard (get-command-type (:type @conf) v))
      shard))
  c/Step
  (init [this]
    (let [in (-> conf deref :channels :in)
          out-ch (-> conf deref :channels :out :output-channel)
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))]
      (swap! conf assoc
             :scheduled-fns [(impl/start-take-loop this
                                                  executor
                                                  threads
                                                  in
                                                  out-ch
                                                  name
                                                  state
                                                  poll-frequency-ms
                                                  (fn [shard]
                                                    (c/grind this shard))
                                                  [:total-step-calls :successful-step-calls]
                                                  [:total-step-calls :unsuccessful-step-calls])]
             :executor executor)
      (log/info "Initialized MCUOutGrinder " name)))
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                   (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                   (not (-> conf deref :type)) (conj "Does not contain vehicle type"))]
      (if (seq result)
        (throw (ex-info "Problem validating MCUOutGrinder conf!" {:message (str/join ", " result)}))
        (log/debug "MCUOutGrinder " name " validated"))))
  (getState [_] @state)
  (isFailFast [_ _] (-> @conf :tx :fail-fast?))
  (getConf [_] @conf)
  (stop [_]
    (log/info "Stopping MCUOutGrinder" name)
    (impl/stop-common-step state conf)
    (log/info "Stopped MCUOutGrinder" name)))

(def ^:private mcu-grinder-impl-fmt {:state v/atomic
                                     :name v/non-empty-str
                                     :conf v/atomic
                                     :v-fn v/not-nil
                                     :x-fn v/not-nil
                                     :threads v/numeric
                                     :poll-frequency-ms v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->MCUOutGrinder" [_ setup]
  (let [[_ err] (v/validate mcu-grinder-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating MCUOutGrinder" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->MCUOutGrinder"
  [impl {name :name
         conf :conf
         tx :tx
         ^Symbol v-fn :v-fn
         ^Symbol x-fn :x-fn
         pf :poll-frequency-ms
         threads :threads}]
  (let [conf (assoc conf :tx tx)
        [v-fn x-fn] (common/resolve-all-functions v-fn x-fn)
        v-fn (or v-fn (fn [_] nil))
        setup {:v-fn v-fn
               :x-fn x-fn
               :poll-frequency-ms pf
               :name name
               :conf conf
               :threads threads
               :state (atom {:total-step-calls 0
                             :successful-step-calls 0
                             :unsuccessful-step-calls 0
                             :stopped false})}]
    [(impl/step-config->instance name impl setup)]))

(extend MCUOutGrinder c/Dispatcher impl/default-dispatcher-implementation)
(extend MCUOutGrinder c/TxPurifier impl/default-tx-purifier-implementation)
(extend MCUOutGrinder c/Taker impl/common-taker-implementation)
(extend MCUOutGrinder c/Outputter impl/common-outputter-implementation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</MCUOutGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord BaseCommandGrinder [state name threads conf poll-frequency-ms]
  c/Grinder
  (grind [_ shard]
    (let [v (tx-shard/getValue shard)]
      (log/debug "Grinding value " v " on Grinder " name)
      (let [^CarState car-state (c/getStateValue @impl/global-state-manager :car-state)]
        (tx-shard/updateValue shard (.processCommand car-state v conf)))
      shard))
  c/Step
  (init [this]
    (let [in (-> conf deref :channels :in)
          out-ch (-> conf deref :channels :out :output-channel)
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))
          tyre-circumference (-> conf deref :tyre-circumference)
          car-type (-> conf deref :type)
          car-state (doto (c-state/get-car-state car-type)
                      (.setTyreCircumference tyre-circumference))]
      (c/alterStateValue @impl/global-state-manager :car-state car-state)
      (swap! conf assoc
             :scheduled-fns [(impl/start-take-loop this
                                                  executor
                                                  threads
                                                  in
                                                  out-ch
                                                  name
                                                  state
                                                  poll-frequency-ms
                                                  (fn [shard]
                                                    (c/grind this shard))
                                                  [:total-step-calls :successful-step-calls]
                                                  [:total-step-calls :unsuccessful-step-calls])]
             :executor executor)
      (log/info "Initialized BaseCommandGrinder " name)))
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                   (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                   (not (-> conf deref :type)) (conj "Does not contain vehicle type")
                   (not (-> conf deref :tyre-circumference)) (conj "Does not contain vehicle tyre circumference"))]
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
                                             :v-fn v/not-nil
                                             :x-fn v/not-nil
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
         ^Symbol v-fn :v-fn
         ^Symbol x-fn :x-fn
         pf :poll-frequency-ms
         threads :threads}]
  (let [conf (assoc conf :tx tx)
        [v-fn x-fn] (common/resolve-all-functions v-fn x-fn)
        v-fn (or v-fn (fn [_] nil))
        setup {:v-fn v-fn
               :x-fn x-fn
               :poll-frequency-ms pf
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
  (sink [_ shard]
    (let [v (tx-shard/getValue shard)]
      (log/debug "Sinking value " v " to " name)
      (tx-shard/updateValue shard (send-packet (:socket @conf) (serialize v) (:destination-host @conf) (:destination-port @conf)))
      shard))
  c/Step
  (validate [_]
    (let [result (cond-> []
                   (not (-> conf deref :channels :in)) (conj "Does not contain in-channel")
                   (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                   (not (-> conf deref :destination-host)) (conj "Does not contain destination host")
                   (not (-> conf deref :destination-port)) (conj "Does not contain destination port"))]
      (if (seq result)
        (throw (ex-info "Problem validating DashboardSink conf!" {:message (str/join ", " result)}))
        (log/debug "DashboardSink " name " validated"))))
  (init [this]
    (let [in (-> conf deref :channels :in)
          executor (Executors/newScheduledThreadPool threads
                                                     (conc/counted-thread-factory (str "sn0wf1eld-" name "-%d") true))
          socket (make-socket (:source-port @conf))]
      (swap! conf
             assoc
             :socket socket
             :scheduled-fns [(impl/start-take-loop this
                                                  executor
                                                  threads
                                                  in
                                                  nil
                                                  name
                                                  state
                                                  poll-frequency-ms
                                                  (fn [shard]
                                                    (c/sink this shard))
                                                  [:total-step-calls :successful-step-calls]
                                                  [:total-step-calls :unsuccessful-step-calls])]
             :executor executor)
      (log/info "Initialized DashboardSink " name)))
  (getState [_] @state)
  (isFailFast [_ _] (-> @conf :tx :fail-fast?))
  (getConf [_] @conf)
  (stop [_]
    (log/info "Stopping DashboardSink" name)
    (impl/stop-common-step state conf)
    (.close (:socket @conf))
    (log/info "Stopped DashboardSink" name)))

(def ^:private mcu-sink-fmt {:state v/atomic
                             :name v/non-empty-str
                             :conf v/atomic
                             :v-fn v/not-nil
                             :x-fn v/not-nil
                             :threads v/numeric
                             :poll-frequency-ms v/numeric})

(defmethod impl/validate-step-setup "coms-middleware.core/map->DashboardSink" [_ setup]
  (let [[_ err] (v/validate mcu-sink-fmt setup)]
    (when err
      (throw (ex-info "Problem validating SinkImpl" {:message (v/humanize-error err)})))))

(defmethod impl/bootstrap-step "coms-middleware.core/map->DashboardSink"
  [impl {name :name
         conf :conf
         tx :tx
         ^Symbol v-fn :v-fn
         ^Symbol x-fn :x-fn
         pf :poll-frequency-ms
         threads :threads}]
  (let [conf (assoc conf :tx tx)
        [v-fn x-fn] (common/resolve-all-functions v-fn x-fn)
        v-fn (or v-fn (fn [_] nil))
        setup {:v-fn v-fn
               :x-fn x-fn
               :name name
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

;CREATE TABLE IF NOT EXISTS cars (
;                                  id UUID,
;                                     constant_kilometers DOUBLE PRECISION,
;                                     trip_kilometers DOUBLE PRECISION,
;                                     trip_initial_fuel_level DOUBLE PRECISION,
;                                     average_fuel_consumption DOUBLE PRECISION,
;                                     dashboard_type TEXT,
;                                     tyre_offset DOUBLE PRECISION,
;                                     next_oil_change DOUBLE PRECISION,
;
;                                     PRIMARY KEY (id)
;                                     --     FOREIGN KEY (owner) REFERENCES users (login)
;                                     );
(defn- command->update-km [command]
  {:constant_kilometers (.getTotalDistance command)
   :trip_kilometers (.getTripDistance command)})

(defn add-to-db [conf obj]
  (jdbc-sql/update! (:conn conf)
                    :cars
                    (command->update-km obj)
                    ["id=?" (:car-id conf)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</JDBCSink>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
