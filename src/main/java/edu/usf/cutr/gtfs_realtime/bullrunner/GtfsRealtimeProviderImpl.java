/**
 * Copyright (C) 2012-2018 Google, Inc., University of South Florida
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
import java.util.Calendar;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This class produces GTFS-realtime trip updates and vehicle positions by
 * periodically polling the USF Bull Runner Syncromatics API and converting the
 * resulting vehicle data into the GTFS-realtime format.
 * <p>
 * Since this class implements GtfsRealtimeProvider, it will
 * automatically be queried by the GtfsRealtimeExporterModule to export
 * the GTFS-realtime feeds to file or to host them using a simple web-server, as
 * configured by the client.
 */
@Singleton
public class GtfsRealtimeProviderImpl {

    private static final String FIELD_PASSENGER_LOAD = "passengerLoad";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_SPEED = "speed";
    private static final String FIELD_HEADING_DEGREES = "headingDegrees";
    private static final String FIELD_LAT = "lat";
    private static final String FIELD_LON = "lon";
    private static final String FIELD_LAST_UPDATED = "lastUpdated";

    private static final Logger mLog = LoggerFactory.getLogger(GtfsRealtimeProviderImpl.class);
    private static final int TIMEOUT_MS = (int) TimeUnit.SECONDS.toMillis(10); // connection time out 10s
    private ScheduledExecutorService mExecutor;
    private BullRunnerGtfsRealtimeExporter mGtfsRealtimeProvider;
    private URL mUrl;
    private String mApiKey;

    /**
     * How often vehicle data will be downloaded, in seconds.
     */
    private int mRefreshInterval = 30;
    private BullRunnerConfigExtract mProviderConfig;
    private GtfsRealtimeSink mVehiclePositionsSink;
    private GtfsRealtimeSink mTripUpdatesSink;

    @Inject
    public void setGtfsRealtimeProvider(BullRunnerGtfsRealtimeExporter gtfsRealtimeProvider) {
        mGtfsRealtimeProvider = gtfsRealtimeProvider;
    }

    /**
     * @param url the URL for the SEPTA vehicle data API.
     */
    public void setUrl(URL url) {
        mUrl = url;
    }

    /**
     * Find the key file in the current directory and then in the parent directory
     * If not found, throw an Error and exit
     *
     * @throws IOException
     */
    public void setKey() throws IOException {
        String keyPath;
        if (Files.exists(Paths.get("./key.txt"))) {
            keyPath = "./key.txt";
        } else if (Files.exists(Paths.get("../key.txt"))) {
            keyPath = "../key.txt";
        } else {
            throw new IOException("KEY FILE NOT FOUND! Create key.txt file in the main directory and save the API key in the file");
        }
        BufferedReader tripsBuffer = new BufferedReader(new FileReader(keyPath));
        String key = tripsBuffer.readLine();
        mApiKey = key;
    }

    /**
     * @param refreshInterval how often vehicle data will be downloaded, in seconds.
     */
    public void setRefreshInterval(int refreshInterval) {
        mRefreshInterval = refreshInterval;
    }

    /**
     * The start method automatically starts up a recurring task that
     * periodically downloads the latest vehicle data from the SEPTA vehicle
     * stream and processes them.
     */
    @Inject
    public void setProvider(BullRunnerConfigExtract providerConfig) {
        mProviderConfig = providerConfig;
    }

    @Inject
    public void setVehiclePositionsSink(@VehiclePositions GtfsRealtimeSink vehiclePositionsSink) {
        mVehiclePositionsSink = vehiclePositionsSink;
    }

    @Inject
    public void setTripUpdatesSink(@TripUpdates GtfsRealtimeSink tripUpdatesSink) {
        mTripUpdatesSink = tripUpdatesSink;
    }

    @PostConstruct
    public void start() {
        try {
            mProviderConfig.findPaths(); // try to find path of GTFS directory
            mProviderConfig.generatesRouteMap(new URL("https://usfbullrunner.com/region/0/routes"));
            mProviderConfig.generateTripMap();
            mProviderConfig.generateServiceMap();
            mProviderConfig.extractSeqId();
            mProviderConfig.extractStartTime();
            mProviderConfig.generateExternalIDMap();
        } catch (Exception ex) {
            mLog.warn("Error in retriving confirmation data!", ex);
        }
        mLog.info("starting GTFS-realtime service");
        mExecutor = Executors.newSingleThreadScheduledExecutor();
        mExecutor.scheduleAtFixedRate(new VehiclesRefreshTask(), 0, mRefreshInterval, TimeUnit.SECONDS);
    }

    /**
     * The stop method cancels the recurring vehicle data downloader task.
     */
    @PreDestroy
    public void stop() {
        mLog.info("stopping GTFS-realtime service");
        mExecutor.shutdownNow();
    }

