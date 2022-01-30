(ns coms-middleware.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop >!! chan <!! <!]]
            [clojure-data-grinder-core.core :as c]
            [clojure-data-grinder-core.validation :as v]
            [clojure.string :as str]
            [coms-middleware.car-state :as c-state])
  (:import (java.net DatagramPacket DatagramSocket InetSocketAddress)
           (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ev EVBased)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream)
           (clojure.lang Symbol)
           (coms_middleware.car_state CarState))
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
  (init [_]
    (let [out-ch (-> conf deref :channels :out :output-channel)
          tx-conf (-> conf deref :tx)
          fail-fast (:fail-fast? tx-conf)
          port (-> conf deref :port)
          socket (make-socket port)]
      (swap! conf assoc :socket socket)
      (swap! conf #(assoc % :scheduled-fns (mapv (fn [t]
                                                   (c/->future-loop _
                                                                    t
                                                                    name
                                                                    state
                                                                    poll-frequency-ms
                                                                    (fn []
                                                                      (log/debug "Calling out MCUSource" name)
                                                                      (let [tx (c/initiate-tx _ conf)
                                                                            value (-> socket
                                                                                      (receive-packet (:buffer-size @conf))
                                                                                      (byte-array)
                                                                                      (ByteBuffer/wrap))]
                                                                        (c/output-to-channel _
                                                                                             (c/wrap-tx-value tx value :ok)
                                                                                             out-ch
                                                                                             name)))
                                                                    [:successful-source-calls :source-calls]
                                                                    fail-fast
                                                                    [:unsuccessful-source-calls :source-calls]))
                                                 [0])))
      (log/info "Initialized MCUSource " name)))
  (validate [this]
    (let [result (cond-> []
                         (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                         (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                         (not (-> conf deref :tx :clean-up-fn)) (conj "Does not contain cleanup function"))]
      (if (seq result)
        (throw (ex-info "Problem validating MCUSource conf!" {:message (str/join ", " result)}))
        (log/debug "Source " name " validated"))))
  (getState [this] @state)
  (stop [this]
    (log/info "Stopping MCUSource" name)
    (doseq [f (:scheduled-fns @conf)]
      (when f
        (future-cancel f)))
    (.close (:socket @conf))
    (swap! state #(assoc % :stopped true))
    (log/info "Stopped MCUSource" name)))

(extend MCUSource c/Outputter c/common-outputter-implementation)
(extend MCUSource c/ITransactional c/common-transactional-implementation)
(extend MCUSource c/Taker c/common-taker-implementation)

(def ^:private mcu-source-impl-fmt {:state v/atomic
                                    :name v/non-empty-str
                                    :conf v/atomic
                                    :threads v/numeric})

(defmethod c/validate-step-setup "clojure-data-grinder-core.core/map->MCUSource" [_ setup]
  (let [[_ err] (v/validate mcu-source-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating MCUSource" {:message (v/humanize-error err)})))))

(defmethod c/bootstrap-step "clojure-data-grinder-core.core/map->MCUSource"
  [impl {name :name
         {{out :out} :channels :as conf} :conf
         {^Symbol clean-up-fn :clean-up-fn fail-fast? :fail-fast? retries :retries} :tx
         ^Symbol v-fn :v-fn
         ^Symbol x-fn :x-fn
         pf :poll-frequency-ms
         threads :threads} channels]
  (let [conf (c/conf-out-channels->conf-real-out-channels conf out channels)
        [v-fn x-fn clean-up-fn] (c/resolve-all-functions v-fn x-fn clean-up-fn)
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
    (c/step-config->instance name impl setup)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<MCUOutGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti ^:private get-command-type (fn [type ^ByteBuffer _] type))

(defmethod ^:private get-command-type :ice [_ ^ByteBuffer value]
  (ICEBased. value))

(defmethod ^:private get-command-type :ev [_ ^ByteBuffer value]
  (EVBased. value))

(defrecord MCUOutGrinder [state name threads conf poll-frequency-ms]
  c/Grinder
  (grind [_ value]
    (get-command-type (:type @conf) value))
  c/Step
  (init [this]
    (let [in (-> conf deref :channels :in)
          out-ch (-> conf deref :channels :out :output-channel)
          fail-fast (-> conf deref :tx :fail-fast?)]
      (swap! conf assoc :scheduled-fns (mapv (fn [t]
                                               (c/take->future-loop this
                                                                    t
                                                                    in
                                                                    out-ch
                                                                    name
                                                                    state
                                                                    poll-frequency-ms
                                                                    (fn [v]
                                                                      (c/grind this v))
                                                                    [:grinding-operations :successful-grinding-operations]
                                                                    fail-fast
                                                                    [:grinding-operations :unsuccessful-grinding-operations]))
                                             (range 0 threads)))
      (log/info "Initialized MCUOutGrinder " name)))
  (validate [this]
    (let [result (cond-> []
                         (not (-> conf deref :channels :out :output-channel)) (conj "Does not contain out-channel")
                         (not (-> conf deref :tx :fail-fast?)) (conj "Does not contain fail fast")
                         (not (-> conf deref :type)) (conj "Does not contain vehicle type"))]
      (if (seq result)
        (throw (ex-info "Problem validating MCUOutGrinder conf!" {:message (str/join ", " result)}))
        (log/debug "MCUOutGrinder " name " validated"))))
  (getState [this] @state)
  (stop [this]
    (log/info "Stopping MCUOutGrinder" name)
    (doseq [f (:scheduled-fns @conf)]
      (when f
        (future-cancel f)))
    (swap! state #(assoc % :stopped true))
    (log/info "Stopped MCUOutGrinder" name)))

(extend MCUOutGrinder c/Outputter c/common-outputter-implementation)
(extend MCUOutGrinder c/ITransactional c/common-transactional-implementation)
(extend MCUOutGrinder c/Dispatcher c/default-dispatcher-implementation)
(extend MCUOutGrinder c/Taker c/common-taker-implementation)

(def ^:private mcu-grinder-impl-fmt {:state v/atomic
                                     :name v/non-empty-str
                                     :conf v/atomic
                                     :threads v/numeric})

(defmethod c/validate-step-setup "clojure-data-grinder-core.core/map->MCUOutGrinder" [_ setup]
  (let [[_ err] (v/validate mcu-grinder-impl-fmt setup)]
    (when err
      (throw (ex-info "Problem validating MCUOutGrinder" {:message (v/humanize-error err)})))))

(defmethod c/bootstrap-step "clojure-data-grinder-core.core/map->MCUOutGrinder"
  [impl {name :name
         {{in :in out :out} :channels :as conf} :conf
         tx :tx
         pf :poll-frequency-ms
         threads :threads} channels]
  (let [in-ch (get @channels in)
        conf (-> conf
                 (c/conf-out-channels->conf-real-out-channels out channels)
                 (assoc-in [:channels :in] in-ch))
        conf (assoc conf :tx tx)
        setup {:poll-frequency-ms pf
               :name name
               :conf conf
               :threads threads
               :state (atom {:grinding-operations 0
                             :successful-grinding-operations 0
                             :unsuccessful-grinding-operations 0
                             :stopped false})}]
    [(c/step-config->instance name impl setup)]))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;</MCUOutGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;<BaseCommandGrinder>;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defrecord BaseCommandGrinder [state name conf threads poll-frequency-ms]
  c/Grinder
  (grind [_ value]
    (let [^CarState car-state (-> conf deref :car-state)]
      (.processCommand car-state value conf)))
  c/Step
  (init [this]
    (let [in (-> conf deref :channels :in)
          out-ch (-> conf deref :channels :out :output-channel)
          fail-fast (-> conf deref :tx :fail-fast?)
          tyre-circumference (-> conf deref :tyre-circumference)
          car-type (-> conf deref :type)
          car-state (doto (c-state/get-car-state car-type)
                      (.setTyreCircumference tyre-circumference))]
      (swap! conf assoc :car-state car-state)
      (swap! conf assoc :scheduled-fns (mapv (fn [t]
                                               (c/take->future-loop this
                                                                    t
                                                                    in
                                                                    out-ch
                                                                    name
                                                                    state
                                                                    poll-frequency-ms
                                                                    (fn [v]
                                                                      (c/grind this v))
                                                                    [:grinding-operations :successful-grinding-operations]
                                                                    fail-fast
                                                                    [:grinding-operations :unsuccessful-grinding-operations]))
                                             (range 0 threads)))
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
  (stop [_]
    (swap! state assoc :stopped true)))

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
  (sink [_ v]
    (log/debug "Sinking value " v " to " name)
    (send-packet (:socket @conf) (serialize v) (:destination-host @conf) (:destination-port @conf))
    v)
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
          fail-fast? (-> conf deref :fail-fast?)
          socket (make-socket (:port @conf))]
      (swap! conf assoc :socket socket)
      (swap! conf assoc :scheduled-fns (mapv (fn [t]
                                               (c/take->future-loop this
                                                                  t
                                                                  in
                                                                  nil
                                                                  name
                                                                  state
                                                                  poll-frequency-ms
                                                                  (fn [v]
                                                                    (c/sink this v))
                                                                  [:sink-operations :successful-sink-operations]
                                                                  fail-fast?
                                                                  [:sink-operations :unsuccessful-sink-operations]))
                                             (range 0 threads)))
      (log/info "Initialized DashboardSink " name)))
  (getState [_] @state)
  (stop [_]
    (log/info "Stopping DashboardSink" name)
    (doseq [f (:scheduled-fns @conf)]
      (future-cancel f))
    (.close (:socket @conf))
    (swap! state #(assoc % :stopped true))
    (log/info "Stopped DashboardSink" name)))
