bullrunner-gtfs-realtime-generator
==================================

Desktop application that retrieves AVL data from the USF Bull Runner's AVL system and produces Trip Updates and Vehicle Positions files in GTFS-realtime format.

Run configuration: 

* tripUpdatesUrl = `http://localhost:8088/trip-updates`
* vehiclePositionsUrl = `http://localhost:8088/vehicle-positions`

Run: java -jar cutr-gtfs-realtime-bullrunner-0.9.0-SNAPSHOT.jar  --tripUpdatesUrl=http://localhost:8080/trip-updates   --vehiclePositionsUrl=http://localhost:8080/vehicle-positions
from TARGET directory

the bullrunner GTFS should be extracted into ../myGTFS/

