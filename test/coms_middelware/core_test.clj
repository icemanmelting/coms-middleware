(ns coms-middelware.core-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan <!!]]
            [coms-middelware.core :refer :all])
  (:import (java.net DatagramSocket InetSocketAddress DatagramPacket)
           (coms_middelware.core MCUSource)))

(defn- make-socket
  ([] (new DatagramSocket))
  ([port] (new DatagramSocket port)))

(defn- send-packet
  "Send a short textual message over a DatagramSocket to the specified
  host and port. If the string is over 512 bytes long, it will be
  truncated."
  [^DatagramSocket socket msg ^String host port]
  (let [payload (.getBytes msg)
        length (min (alength payload) 14)
        address (InetSocketAddress. host port)
        packet (DatagramPacket. payload length address)]
    (.send socket packet)))

(deftest mcu-source-test
  (let [state (atom {})
        name "test-source"
        conf (atom {:port 9999
                    :buffer-size 14})
        out (chan)
        ^MCUSource mcu-source (->MCUSource state name conf nil out)]

    (with-open [socket (make-socket)]
      (.init mcu-source)
      (send-packet socket "this is a test" "localhost" 9999)

      (let [value (<!! out)]
        (is (= "this is a test" (String. (.array value))))))
    (.stop mcu-source)))
