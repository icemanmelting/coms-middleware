(ns coms-middleware.test-tool
  (:require [clojure.core.async :as async])
  (:import (pt.iceman.middleware.cars.ice ICEBased)
           (java.nio ByteBuffer)
           (java.io ObjectOutputStream ByteArrayOutputStream)
           (java.net InetSocketAddress DatagramPacket DatagramSocket)))

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

(def socket (when-not *compile-files* (make-socket 4445)))

(defn serialize
  "Serializes value, returns a byte array"
  [v]
  (let [buff (ByteArrayOutputStream. 1024)]
    (with-open [dos (ObjectOutputStream. buff)]
      (.writeObject dos v))
    (.toByteArray buff)))

(defn- short-to-2-bytes [^Short s]
  (let [b1 (bit-and s 0xFF)
        b2 (bit-and (bit-shift-right s 8) 0xFF)]
    [b1 b2]))

(def speed (short-to-2-bytes 50))

(def arr (byte-array [0x00
                      0x00
                      0x00
                      0xFF
                      0xFF
                      0x00
                      0xFF
                      (first (short-to-2-bytes 200))
                      (second (short-to-2-bytes 200))
                      0x00
                      0xFF
                      (first (short-to-2-bytes 3000))
                      (second (short-to-2-bytes 3000))
                      (first (short-to-2-bytes 4))
                      (second (short-to-2-bytes 4))
                      (first (short-to-2-bytes 90))
                      (second (short-to-2-bytes 90))]))

(def command
  (doto (ICEBased. (ByteBuffer/wrap arr))
    (.setTotalDistance 230000)))

(def serialized (serialize command))

(def ^:const abs-anomaly-off 128)
(def ^:const abs-anomaly-on 143)
(def ^:const battery-off 32)
(def ^:const battery-on 47)
(def ^:const brakes-oil-off 64)
(def ^:const brakes-oil-on 79)
(def ^:const high-beam-off 144)
(def ^:const high-beam-on 159)
(def ^:const oil-pressure-off 16)
(def ^:const oil-pressure-on 31)
(def ^:const parking-brake-off 48)
(def ^:const parking-brake-on 63)
(def ^:const turning-signs-off 80)
(def ^:const turning-signs-on 95)
(def ^:const spark-plugs-off 112)
(def ^:const spark-plugs-on 127)
(def ^:const reset-trip-km 1)
(def ^:const rpm-pulse 180)
(def ^:const speed-pulse 176)
(def ^:const temperature-value 192)
(def ^:const fuel-value 224)
(def ^:const ignition-on 171)
(def ^:const ignition-off 170)
(def ^:const turn-off 168)

(def lights [128 143 32 47 64 79 144 159 16 31 48 63 80 95 112 127])

(def analog-values [180 176 192 224])

(defn simulate []
  (async/thread
    (loop []
      (let [light (rand-int (- (count lights) 1))
            analog-value (rand-int (- (count analog-values) 1))]
        (send-packet socket (byte-array 1 (unchecked-byte (nth lights light))) "127.0.0.1" 9887)
        (send-packet socket (byte-array 3 [(unchecked-byte (nth analog-values analog-value)) (unchecked-byte 91) (unchecked-byte 2)]) "127.0.0.1" 9887))
      ;(Thread/sleep 1)
      (recur))))