    /**
     * Download the latest vehicle data from the Syncromatics API and create a GTFS-realtime VehiclePosition feed
     */
    private void refreshTripVehicle() throws IOException, JSONException {
        GtfsRealtimeFullUpdate vehiclePositions = new GtfsRealtimeFullUpdate();
        int vehicleFeedID = 0;
        // Loop through the external route id map to get vehicle locations for each route id
        Iterator it = mProviderConfig.mExternalIDMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry pair = (Map.Entry) it.next();
            String route_id = pair.getKey().toString();
            String external_id = pair.getValue().toString();
            if (route_id.equals("MSC Express")) {
                // Skip MSC Express as its vehicles are included when running route C
                continue;
            }

            // Get vehicle locations for this route_id
            JSONArray vehicleArray;
            if (route_id.equals("C")) {
                vehicleArray = downloadVehiclesRouteC(external_id);
            } else {
                vehicleArray = downloadVehicles(external_id);
            }

            // Loop through vehicleArray to build vehiclePosition for the given route
            for (int k = 0; k < vehicleArray.length(); k++) {
                JSONObject vehicleObj = vehicleArray.getJSONObject(k);

                // check if we have route_id to provide and if so, what is route id?
                boolean has_route_id;
                String route_id_out;
                if (route_id.equals("C")) {
                    // Route C and MSC Express
                    if (vehicleObj.getString("route_id").equals("Unknown")) {
                        has_route_id = false;
                        route_id_out = null;
                    } else {
                        has_route_id = true;
                        route_id_out = vehicleObj.getString("route_id");
                    }
                } else {
                    // All other routes
                    has_route_id = true;
                    route_id_out = route_id;
                }

                // initiate feed
                TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
                Position.Builder position = Position.newBuilder();
                VehicleDescriptor.Builder VehicleInfo = VehicleDescriptor.newBuilder();
                FeedEntity.Builder vehiclePositionEntity = FeedEntity.newBuilder();

                // set values for feed
                if (has_route_id) tripDescriptor.setRouteId(route_id_out);
                if (has_route_id) tripDescriptor.setTripId(findTripID(route_id));
                position.setBearing((float) Math.round(100 * vehicleObj.getDouble(FIELD_HEADING_DEGREES)) / 100);
                position.setLatitude((float) vehicleObj.getDouble(FIELD_LAT));
                position.setLongitude((float) vehicleObj.getDouble(FIELD_LON));
                position.setSpeed((float) Math.round(100 * vehicleObj.getDouble(FIELD_SPEED)) / 100);
                VehicleInfo.setId(vehicleObj.getString(FIELD_NAME));
                VehicleInfo.setLabel(vehicleObj.getString(FIELD_NAME));
                tripDescriptor.setScheduleRelationship(TripDescriptor.ScheduleRelationship.UNSCHEDULED);

                // Build feed
                VehiclePosition.Builder vpBuilder = VehiclePosition.newBuilder();
                vpBuilder.setPosition(position);
                vpBuilder.setTrip(tripDescriptor);
                vpBuilder.setVehicle(VehicleInfo);
                vpBuilder.setTimestamp(
                        Instant.parse(vehicleObj.getString(FIELD_LAST_UPDATED)).getEpochSecond()
                );
                if (vehicleObj.getDouble(FIELD_PASSENGER_LOAD) <= 0) {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.EMPTY);
                } else if (vehicleObj.getDouble(FIELD_PASSENGER_LOAD) <= 0.50) {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.MANY_SEATS_AVAILABLE);
                } else if (vehicleObj.getDouble(FIELD_PASSENGER_LOAD) <= 0.70) {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.FEW_SEATS_AVAILABLE);
                } else if (vehicleObj.getDouble(FIELD_PASSENGER_LOAD) <= 0.90) {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.STANDING_ROOM_ONLY);
                } else if (vehicleObj.getDouble(FIELD_PASSENGER_LOAD) <= 0.95) {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.CRUSHED_STANDING_ROOM_ONLY);
                } else {
                    vpBuilder.setOccupancyStatus(OccupancyStatus.FULL);
                }

                vehicleFeedID++;
                vehiclePositionEntity.setId(Integer.toString(vehicleFeedID));
                vehiclePositionEntity.setVehicle(vpBuilder);
                vehiclePositions.addEntity(vehiclePositionEntity.build());
            }
        }
        mVehiclePositionsSink.handleFullUpdate(vehiclePositions);
        mLog.info("Vehicle locations downloaded: " + vehiclePositions.getEntities().size());

        // We have experienced inconsistencies when trying to generate Trip Updates from the Syncromatics API data,
        // (see https://github.com/CUTR-at-USF/bullrunner-gtfs-realtime-generator/issues/8),
        // so we have decided to remove the Trip Updates feed until we find a better solution.
    }


    /**
     * Method to send a request to the Syncromatics API and get vehicle locations for a given external route_id
     * (Syncromatics route id)
     *
     * @param external_route_id
     * @return JSONArray of vehicle locations
     * @throws IOException
     * @throws JSONException
     */
    private JSONArray downloadVehicles(String external_route_id) throws IOException, JSONException {
        URLConnection connection = null;
        URL url = new URL(mUrl + "routes/" + external_route_id + "/vehicles?api-key=" + mApiKey);
        try {
            mLog.debug(url.toString());
            connection = url.openConnection();
        } catch (Exception ex) {
            mLog.error("Error in opening feeds url", ex);
        }
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
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
            mLog.error("Error readline, server doesn't close the connection.", ex);
            response = null;
        }

        return response;
    }

    /**
     * Get vehicle locations for route C and MSC Express (which has the same route_id).
     *
     * @param external_route_id The external_route_id from Bull Runner GTFS routes.txt
     * @return JSONArray of vehicle locations for given route
     * @throws IOException
     * @throws JSONException
     */
    private JSONArray downloadVehiclesRouteC(String external_route_id) throws IOException, JSONException {
        final String patternNameC = "Route C";
        final String patternNameMSC = "MSC Express";
        final String routeIdC = "C";
        final String routeIdMSC = "MSC Express";

        // get vehicles
        URLConnection connection = null;
        URL url = new URL(mUrl + "routes/" + external_route_id + "/vehicles?api-key=" + mApiKey);
        try {
            mLog.debug(url.toString());
            connection = url.openConnection();
        } catch (Exception ex) {
            mLog.error("Error in opening feeds url", ex);
        }
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        InputStream in = connection.getInputStream();

        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

        StringBuilder builder = new StringBuilder();
        String inputLine;
        JSONArray response;
        try {
            while ((inputLine = reader.readLine()) != null) {
                builder.append(inputLine).append("\n");
            }
            response = new JSONArray(builder.toString());
        } catch (SocketTimeoutException ex) {
            mLog.error("Error readline, server doesn't close the connection.", ex);
            response = null;
        }

        // Find pattern_name for each vehicle
        JSONArray responseOut = new JSONArray();
        for (int i = 0; i < response.length(); i++) {
            JSONObject vehicleObj = response.getJSONObject(i);
            String vehicle_id = vehicleObj.getString("id");
            URL urlPattern = new URL(mUrl + "v1/vehicles/" + vehicle_id + "/arrivals?count=100&api-key=" + mApiKey);
            try {
                mLog.debug(urlPattern.toString());
                connection = urlPattern.openConnection();
            } catch (Exception ex) {
                mLog.error("Error in opening feeds url", ex);
            }
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            in = connection.getInputStream();

            BufferedReader reader1 = new BufferedReader(new InputStreamReader(in, "UTF-8"));

            StringBuilder builder1 = new StringBuilder();
            String inputLine1;
            JSONArray responsePattern;
            try {
                while ((inputLine1 = reader1.readLine()) != null) {
                    builder1.append(inputLine1).append("\n");
                }
                responsePattern = new JSONArray(builder1.toString());
            } catch (SocketTimeoutException ex) {
                mLog.error("Error readline, server doesn't close the connection.", ex);
                continue;
            }

            String pattern_name;
            if (responsePattern.length() == 0) {
                mLog.error("Syncromatics API call is empty: " + urlPattern.toString());
                pattern_name = "Unknown";
            } else {
                pattern_name = responsePattern.getJSONObject(0).getJSONObject("pattern").getString("name");
            }

            // Assign route_id based on pattern_name
            if (pattern_name.equals("Unknown")) {
                vehicleObj.put("route_id", "Unknown"); // flag to let method refreshTripVehicle know to not set route_id
                responseOut.put(vehicleObj);
            } else if (pattern_name.equals(patternNameC)) {
                vehicleObj.put("route_id", routeIdC);
                responseOut.put(vehicleObj);
            } else if (pattern_name.equals(patternNameMSC)) {
                vehicleObj.put("route_id", routeIdMSC);
                responseOut.put(vehicleObj);
            } else {
                mLog.warn("NEW PATTERN NAME DETECTED FOR ROUTE C IN THE API: " + pattern_name);
            }
        }

        return responseOut;
    }

    /**
     * Method to find trip_id of the given route
     * First find service_id from the day today then use the mapping from trips.txt
     */
    private String findTripID(String route_id) {
        // get int current day of week (Sun-Sat = 0-7)
        int currentDay = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;

        // get service_id for today
        String serviceID = mProviderConfig.mServiceIds[currentDay];

        // use service_id and route_id to find trip_id
        String tripId = mProviderConfig.mTripIDMap.get(route_id, serviceID);

        if (tripId == null || tripId.equals("")) {
            mLog.error("Cannot find trip_id for Route " + route_id + " and service_id " + serviceID);
        }

        return tripId;
    }

    /**
     * Task that will download new vehicle data from the remote data source when
     * executed.
     */
    private class VehiclesRefreshTask implements Runnable {
        @Override
        public void run() {
            try {
                mLog.info("Refreshing vehicles...");
                refreshTripVehicle();
            } catch (Exception ex) {
                mLog.warn("Error in vehicle refresh task", ex);
            }
        }
    }

}
