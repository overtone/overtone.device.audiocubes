# AudioCubes

Library to generate and parse AudioCube specific OSC messages. Simplifies the process of interfacing a set of Percussa Audiocubes to Overtone via the OSC-bridge from Percussa.

## Usage

### AudioCube / OSC Bridge configuration

Requires the audiocube OSC bridge running (available for Windows or OSX - not tested with Wine but should work).

	> acosc.exe 127.0.0.1 7000 8000
	
Runs the bridge sending messages to localhost port 7000 and receiving them on 8000
 
### Creating handlers

See `test/example.clj`. Create an OSC handler with `mk-cube-handle` passing a map of event type to handler function, available event types to use as keys in the map are as follows:

* `:topology` Called when the cube topology is changed, and passed a list of 
tuples describing the new per-cube and per-face adjacencies. Each entry in the 
list is a map e.g. `{:cube1 0 :face1 2 :cube2 1 :face2 3}`. Due to a strange
bug in the OSC bridge this can emit topologies which are physically impossible 
so any consuming code should apply appropriate filtering or use the `:topology2d`
event which doesn't suffer from this problem.
* `:topology2d` A more sophisticated topology based handler, called whenever
the topology is changed and passed a list of sub-topologies, each of which details
the relative position and rotation of the cubes in that sub-topology. The handler
is passed a structure of the form `({:width :height :cubes ({:cube :x :y :rotation}...)}...)`
* `:attached` Called when a cube is attached to the network, and passed the integer
ID of the newly attached cube (0-15). This only applies to cubes connected over USB.
* `:detached` Called when a previously directly attached (USB connected) cube is
detached from the host, and passed the integer ID of the cube (0-15).
* `:added` Called when a cube is added to the network via the infra-red mesh rather
than being directly attached over USB. May also be called when directly connected!
* `:sensor-updated` Called when a directly attached cube emits a sensor change event.
Passed the cube ID (0-15), face ID (0-3) and sensor value (float with range 0 to 1.0).
This function will be called rapidly, each directly attached cube has four sensors and
will emit data as fast as it can. Cubes attached through the infra-red mesh will not emit
sensor data due to bandwidth limitations. 
* `:colour-updated` Called when a cube's internal LED has been set to a new colour, passed
the cube ID (0-15) and standard RGB values (0-255)
* `:sensor-mode-updated` Called when a cube has been put into or out of 'sensor only' mode,
a function which disables topology detection and allows for more rapid sensor updates.
Called with the cube ID (0-15) and whether the cube is now in sensor only mode (boolean)

The return from `mk-cube-handler` can then be attached to an OSC server instance, for example with the OSC bridge configured as above you'd use

	(osc-handle 
    	(osc-server 7000) ; create a new OSC server listening on port 7000
    	"/audiocubes" ; handle audiocube events only
    	;; Create a new cube message handler to respond to cube-related events
    	(mk-cube-handler .... 

The `:topology` and `:topology2d` events are debounced, that is handlers you create will only be called at most once every (currently) 200ms. This is to attempt to work around a behaviour in the OSC Bridge code which tends to emit bogus empty topology events immediately after the real ones.

### Setting cube colour

Any connected cube, whether directly connected or through the IR mesh network, can have the colour of its internal LED set with `(set-colour cube red green blue)`, where cube is the ID of the cube to set (0-15) and the other values the RGB values to set (0-255)

## Project Info

Import as `[overtone/device/audiocubes "0.1"]`. Depends on `[overtone/osc-clj "0.7.1"]`, previous versions have a necessary OSC message send method marked as private.

### Author

Tom Oinn

### Contributors

Sam Aaron

### License

Distributed under the Eclipse Public License, the same as Clojure.
