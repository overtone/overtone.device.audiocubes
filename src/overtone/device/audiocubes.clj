(ns overtone.device.audiocubes
  (:use [overtone.osc]))

;;;; Provides functions to handle the AudioCube-OSC bridge.

;;; Topology is presented as a single OSC message with the following form:
;;; (count:int [cube1:int face1:int cube2:int face2:int]...) where <cube> is
;;; an integer 0-15 and <face> an integer 0-3. There are <count> entries in
;;; the topology.

(defn- parse-topology 
  "Parses the topology message into a seq of cube and face relationships of the form
   ({:cube1 :face1 :cube2 :face2}...), ensuring that cube1<cube2 and sorting the list
   before returning it. Note that the current OSC host implementation can return
   topologies which aren't physically possible, the parse-topology2d won't do this"
  [[topo-count & topo]]
  (loop [topo-list ()
         values topo]
    (if (seq values)
      (let [[cube1 face1 cube2 face2 & tail] values]
        (recur (cons (if (< cube1 cube2) ;ensure that cube1 < cube2
                       {:cube1 cube1 :face1 face1 :cube2 cube2 :face2 face2}
                       {:cube1 cube2 :face1 face2 :cube2 cube1 :face2 face1}
                       ) topo-list)
               tail))       
      ;; Sort the list, ordering by cube1,face1,cube2,face2 in decreasing order of
      ;; significance. Used as the ordering in the OSC message seems rather random.
      (sort-by (fn [{cube1 :cube1 face1 :face1 cube2 :cube2 face2 :face2}] 
                 (+ cube1 (* 16 face1)(* 64 cube2 )(* 1024 face2)))
               topo-list))))


;;; As an alternative to the raw topology the OSC bridge also sends a topology2D
;;; message containing a summary of the implied grid locations and orientations of
;;; the AudioCubes. This is conveyed as an OSC message of the form:
;;; (sub-topology-count [cube-count width height [cube x y rotation]... ]...) where
;;; all properties are integers. The x and y coordinates per cube may be negative,
;;; and exactly one cube per sub-topology will be located at 0,0 - typically this
;;; seems to be a cube directly connected via USB but there's no formal guarantee
;;; of this. Width and height are the minimum number of slots in each direction 
;;; required to contain the topology, so for a single cube these are both 1. As
;;; with the raw topology cube is an integer 0-15 and rotation an integer 0-3.

(defn- parse-topology2d 
  "Parses the topo2d message into a seq of topology structures of the form
   ({:width :height :cubes ({:cube :x : y :rotation}...)}...)
   Multiple topologies will be included if there are disjoint groups of cubes
   visible to the OSC host. X and Y coordinates for cubes can be negative, width
   and height for the sub-topologies are the number of cubes, so always at least 1."
  [[subtopology-count & data]]
  (loop [subtopology-list ()
         subtopology-data data]
    (if (seq subtopology-data)
      (let [[entry-count width height & entries] subtopology-data]
        (recur (cons {:width width :height height :cubes 
                      ;; Inner function, iterate over 4*entry-count of entries
                      (#(loop [cube-list () 
                               cube-data %]
                          (if (seq cube-data)
                            (let [[cube x y rotation & next-cube-data] cube-data]
                              (recur (cons {:cube cube :x x :y y :rotation rotation} cube-list)
                                     next-cube-data))
                            cube-list)) ;return the cube list from the inner function
                        (take (* 4 entry-count) entries))}
                     subtopology-list) ;cons the sub-topology onto the list
               (nthrest entries (* 4 entry-count)))) ;recurse on the remainder of the data
      subtopology-list)))                  


;;; The OSC bridge can produce spurious messages, in particular it tends to send empty topology
;;; messages immediately after the 'real' ones. The debounce is used to wrap the handler functions
;;; for the topology messages such that they return nil if called immediately after a preceeding
;;; successful invocation. This cleans up the message stream, it doesn't entirely eliminate odd
;;; messages but it does go quite a long way towards making the output usable.

(defn debounce 
  "Wrap a function such that calls within 'timeout' milliseconds of a previous call
   are ignored and return nil, other calls call the wrapped function and return its result"
  [timeout f]
  (let [last-invocation-atom (atom 0)]
    (fn [& args]
      (let [current-time (System/currentTimeMillis)
            last-invocation @last-invocation-atom]
        (if (< (- current-time last-invocation) timeout)
          nil
          (do
            (reset! last-invocation-atom current-time)
            (apply f args)))))))


;;; Uses the osc-send-message and an explicit type tag string to send RGB values to a numbered
;;; cube. Passed the OSC client which should be used to send messages.

