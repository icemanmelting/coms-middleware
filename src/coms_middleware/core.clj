(ns coms-middleware.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop >!! chan <!! <!]]
            [clojure-data-grinder-core.core :as c]
            [clojure-data-grinder-core.validation :as v]
            [clojure.string :as str])
  (:import (java.net DatagramPacket DatagramSocket InetSocketAddress SocketAddress)
           (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ev EVBased)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream)
           (clojure.lang Symbol)))

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

(defrecord MCUSource [state name conf]
  c/Source
  c/Step
  (init [_]
    (let [out-ch (-> conf deref :channels :out :out-channel)
          port (-> conf deref :port)
          socket (make-socket port)]
      (swap! conf assoc :socket socket)
      (log/debug "Initialized MCUSource " name)
      (go-loop []
        (if-not (:stopped @state)
          (let [tx (c/initiate-tx _ conf)
                value (-> socket
                          (receive-packet (:buffer-size @conf))
                          (byte-array)
                          (ByteBuffer/wrap))]
            (c/output-to-channel _
                                 (c/wrap-tx-value tx value :ok)
                                 out-ch
                                 name)
            (swap! state (fn [m]
                           (c/m->inc-vals m [:successful-source-calls :source-calls])))
            (recur))
          (.close (:socket @conf))))))
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
          (log/debug "Stopping MCUSource " name)
          (swap! state assoc :stopped true)))

  (extend MCUSource c/Outputter c/common-outputter-implementation)
  (extend MCUSource c/ITransactional c/common-transactional-implementation)

  (def ^:private mcu-source-impl-fmt {:state   v/atomic
                                      :name    v/non-empty-str
                                      :conf    v/atomic
                                      :threads v/numeric})

  (defmethod c/validate-step-setup "clojure-data-grinder-core.core/map->MCUSource" [_ setup]
    (let [[_ err] (v/validate mcu-source-impl-fmt setup)]
      (when err
        (throw (ex-info "Problem validating MCUSource" {:message (v/humanize-error err)})))))

  (defmethod c/bootstrap-step "clojure-data-grinder-core.core/map->MCUSource"
    [impl {name                                                                       :name
           {{out :out} :channels :as conf}                                            :conf
           {^Symbol clean-up-fn :clean-up-fn fail-fast? :fail-fast? retries :retries} :tx
           ^Symbol v-fn                                                               :v-fn
           ^Symbol x-fn                                                               :x-fn
           pf                                                                         :poll-frequency-ms
           threads                                                                    :threads} channels]
    (let [conf (c/conf-out-channels->conf-real-out-channels conf out channels)
          [v-fn x-fn clean-up-fn] (c/resolve-all-functions v-fn x-fn clean-up-fn)
          v-fn (or v-fn (fn [_] nil))
          conf (assoc conf :tx {:clean-up-fn clean-up-fn
                                :fail-fast?  (or fail-fast? false)
                                :retries     retries})
          setup {:v-fn              v-fn
                 :x-fn              x-fn
                 :poll-frequency-ms pf
                 :name              name
                 :conf              conf
                 :threads           threads
                 :state             (atom {:successful-source-calls   0
                                           :unsuccessful-source-calls 0
                                           :source-calls              0
                                           :stopped                   false})}]
      (c/step-config->instance name impl setup)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (defmulti ^:private get-command-type (fn [type ^ByteBuffer _] type))

  (defmethod ^:private get-command-type :ice [_ ^ByteBuffer value]
    (ICEBased. value))

  (defmethod ^:private get-command-type :ev [_ ^ByteBuffer value]
    (EVBased. value))

  (defrecord MCUOutGrinder [state name conf v-fn in out]
    c/Grinder
    (grind [this value]
      (>!! out (get-command-type (:type @conf) value)))
    c/Step
    (init [this]
      (log/debug "Initialized Grinder " name)
      (loop []
        (when-not (:stopped @state)
          (.grind this (<!! in))
          (recur))))
    (validate [this]
      (if-let [result (v-fn @conf)]
        (throw (ex-info "Problem validating Grinder conf!" result))
        (log/debug "Grinder " name " validated")))
    (getState [this] @state)
    (stop [this] (swap! state assoc :stopped true)))

  (defn serialize
    "Serializes value, returns a byte array"
    [v]
    (let [buff (ByteArrayOutputStream. 1024)]
      (with-open [dos (ObjectOutputStream. buff)]
        (.writeObject dos v))
      (.toByteArray buff)))

  (defrecord DashboardSink [state name conf v-fn in]
    c/Sink
    (sink [_ value]
      (send-packet (:socket @conf) (serialize value) (:destination-host @conf) (:destination-port @conf)))
    c/Step
    (init [this]
      (log/debug "Initialized Sink " name)
      (let [socket (make-socket (:port @conf))]
        (swap! conf assoc :socket socket)
        (loop []
          (if-not (:stopped @state)
            (when-let [value (<!! in)]
              (.sink this value)
              (recur))
            (.close socket)))))
    (validate [_]
      (if-let [result (v-fn @conf)]
        (throw (ex-info "Problem validating Sink conf!" result))
        (log/debug "Sink " name " validated")))
    (getState [_] @state)
    (stop [_]
      (swap! state assoc :stopped true)))

  ;(defrecord StateGrinder [state name conf v-fn in out]
  ;  Grinder
  ;  (grind [_ value]
  ;    (let [^CarState car-state (:car-state @conf)
  ;          value (if (.setIgnition car-state value)
  ;                  (->> value
  ;                       (.setFuelLevel car-state)
  ;                       (.checkSpeedIncreaseDistance car-state))
  ;                  value)]
  ;      (>!! out value)))
  ;  Step
  ;  (init [this]
  ;    (log/debug "Initialized Grinder " name)
  ;    (swap! conf assoc :car-state (c-state/get-car-state (:type @conf)))
  ;    (go-loop []
  ;      (if-not (:stopped @state)
  ;        (when-let [value (<!! in)]
  ;          (.grind this value)
  ;          (recur)))))
  ;  (validate [_]
  ;    (if-let [result (v-fn @conf)]
  ;      (throw (ex-info "Problem validating Grinder conf!" result))
  ;      (log/debug "Grinder " name " validated")))
  ;  (getState [_] @state)
  ;  (stop [_]
  ;    (swap! state assoc :stopped true)))
