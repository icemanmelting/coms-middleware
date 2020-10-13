(ns coms-middelware.core
  (:require [clojure.tools.logging :as log]
            [clojure.core.async :refer [go-loop >!! chan <!!]]
            [clojure-data-grinder-core.core :refer [Source Step]])
  (:import (java.net DatagramPacket DatagramSocket)
           (java.nio ByteBuffer)))

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
      (go-loop []
        (.output this (-> (receive-packet socket (:buffer-size @conf))
                         (byte-array)
                         (ByteBuffer/wrap)))
        (recur))))
  (validate [this]
    (if-let [result (v-fn conf)]
      (throw (ex-info "Problem validating Source conf!" result))
      (log/debug "Source " name " validated")))
  (getState [this] @state))
