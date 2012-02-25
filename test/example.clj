(ns example
  (:use [overtone.device.audiocubes] 
        [overtone.osc]))

;;; Example usage, creates an OSC server and associates it with a cube handler, printing any
;;; received values other than sensor updates (the sensors update so fast that anything else
;;; would get lost in the chatter)

(let [client (osc-client "localhost" 8000)] 
  (osc-handle 
    (osc-server 7000) ; create a new OSC server listening on port 7000
    "/audiocubes"
    ;; Create a new cube message handler to respond to cube-related events
    (mk-cube-handler {:topology ; Topology change
                      (fn [topology-list] (println (stringify-topology-list topology-list)))
                      :topology2d ; Topology 2D structure produced 
                      (fn [topo2d] (println topo2d))
                      :attached ; Cube directly attached on USB
                      (fn [cube] (do 
                                   (println (str "Cube " cube " attached"))
                                   (set-colour client cube 255 200 0)))
                      :detached ; Cube detached from USB
                      (fn [cube] (println (str "Cube " cube " detached")))
                      :added ; Cube added to mesh network
                      (fn [cube] (do 
                                   (println (str "Cube " cube " added to network"))
                                   (set-colour client cube 50 255 30)))
                      :sensor-updated ; Sensor value updated
                      (fn [cube face sensor-value] 
                        (println cube face sensor-value)) ; Print sensor data
                      :colour-updated ; Colour set for cube
                      (fn [cube red green blue] 
                        (println (str "Cube " cube " set to r=" red " g=" green " b=" blue)))
                      :sensor-mode-updated ; Cube set into sensor-only (no topo) mode?
                      (fn [cube sensor-only-mode] 
                        (println (str "Cube " cube " set to sensor-only mode? " sensor-only-mode)))})))