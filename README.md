bullrunner-gtfs-realtime-generator
==================================

Desktop application that retrieves AVL data from the USF Bull Runner's AVL system and produces Trip Updates and Vehicle Positions files in GTFS-realtime format.

Protobuf URL endpoints for the feed: 

* tripUpdatesUrl = `http://localhost:8088/trip-updates`
* vehiclePositionsUrl = `http://localhost:8088/vehicle-positions`

To see a plain text representation, add `?debug` to the end of the URL:

* tripUpdatesUrl = `http://localhost:8088/trip-updates?debug`
* vehiclePositionsUrl = `http://localhost:8088/vehicle-positions?debug`

To run: 

`java -jar cutr-gtfs-realtime-bullrunner-0.9.0-SNAPSHOT.jar  --tripUpdatesUrl=http://localhost:8080/trip-updates   --vehiclePositionsUrl=http://localhost:8080/vehicle-positions`

...from TARGET directory

The Bull Runner GTFS can be found [here](https://github.com/CUTR-at-USF/bullrunner-gtfs-realtime-generator/blob/master/bullrunner-gtfs.zip) and should be extracted into `../myGTFS/`, as the GTFS-rt feed requires it to run.

