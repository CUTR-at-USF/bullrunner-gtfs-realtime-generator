/**
 * Copyright (C) 2012 Google, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.usf.cutr.gtfs_realtime.bullrunner;


import com.google.inject.Inject;
import com.google.transit.realtime.GtfsRealtime.*;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.OccupancyStatus;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFullUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.TripUpdates;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import java.io.*;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This class produces GTFS-realtime trip updates and vehicle positions by
 * periodically polling the custom SEPTA vehicle data API and converting the
 * resulting vehicle data into the GTFS-realtime format.
 *
 * Since this class implements {@link GtfsRealtimeProvider}, it will
 * automatically be queried by the {@link GtfsRealtimeExporterModule} to export
 * the GTFS-realtime feeds to file or to host them using a simple web-server, as
 * configured by the client.
 *
 * @author bdferris
 *
 */
@Singleton
public class GtfsRealtimeProviderImpl {

    private static final Logger _log = LoggerFactory
            .getLogger(GtfsRealtimeProviderImpl.class);
    private static final int _Conn_Timeout_MS = (int) TimeUnit.SECONDS.toMillis(10); // connection time out 10s
    private ScheduledExecutorService _executor;
    private GtfsRealtimeExporterCutr _gtfsRealtimeProvider;
    private URL _url;
    private String _api_key;

    /**
     * How often vehicle data will be downloaded, in seconds.
     */
    private int _refreshInterval = 30;
    private BullRunnerConfigExtract _providerConfig;

    @Inject
    public void setGtfsRealtimeProvider(
            GtfsRealtimeExporterCutr gtfsRealtimeProvider) {
        _gtfsRealtimeProvider = gtfsRealtimeProvider;
    }


    private GtfsRealtimeSink _vehiclePositionsSink;
    private GtfsRealtimeSink _tripUpdatesSink;

    private static float getDirVal(String direction) {
        switch (direction) {
            case "N":
                return 0;
            case "NE":
                return 45;
            case "E":
                return 90;
            case "SE":
                return 135;
            case "S":
                return 180;
            case "SW":
                return 225;
            case "W":
                return 270;
            case "NW":
                return 315;
            default: {
                System.out.println("this dierection is not available : " + direction);
                _log.error("this dierection is not supported : " + direction);
                return 0;
            }

        }
    }

    /**
     * @param url
     *            the URL for the SEPTA vehicle data API.
     */
    public void setUrl(URL url) {
        _url = url;
        // System.out.println(_url.toString());
    }

    /**
     * Find the key file in the current directory and then in the parent directory
     * If not found, throw an Error and exit
     * @throws IOException
     */
    public void setKey() throws IOException { // try to find key file and set API key
        String keyPath;
        if (Files.exists(Paths.get("./key.txt"))){
            keyPath = "./key.txt";
        } else if (Files.exists(Paths.get("../key.txt"))){
            keyPath = "../key.txt";
        } else {
            throw new Error("KEY FILE NOT FOUND! Create key.txt file in the main directory and save the API key in the file");
        }
        BufferedReader tripsBuffer = new BufferedReader(new FileReader(keyPath));
        String key = tripsBuffer.readLine();
        _api_key = key;
    }

    /**
     * @param refreshInterval
     *            how often vehicle data will be downloaded, in seconds.
     */
    public void setRefreshInterval(int refreshInterval) {
        _refreshInterval = refreshInterval;
    }

    /**
     * The start method automatically starts up a recurring task that
     * periodically downloads the latest vehicle data from the SEPTA vehicle
     * stream and processes them.
     */
    @Inject
    public void setProvider(BullRunnerConfigExtract providerConfig) {
        _providerConfig = providerConfig;
    }

    @Inject
    public void setVehiclePositionsSink(@VehiclePositions GtfsRealtimeSink vehiclePositionsSink) {
        _vehiclePositionsSink = vehiclePositionsSink;
    }

    @Inject
    public void setTripUpdatesSink(@TripUpdates GtfsRealtimeSink tripUpdatesSink) {
        _tripUpdatesSink = tripUpdatesSink;
    }

    @PostConstruct
    public void start() {

        try {
            //_providerConfig .setUrl(new URL( "http://usfbullrunner.com/region/0/routes"));
            _providerConfig.findPaths(); // try to find path of GTFS directory
            _providerConfig.generatesRouteMap(new URL("https://usfbullrunner.com/region/0/routes"));
            _providerConfig.generateTripMap();
            _providerConfig.generateServiceMap();
            _providerConfig.extractSeqId();
            _providerConfig.extractStartTime();
            _providerConfig.generateExternalIDMap();

        } catch (Exception ex) {
            _log.warn("Error in retriving confirmation data!", ex);
        }
        _log.info("starting GTFS-realtime service");
        _executor = Executors.newSingleThreadScheduledExecutor();
        _executor.scheduleAtFixedRate(new VehiclesRefreshTask(), 0,
                _refreshInterval, TimeUnit.SECONDS);
    }

