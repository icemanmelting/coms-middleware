(ns coms-middleware.readers.ignition-reader
  (:require [coms-middleware.readers.common :refer [create-log]]))

(defn ->ignition-on [basecommand _]
  (.setIgnition basecommand true)
  (try
    (create-log "INFO" "Ignition turned on")
    (.exec (Runtime/getRuntime) "/etc/init.d/turnonscreen.sh")
    (catch Exception _
      (create-log "ERROR" "Could not read script to turn on screen")))
  basecommand)

(defn ->ignition-off [basecommand _]
  (.setIgnition basecommand false)
  (future (try
            (Thread/sleep 5000)
            (.exec (Runtime/getRuntime) "/etc/init.d/shutdownScreen.sh")
            (catch Exception _
              (create-log "INFO" "Could not read script to shutdown screen"))))
  (create-log "INFO" "Ignition turned off")
  basecommand)
