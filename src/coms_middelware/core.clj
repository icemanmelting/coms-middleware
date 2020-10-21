(ns coms-middelware.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop >!! chan <!! <!]]
            [clojure-data-grinder-core.core :refer [Source Grinder Sink Step]])
  (:import (java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress SocketAddress)
           (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ev EVBased)
           (pt.iceman.middleware.cars.ice ICEBased)
           (java.io ByteArrayOutputStream ObjectOutputStream ObjectInputStream ByteArrayInputStream)))

(defn make-socket
  ([] (new DatagramSocket))
  ([port] (new DatagramSocket port)))

(defn receive-packet
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload message as a string."
  [^DatagramSocket socket buffer-size]
  (let [buffer (byte-array buffer-size)
        packet (DatagramPacket. buffer buffer-size)]
    (.receive socket packet)
    (.getData packet)))

(defn send-packet
  "Send a short textual message over a DatagramSocket to the specified
  host and port. If the string is over 512 bytes long, it will be
  truncated."
  [^DatagramSocket socket payload host port]
  (let [length (alength payload)
        address (InetSocketAddress. ^String host ^long port)
        packet (DatagramPacket. payload length address)]
    (.send socket packet)))

(defrecord MCUSource [state name conf v-fn out]
  Source
  (output [this value]
    (log/debug "Adding value " value " to source channel " name)
    (>!! out value))
  Step
  (init [this]
    (let [socket (make-socket (:port @conf))]
      (swap! conf assoc :socket socket)
      (log/debug "Initialized Source " name)
      (go-loop []
        (if-not (:stopped @state)
          (do (.output this (-> socket
                          (receive-packet (:buffer-size @conf))
                          (byte-array)
                          (ByteBuffer/wrap)))
              (recur))
          (.close socket)))))
  (validate [this]
    (if-let [result (v-fn @conf)]
      (throw (ex-info "Problem validating Source conf!" result))
      (log/debug "Source " name " validated")))
  (getState [this] @state)
  (stop [this]
    (log/debug "Stopping MCUSource " name)
    (swap! state assoc :stopped true)))

(defmulti ^:private get-command-type (fn [type ^ByteBuffer _] type))

(defmethod ^:private get-command-type :ice [_ ^ByteBuffer value]
  (ICEBased. value))

(defmethod ^:private get-command-type :ev [_ ^ByteBuffer value]
  (EVBased. value))

(defrecord MCUOutGrinder [state name conf v-fn in out]
  Grinder
  (grind [this value]
    (>!! out (get-command-type (:type @conf) value)))
  Step
  (init [this]
    (log/debug "Initialized Grinder " name)
    (go-loop []
      (when-not (:stopped @state)
        (.grind this (<!! in))
        (recur))))
  (validate [this]
    (if-let [result (v-fn @conf)]
      (throw (ex-info "Problem validating Source conf!" result))
      (log/debug "Source " name " validated")))
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
  Sink
  (sink [this value]
    (send-packet (:socket @conf) (serialize value) (:destination-host @conf) (:destination-port @conf)))
  Step
  (init [this]
    (log/debug "Initialized Sink " name)
    (let [socket (make-socket (:port @conf))]
      (swap! conf assoc :socket socket)
      (go-loop []
        (if-not (:stopped @state)
          (when-let [value (<!! in)]
            (.sink this value)
            (recur))
          (.close socket)))))
  (validate [this]
    (if-let [result (v-fn @conf)]
      (throw (ex-info "Problem validating Sink conf!" result))
      (log/debug "Source " name " validated")))
  (getState [this] @state)
  (stop [this]
    (swap! state assoc :stopped true)))
