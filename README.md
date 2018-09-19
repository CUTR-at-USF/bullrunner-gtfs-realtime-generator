bullrunner-gtfs-realtime-generator [![Build Status](https://travis-ci.org/CUTR-at-USF/bullrunner-gtfs-realtime-generator.svg?branch=master)](https://travis-ci.org/CUTR-at-USF/bullrunner-gtfs-realtime-generator)
==================================

Desktop application that retrieves AVL data from the USF Bull Runner's AVL system and produces Trip Updates and Vehicle Positions files in GTFS-realtime format.

Protobuf URL endpoints for the feed: 

* tripUpdatesUrl = `http://localhost:8088/trip-updates` (Currently unsupported, feed is empty)
* vehiclePositionsUrl = `http://localhost:8088/vehicle-positions`

To see a plain text representation, add `?debug` to the end of the URL:

* tripUpdatesUrl = `http://localhost:8088/trip-updates?debug` (Currently unsupported, feed is empty)
* vehiclePositionsUrl = `http://localhost:8088/vehicle-positions?debug`

To run: 
1. Create key.txt file in the main directory and save the API key in the file (API key can be requested from [Syncromatics](http://gmvsyncromatics.com/contact/))
2. Extract the bullrunner-gtfs.zip file in the same folder. The extracted folder should be name "bullrunner-gtfs"
3. Build `mvn package`
4. Run `java -jar target/bullrunner-gtfs-realtime-generator-1.0.0-SNAPSHOT.jar --vehiclePositionsUrl=http://localhost:8088/vehicle-positions`

