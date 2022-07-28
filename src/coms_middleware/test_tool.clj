(ns coms-middleware.test-tool
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

(defn- short-to-2-bytes[^Short s]
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
