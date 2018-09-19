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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;


public class BullRunnerConfigExtract {
    private static String mPath2tripsFile;
    private static String mPath2calFile;
    private static String mPath2routeFile;
    private static String mPath2stopTimesFile;
    private static String mPath2frequenciesFile;

    Map<String, Integer> mRoutesMap = new HashMap<>();
    String[] mServiceIds = new String[7];
    BiHashMap<String, String, String> mTripIDMap = new BiHashMap<>();
    Map<String, String> mStartTimeByTripIDMap = new HashMap<>();
    Map<String, String> mExternalIDMap = new HashMap<>();
    BiHashMap<String, String, String> mStopSeqIdMap = new BiHashMap<>();

    /**
     * Find the USF Bull Runner GTFS directory in the current directory or the parent directory.
     * If not found, throw an Error and exit
     */
    public void findPaths() {
        String GTFS_path;
        if (Files.exists(Paths.get("./bullrunner-gtfs"))) {
            GTFS_path = "./bullrunner-gtfs";
        } else if (Files.exists(Paths.get("../bullrunner-gtfs"))) {
            GTFS_path = "../bullrunner-gtfs";
        } else {
            throw new IllegalArgumentException("GTFS FILE NOT FOUND! MAKE SURE YOU HAVE EXTRACTED THE GTFS ZIP FILE IN THE MAIN DIRECTORY OR IN THE TARGET DIRECTORY");
        }
        mPath2tripsFile = GTFS_path + "/trips.txt";
        mPath2calFile = GTFS_path + "/calendar.txt";
        mPath2routeFile = GTFS_path + "/routes.txt";
        mPath2stopTimesFile = GTFS_path + "/stop_times.txt";
        mPath2frequenciesFile = GTFS_path + "/frequencies.txt";
    }

    /**
     * @return a JSON array parsed from the data pulled from the USF Bull Runner Syncromatics API.
     */
    public JSONArray downloadCofiguration(URL url) throws IOException, JSONException {

        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));

        StringBuilder builder = new StringBuilder();
        String inputLine;

        while ((inputLine = reader.readLine()) != null) {
            builder.append(inputLine).append("\n");
        }

        JSONArray object = (JSONArray) new JSONTokener(builder.toString())
                .nextValue();

        return object;
    }

    public void generatesRouteMap(URL url) throws IOException, JSONException {

        JSONArray configArray = downloadCofiguration(url);

        for (int i = 0; i < configArray.length(); i++) {

            JSONObject obj = configArray.getJSONObject(i);
            /*
             * "ID":423,
      			"DisplayName":"A Route A",
             */
            int ID = obj.getInt("ID");
            String route = obj.getString("DisplayName").substring(0, 1);
            mRoutesMap.put(route, ID);
        }
    }

    //

    /**
     * Extract the service_id for day of week
     *
     * @throws IOException
     */
    public void generateServiceMap() throws IOException {
        String splitBy = ",";
        String line;
        BufferedReader servicesBuffer = new BufferedReader(new FileReader(mPath2calFile));
        line = servicesBuffer.readLine();
        while ((line = servicesBuffer.readLine()) != null) {
            String[] tokens = line.split(splitBy);
            for (int i = 1; i <= 7; i++) {
                int day = Integer.parseInt(tokens[i]);
                if (day == 1) {
                    if (i != 7) {
                        mServiceIds[i] = tokens[0];
                    } else {
                        mServiceIds[0] = tokens[0];
                    }
                }
            }
        }
    }

    public void generateTripMap() throws IOException {
        String line;
        BufferedReader tripsBuffer = new BufferedReader(new FileReader(mPath2tripsFile));

        String splitBy = ",";
        line = tripsBuffer.readLine();
        while ((line = tripsBuffer.readLine()) != null) {
            String[] tripRoute = line.split(splitBy);
            //System.out.println(tripRoute[0]+" , "+ tripRoute[1]+" , "+ tripRoute[2]);
            mTripIDMap.put(tripRoute[0], tripRoute[1], tripRoute[2]);
        }

    }


    /**
     * This function extract the corresponding sequence ID for each stop ID from stop_times.txt in GTFS files.
     * We need tripID and stopID to extract stop sequence.
     *
     * @throws IOException
     */
    public void extractSeqId() throws IOException {

        String line;
        String[] tokens;
        String delims = "[,]+";
        String stop_id = "", trip_id = "", stop_sequence = "";

        BufferedReader stop_times = new BufferedReader(new FileReader(mPath2stopTimesFile));
        line = stop_times.readLine();

        try {
            line = stop_times.readLine();
            while (line != null) {

                tokens = line.split(delims);
                trip_id = tokens[0];
                stop_id = tokens[3];
                stop_sequence = tokens[4];
                String preStopSeq = "";
                try {
                    preStopSeq = mStopSeqIdMap.get(trip_id, stop_id);
                } catch (NullPointerException e) {

                }
                if (preStopSeq == null)
                    mStopSeqIdMap.put(trip_id, stop_id, stop_sequence);
                line = stop_times.readLine();
            }

        } finally {
            stop_times.close();
        }
        if (stop_sequence.equals("")) {
            throw new RuntimeException("Cannot find the stop_sequence = " + stop_sequence + ", or stop_id= " + stop_id);
        }

    }

    /**
     * Extract the corresponding start_time for each trip ID from frequencies.txt in GTFS files
     *
     * @throws IOException
     */
    public void extractStartTime() throws IOException {
        String line;
        String[] tokens;
        String delims = "[,]+";
        BufferedReader frequencies = null;
        try {
            frequencies = new BufferedReader(new FileReader(mPath2frequenciesFile));
            line = frequencies.readLine();
        } catch (IOException e) {
            System.err.println("Error, not able to open" + e);
        }
        String start_time = "";
        String trip_id = "";

        try {
            line = frequencies.readLine();
            while (line != null) {
                tokens = line.split(delims);
                trip_id = tokens[0];
                start_time = tokens[1];
                mStartTimeByTripIDMap.put(trip_id, start_time);
                line = frequencies.readLine();
            }
        } finally {
            frequencies.close();
        }
    }

    /**
     * Create a mapping between Syncromatics' route id and Bull Runner GTFS route id (A, B, C, etc.)
     *
     * @throws IOException
     */
    public void generateExternalIDMap() throws IOException {
        BufferedReader routesBuffer = new BufferedReader(new FileReader(mPath2routeFile));
        String line = routesBuffer.readLine();
        while ((line = routesBuffer.readLine()) != null) {
            String[] Route = line.split(",");
            mExternalIDMap.put(Route[0], Route[7].toString());
        }
    }
}