(defn set-colour
  "Set the colour of a connected cube (works over USB and IR mesh)"
  [client cube red green blue]
  (osc-send-msg 
    client 
    (with-meta 
      {:path "/audiocubes" 
                :type-tag "siiii"
                :args ["change_color" cube red green blue]}
      {:type :osc-msg})))


;;; Function which takes a map of handler functions, optional for each type of message,
;;; and which returns a function to be bound to an OSC server to respond and route events
;;; to the handlers. Topology messages are debounced - any message within 200ms of a previous
;;; message will be ignored and the handler not called. This prevents otherwise spurious
;;; empty topology messages, the precise debounce timer may need tuning.

(defn mk-cube-handler
  "Constructs a handler function which can be passed into the osc-listen
   function to respond to cube related OSC messages"
  [&{:keys [topology topology2d attached detached added sensor-updated colour-updated sensor-mode-updated]}]
  (let [debounced-parse-topology (debounce 200 parse-topology)
        debounced-parse-topology2d (debounce 200 parse-topology2d)
        create-initial-sensor-data #(apply hash-map 
                                           (flatten (for 
                                                      [face (range 0 4)]
                                                      [face (atom {:min 1.0 :max 0.0})])))
        sensor-values (apply hash-map 
                             (apply concat 
                                    (for 
                                      [cube (range 0 16)] 
                                      ;; Storing the per-face sub-structure as an atom to simplify reset
                                      [cube (atom (create-initial-sensor-data))])))]
    (fn [{[message-name & message] :args}]
      (case message-name
        "topology_update"  ; {"topology_update",count,cubeA,faceA,cubeB,faceB....}
        (if-let [handler topology]
          (if-let [topology (debounced-parse-topology message)]
            (handler topology)))
        "topology_2D" ; Summary of implied cube positions and rotation from topology information
        (if-let [handler topology2d]
          (if-let [topology (debounced-parse-topology2d message)]
            (handler topology)))
        (let [[cube] message] ; other message, extract cube number
          (case message-name
            "attached" 
            (if-let [handler attached] 
              (do 
                ;; Reset calibration data for this cube
                (reset! (sensor-values cube) (create-initial-sensor-data))
                (handler cube)))
            "detached" 
            (if-let [handler detached] (handler cube)) 
            "added" 
            (if-let [handler added] 
              (do 
                ;; Reset calibration data for this cube
                (reset! (sensor-values cube) (create-initial-sensor-data))
                (handler cube)))
            "sensor_update" 
            (if-let [handler sensor-updated] 
              ;; Apply auto-calibration
              (let [[cube face value] message
                    ;; historic-values is (atom {:min :max :last}) for the appropriate cube / face
                    historic-values (@(sensor-values cube) face)
                    ;; clip values to between 0.05 and 0.95, reduces noise at what would otherwise
                    ;; be the zero signal point and allows for cubes to return 'nothing' as a sensor
                    ;; value in a way they wouldn't otherwise do (presumably due to sensor noise)
                    clipped-value (min (max value 0.05) 0.95)
                    min-value (min clipped-value (:min @historic-values))
                    max-value (max clipped-value (:max @historic-values))
                    last-returned-value (if (contains? @historic-values :last) (:last @historic-values) -1)
                    value-range (if (> max-value min-value) (- max-value min-value) 1.0)
                    calibrated-value  (/ (- clipped-value min-value) value-range)]
                (do 
                  (reset! historic-values {:min min-value :max max-value :last calibrated-value}) 
                  (if (== last-returned-value calibrated-value)
                    ;; Don't return streams of identical values
                    nil
                    (handler cube face calibrated-value)))))
            "color-update" 
            (if-let [handler colour-updated] 
              (let [[cube red green blue] message]
                (handler cube red green blue))) 
            "sensoring-only-mode" 
            (if-let [handler sensor-mode-updated] 
              (let [[cube sensor-only-mode] message]
                (handler cube sensor-only-mode)))
            nil))))))


;;; Converts the otherwise unreadable result from parse-topology into something human readable

(defn stringify-topology-list 
  "Create human readable string from the return from 'parse-topology'"
  [topo-list]
  (let [faces `(:north :east :south :west)] ; labels for cube faces 0-3
    (str "Topology : " ; Print a summary of the topology as parsed.
         (if (seq topo-list) 
           (apply str (map #(str (:cube1 %)(nth faces (:face1 %))"=>"
                                 (:cube2 %)(nth faces (:face2 %))" ") topo-list)) 
           "empty"))))

