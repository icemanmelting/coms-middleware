(ns coms-middelware.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop >!! chan <!! <!]]
            [clojure-data-grinder-core.core :refer [Source Grinder Sink Step]])
  (:import (java.net DatagramPacket DatagramSocket)
           (java.nio ByteBuffer)
           (pt.iceman.middleware.cars.ev EVBased)
           (pt.iceman.middleware.cars.ice ICEBased)))

(defn- make-socket
  ([] (new DatagramSocket))
  ([port] (new DatagramSocket port)))

(defn- receive-packet
  "Block until a UDP message is received on the given DatagramSocket, and
  return the payload message as a string."
  [^DatagramSocket socket buffer-size]
  (let [buffer (byte-array buffer-size)
        packet (DatagramPacket. buffer buffer-size)]
    (.receive socket packet)
    (.getData packet)))

(defrecord MCUSource [state name conf v-fn out]
  Source
  (output [this value]
    (log/debug "Adding value " value " to source channel " name)
    (>!! out value))
  Step
  (init [this]
    (log/debug "Initialized Source " name)
    (let [socket (make-socket (:port @conf))]
      (swap! conf assoc :socket socket)
      (go-loop []
        (.output this (-> socket
                          (receive-packet (:buffer-size @conf))
                          (byte-array)
                          (ByteBuffer/wrap)))
        (if-not (:stopped @state)
          (recur)
          (.close socket)))))
  (validate [this]
    (if-let [result (v-fn @conf)]
      (throw (ex-info "Problem validating Source conf!" result))
      (log/debug "Source " name " validated")))
  (getState [this] @state)
  (stop [this]
    (log/debug "Stopping MCUSource " name)
    (swap! state assoc :stopped true)))

(defmulti get-command-type (fn [type ^ByteBuffer _] type))

(defmethod get-command-type :ice [_ ^ByteBuffer value]
  (ICEBased. value))

(defmethod get-command-type :ev [_ ^ByteBuffer value]
  (EVBased. value))

(defrecord MCUOutGrinder [state name conf v-fn in out]
  Grinder
  (grind [this value]
    (>!! out (get-command-type (:type @conf) value)))
  Step
  (init [this]
    (log/debug "Initialized Grinder " name)
    (go-loop []
      (.grind this (<!! in))
      (recur)))
  (validate [this]
    (if-let [result (v-fn @conf)]
      (throw (ex-info "Problem validating Source conf!" result))
      (log/debug "Source " name " validated")))
  (getState [this] @state)
  (stop [this] (swap! state assoc :stopped true)))