    /****
     * Private Methods - Here is where the real work happens
     ****/

    /**
     * The stop method cancels the recurring vehicle data downloader task.
     */
    @PreDestroy
    public void stop() {
        _log.info("stopping GTFS-realtime service");
        _executor.shutdownNow();
    }

    /**
     * This method downloads the latest vehicle data, processes each vehicle in
     * turn, and create a GTFS-realtime feed of trip updates and vehicle
     * positions as a result.
     */
    private void refreshTripVehicle() throws IOException, JSONException {

        // ---- START UPDATING VEHICLE POSITIONS -----------------
        // construct VehiclePosition feed
        GtfsRealtimeFullUpdate vehiclePositions = new GtfsRealtimeFullUpdate();
        int vehicleFeedID = 0;
        // Loop through the external route id map to get vehicle locations for each route id
        Iterator IT = _providerConfig.ExternalIDMap.entrySet().iterator();
        while (IT.hasNext()) {
            Map.Entry pair = (Map.Entry) IT.next();
            String route_id = pair.getKey().toString();
            String external_id = pair.getValue().toString();

            // get vehicle locations for this route_id:
            JSONArray vehicleArray;
            if (route_id.equals("C") || route_id.equals("MSC Express")){
                vehicleArray = downloadVehicleDetails_C(route_id, external_id);
            } else {
                vehicleArray = downloadVehicleDetails(external_id);
            }
            // loop through vehicleArray to build vehiclePosition for the given route
            for (int k = 0; k < vehicleArray.length(); k++) {


                JSONObject vehicleObj = vehicleArray.getJSONObject(k);
                // initiate feed
                TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
                Position.Builder position = Position.newBuilder();
                VehicleDescriptor.Builder VehicleInfo = VehicleDescriptor.newBuilder();
                FeedEntity.Builder vehiclePositionEntity = FeedEntity.newBuilder();

                // set values for feed
                tripDescriptor.setRouteId(route_id);
                position.setBearing((float) Math.round(100*vehicleObj.getDouble("headingDegrees"))/100);
                position.setLatitude((float) vehicleObj.getDouble("lat"));
                position.setLongitude((float) vehicleObj.getDouble("lon"));
                position.setSpeed((float) Math.round(100*vehicleObj.getDouble("speed"))/100);
                VehicleInfo.setId(vehicleObj.getString("name"));
                VehicleInfo.setLabel(vehicleObj.getString("name"));

                // Build feed
                VehiclePosition.Builder VehiclePosition_route = VehiclePosition.newBuilder();
                VehiclePosition_route.setPosition(position);
                VehiclePosition_route.setTrip(tripDescriptor);
                VehiclePosition_route.setVehicle(VehicleInfo);
                VehiclePosition_route.setTimestamp(
                        Instant.parse(vehicleObj.getString("lastUpdated")).getEpochSecond()
                );
                if (vehicleObj.getDouble("passengerLoad") <= 0)
                    VehiclePosition_route.setOccupancyStatus(OccupancyStatus.EMPTY);
                else if (vehicleObj.getDouble("passengerLoad") <= 50)
                    VehiclePosition_route.setOccupancyStatus(OccupancyStatus.MANY_SEATS_AVAILABLE);
                else if (vehicleObj.getDouble("passengerLoad") <= 70)
                    VehiclePosition_route.setOccupancyStatus(OccupancyStatus.FEW_SEATS_AVAILABLE);
                else if (vehicleObj.getDouble("passengerLoad") <= 90)
                    VehiclePosition_route.setOccupancyStatus(OccupancyStatus.STANDING_ROOM_ONLY);
                else if (vehicleObj.getDouble("passengerLoad") <= 95)
                    VehiclePosition_route.setOccupancyStatus(OccupancyStatus.CRUSHED_STANDING_ROOM_ONLY);
                else VehiclePosition_route.setOccupancyStatus(OccupancyStatus.FULL);

                vehicleFeedID++;
                vehiclePositionEntity.setId(Integer.toString(vehicleFeedID));
                vehiclePositionEntity.setVehicle(VehiclePosition_route);
                vehiclePositions.addEntity(vehiclePositionEntity.build());
            }
        }
        _vehiclePositionsSink.handleFullUpdate(vehiclePositions);
        _log.info("vehicles' location extracted: " + vehiclePositions.getEntities().size());
        // ---- END UPDATING VEHICLE POSITIONS -----------------
        /* ---- TRIP UPDATES WAS REMOVED FROM VERSION 1.0-------------------
        * We have experienced inconsistencies in the generated trip_updates,
        * (see issue #8 (https://github.com/CUTR-at-USF/bullrunner-gtfs-realtime-generator/issues/8)
        * So we decided to remove trip_updates until we find a better solution.
        * */
    }


    /**
     * Method to hit the API and Get Vehicle locations for a given external route_id (Syncromatics route id)
     * @param external_route_id
     * @return JSONArray of vehicle locations
     * @throws IOException
     * @throws JSONException
     */
    private JSONArray downloadVehicleDetails(String external_route_id) throws IOException, JSONException {
        URLConnection connection = null;
        URL URL_Request = new URL(_url + "routes/" + external_route_id + "/vehicles?api-key=" + _api_key);
        try {
            connection = URL_Request.openConnection();
        } catch (Exception ex) {
            _log.error("Error in opening feeds url", ex);
        }
        connection.setConnectTimeout(_Conn_Timeout_MS);
        connection.setReadTimeout(_Conn_Timeout_MS);
        InputStream in = connection.getInputStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

        StringBuilder builder = new StringBuilder();
        String inputLine;
        JSONArray response;
        try {
            while ((inputLine = reader.readLine()) != null)
                builder.append(inputLine).append("\n");
            response = new JSONArray(builder.toString());
        } catch (SocketTimeoutException ex) {
            _log.error("Error readline, server doesn't close the connection.", ex);
            response = null;
        }

        return response;
    }

    /**
     * Method to get Vehicle locations for MSC Express (has the same route_id with C)
     *     route id must be C or MSC Express
     * @param route_id
     * @param external_route_id
     * @return JSONArray of vehicle locations for given route
     * @throws IOException
     * @throws JSONException
     */
    private JSONArray downloadVehicleDetails_C(String route_id, String external_route_id) throws IOException, JSONException {
        final String Pattern_name_C = "Route C";
        final String Pattern_name_MSC = "MSC Express";
        final String Route_id_C = "C";
        final String Route_id_MSC = "MSC Express";

        if ((!route_id.equals(Route_id_C)) & (!route_id.equals(Route_id_MSC))) {
            throw new Error("Method downloadVehicleDetails_C only applies to route C or MSC Express");
        }
        URLConnection connection = null;
        URL URL_Request = new URL(_url + "routes/" + external_route_id + "/vehicles?api-key=" + _api_key);
        try {
            connection = URL_Request.openConnection();
        } catch (Exception ex) {
            _log.error("Error in opening feeds url", ex);
        }
        connection.setConnectTimeout(_Conn_Timeout_MS);
        connection.setReadTimeout(_Conn_Timeout_MS);
        InputStream in = connection.getInputStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

        StringBuilder builder = new StringBuilder();
        String inputLine;
        JSONArray response;
        try {
            while ((inputLine = reader.readLine()) != null)
                builder.append(inputLine).append("\n");
            response = new JSONArray(builder.toString());
        } catch (SocketTimeoutException ex) {
            _log.error("Error readline, server doesn't close the connection.", ex);
            response = null;
        }

        if (response.length() == 0)
            return null;

        // keep only vehicles of the given route_id
        JSONArray response_out = new JSONArray();
        for (int i = 0; i < response.length(); i++){
            JSONObject vehicleObj = response.getJSONObject(i);
            String vehicle_id = vehicleObj.getString("id");
            // check pattern name
            URL URL_pattern = new URL(_url + "v1/vehicles/" + vehicle_id + "/arrivals?count=100&api-key=" + _api_key);
            try {
                connection = URL_pattern.openConnection();
            } catch (Exception ex) {
                _log.error("Error in opening feeds url", ex);
            }
            connection.setConnectTimeout(_Conn_Timeout_MS);
            connection.setReadTimeout(_Conn_Timeout_MS);
            in = connection.getInputStream();

            BufferedReader reader1 = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            StringBuilder builder1 = new StringBuilder();
            String inputLine1;
            JSONArray response_pattern;
            try {
                while ((inputLine1 = reader1.readLine()) != null)
                    builder1.append(inputLine1).append("\n");
                response_pattern = new JSONArray(builder1.toString());
                String pattern_name = response_pattern.getJSONObject(0).getJSONObject("pattern").getString("name");
            } catch (SocketTimeoutException ex) {
                _log.error("Error readline, server doesn't close the connection.", ex);
                response_pattern = null;
                return null;
            }
            String pattern_name = response_pattern.getJSONObject(0).getJSONObject("pattern").getString("name");

            // check if pattern name matches
            if (pattern_name.equals(Pattern_name_C)){
                if (route_id.equals(Route_id_C))
                    response_out.put(vehicleObj);
            } else if (pattern_name.equals(Pattern_name_MSC)){
                if (route_id.equals(Route_id_MSC))
                    response_out.put(vehicleObj);
            } else {
                throw new Error("NEW PATTERN NAME DETECTED FOR ROUTE C IN THE API: " + pattern_name);
            }
        }

        return response_out;
    }


    /**
     * Task that will download new vehicle data from the remote data source when
     * executed.
     */
    private class VehiclesRefreshTask implements Runnable {

        @Override
        public void run() {
            try {
                _log.info("refreshing vehicles");
                refreshTripVehicle();
                //test_refreshVehicles();
            } catch (Exception ex) {
                _log.warn("Error in vehicle refresh task", ex);
            }
        }
    }

}
