/**
 * Copyright (C) 2012 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.usf.cutr.gtfs_realtime.bullrunner;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.text.ParsePosition;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeMutableProvider;
import org.onebusway.gtfs_realtime.exporter.GtfsRealtimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtime.Position;
import com.google.transit.realtime.GtfsRealtime.TripDescriptor;
import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeEvent;
import com.google.transit.realtime.GtfsRealtime.TripUpdate.StopTimeUpdate;
import com.google.transit.realtime.GtfsRealtime.VehicleDescriptor;
import com.google.transit.realtime.GtfsRealtime.VehiclePosition;

import java.net.URL;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

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
	private String responseTimeStamp;
	private ScheduledExecutorService _executor;

	private GtfsRealtimeMutableProvider _gtfsRealtimeProvider;
	private URL _url;

	/**
	 * How often vehicle data will be downloaded, in seconds.
	 */
	private int _refreshInterval = 1500;
	private BullRunnerConfigExtract _providerConfig;

	@Inject
	public void setGtfsRealtimeProvider(
			GtfsRealtimeMutableProvider gtfsRealtimeProvider) {
		_gtfsRealtimeProvider = gtfsRealtimeProvider;
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

	@PostConstruct
	public void start() {

		try {
			_providerConfig .setUrl(new URL( "http://api.syncromatics.com/feed/511/Configuration/?api_key=593e3f10de49d7fec7c8ace98f0ee6d1&format=json"));
			//_providerConfig.generatesRouteMap();
			_providerConfig.generateTripMap();
			_providerConfig.extractSeqId();
			_providerConfig.extractStartTime();
			
		} catch (Exception ex) {
			_log.warn("Error in retriving confirmation data!", ex);
		}
		_log.info("starting GTFS-realtime service");
		_executor = Executors.newSingleThreadScheduledExecutor();
		_executor.scheduleAtFixedRate(new VehiclesRefreshTask(), 0,
				_refreshInterval, TimeUnit.SECONDS);
	}

	/**
	 * The stop method cancels the recurring vehicle data downloader task.
	 */
	@PreDestroy
	public void stop() {
		_log.info("stopping GTFS-realtime service");
		_executor.shutdownNow();
	}

	/****
	 * Private Methods - Here is where the real work happens
	 ****/

	/**
	 * This method downloads the latest vehicle data, processes each vehicle in
	 * turn, and create a GTFS-realtime feed of trip updates and vehicle
	 * positions as a result.
	 */
	private void refreshVehicles() throws IOException, JSONException {
		String startTime = "";
		/**
		 * We download the vehicle details as an array of JSON objects.
		 */

		Pair pair = downloadVehicleDetails();
		JSONArray stopIDsArray = pair.getArray1();
		JSONArray vehicleArray = pair.getArray2();
		/**
		 * The FeedMessage.Builder is what we will use to build up our
		 * GTFS-realtime feeds. We create a feed for both trip updates and
		 * vehicle positions.
		 */
		FeedMessage.Builder tripUpdates = GtfsRealtimeLibrary
				.createFeedMessageBuilder();
		FeedMessage.Builder vehiclePositions = GtfsRealtimeLibrary
				.createFeedMessageBuilder();
		 VehicleDescriptor.Builder vehicleDescriptor = null;
		/**
		 * We iterate over every JSON vehicle object.
		 */
		long respTime;
		long predictTime = 0;

		int step = 1;
		int routeNumber;
		String routeTitle, trip;
		String route;
		 int busId = 0;
		 int vehicleFeedID = 0;
		 String stopId = "";
		  
		for (int i = 0; i <stopIDsArray.length(); i += step) {

			JSONObject obj = stopIDsArray.getJSONObject(i);

			routeTitle = obj.getString("route"); 
			route = routeTitle.substring(6);
	 
			trip = _providerConfig.tripIDMap.get(route);
			
			
			int stopId_int = obj.getInt("stop");
			stopId = Integer.toString(stopId_int);
 
			JSONArray childArray = obj.getJSONArray("Ptimes");
			 
			  respTime = 0;
				
			for (int j = 0; j < childArray.length(); j++) {
				
				JSONObject child = childArray.getJSONObject(j);
				String predTimeStamp = child.getString("PredictionTime");

				predictTime = convertTime(predTimeStamp) / 1000;
				respTime = convertTime(responseTimeStamp) / 1000;
 

				//delay = (int) (predictTime - respTime) / 60;
				String vehicleId = child.getString("VehicleId");				
				vehicleDescriptor = VehicleDescriptor.newBuilder();
				vehicleDescriptor.setId(vehicleId);
				/**
				 * We construct a TripDescriptor and VehicleDescriptor, which
				 * will be used in both trip updates and vehicle positions to
				 * identify the trip and vehicle. Ideally, we would have a trip
				 * id to use for the trip descriptor, but the SEPTA api doesn't
				 * include it, so we settle for a route id instead.
				 */
				TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
				tripDescriptor.setRouteId(route);
				tripDescriptor.setTripId(trip);
				 
				//add the start_times filed
				startTime = _providerConfig.startTimeByTripIDMap.get(trip);
				tripDescriptor.setStartTime(startTime);
				
				/**
				 * To construct our TripUpdate, we create a stop-time arrival
				 * event for the next stop for the vehicle, with the specified
				 * arrival delay. We add the stop-time update to a TripUpdate
				 * builder, along with the trip and vehicle descriptors.
				 */
				StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();
				//arrival.setDelay(delay);
				arrival.setTime(predictTime);
				StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate
						.newBuilder();
				stopTimeUpdate.setArrival(arrival);
				stopTimeUpdate.setStopId(stopId);
				String stopSeq = _providerConfig.stopSeqIDMap.get(trip, stopId);
				//System.out.println("trip = "+ trip+ ", stopSeq =" + Integer.parseInt(stopSeq)+ ", stopId =" + stopId);
				stopTimeUpdate.setStopSequence(Integer.parseInt(stopSeq));
				TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();
				tripUpdate.addStopTimeUpdate(stopTimeUpdate);
				tripUpdate.setVehicle(vehicleDescriptor);
				tripUpdate.setTrip(tripDescriptor);

				/**
				 * Create a new feed entity to wrap the trip update and add it
				 * to the GTFS-realtime trip updates feed.
				 */
				FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
 
				busId ++;
				tripUpdateEntity.setId(Integer.toString(busId));
				tripUpdateEntity.setTripUpdate(tripUpdate);
				tripUpdates.addEntity(tripUpdateEntity);
			}
		}
		//System.out.println("last: busId ="+ busId+ ", stopId = "+ stopId+ ", arrival = "+predictTime);
		
		for (int k = 0; k < vehicleArray.length(); k++) {
			JSONObject vehicleObj = vehicleArray.getJSONObject(k);

			//route = vehicleObj.getString("route");
			//route = routeTitle.substring(6);

			routeTitle = vehicleObj.getString("route");
			route = routeTitle.substring(6);
		 
			//routeNumber = obj.getInt("route");
			//routeTitle = _providerConfig.routesMap.get(routeNumber);
 
			

			JSONArray vehicleLocsArray = vehicleObj .getJSONArray("VehicleLocation");
			
			for (int l = 0; l < vehicleLocsArray.length(); ++l) {

				JSONObject child = vehicleLocsArray.getJSONObject(l);
				double lat = child.getDouble("vehicleLat");
				double lon = child.getDouble("vehicleLong");
				/**
				 * To construct our VehiclePosition, we create a position for
				 * the vehicle. We add the position to a VehiclePosition
				 * builder, along with the trip and vehicle descriptors.
				 */
				TripDescriptor.Builder tripDescriptor = TripDescriptor
						.newBuilder();

				tripDescriptor.setRouteId(route);
				Position.Builder position = Position.newBuilder();
				position.setLatitude((float) lat);
				position.setLongitude((float) lon);
				VehiclePosition.Builder vehiclePosition = VehiclePosition
						.newBuilder();
				vehiclePosition.setPosition(position);
				vehiclePosition.setTrip(tripDescriptor);

				/**
				 * Create a new feed entity to wrap the vehicle position and add
				 * it to the GTFS-realtime vehicle positions feed.
				 */
				FeedEntity.Builder vehiclePositionEntity = FeedEntity
						.newBuilder();
				int tripID_int = child.getInt("tripId");
			
				String TripID = Integer.toString(tripID_int);
				String vehicleId = child.getString("VehicleId");				
				vehicleDescriptor = VehicleDescriptor.newBuilder();
				vehicleDescriptor.setId(vehicleId);
				
				vehicleFeedID ++;

				vehiclePositionEntity.setId(Integer.toString(vehicleFeedID));
				vehiclePosition.setVehicle(vehicleDescriptor);
				vehiclePositionEntity.setVehicle(vehiclePosition);
				
				vehiclePositions.addEntity(vehiclePositionEntity);
				
			}
 		}


		/**
		 * Build out the final GTFS-realtime feed messagse and save them.
		 */
		 _gtfsRealtimeProvider.setTripUpdates(tripUpdates.build());
		
		
		 _gtfsRealtimeProvider.setVehiclePositions(vehiclePositions.build());
		
		 _log.info("vehicles' location extracted: " + vehiclePositions.getEntityCount());
		 _log.info("stoIDs extracted: " + tripUpdates.getEntityCount());
	}// end of refresh

	
	private void test_refreshVehicles() throws IOException, JSONException, ParseException {

		String startTime = "";
		 int busId = 0;
		 int[] tripArr = new int [2];
		 
		 int[][] delayArr = new int [2][2];
		 delayArr[0][0] = 4*60;
		 delayArr[0][1] = 1*60;
		 
		 delayArr[1][0] = 3*60;
		 delayArr[1][1] = 8*60;
		 
		 int[][] stopArr = new int [2][2];
		 stopArr[0][0]= 401;
		 stopArr[1][0]= 204;
		 
		 stopArr[0][1]= 801;
		 stopArr[1][1]= 807;
		 // frequency = 30 min
		 String[] refTime= {"07-April-2014 07:00:00 PDT", "07-April-2014 07:30:00 PDT"};
		 
		 int stopId_int;
		 String[] trainNumber = {"1", "2"};
		 VehicleDescriptor.Builder vehicleDescriptor = null;
		 
		 FeedMessage.Builder tripUpdates = GtfsRealtimeLibrary.createFeedMessageBuilder();
		 String tripId = "1";
		for (int i = 0; i < tripArr.length; i += 1) {
		//for (int i = 0; i < 1; i += 1) {


			FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
			TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
		 
			tripUpdateEntity.setId(Integer.toString(busId +1));
		 
			tripDescriptor.setTripId(tripId);
			
			vehicleDescriptor = VehicleDescriptor.newBuilder();
			vehicleDescriptor.setId(trainNumber[i]);
			
			 
			//add the start_times filed
			startTime = _providerConfig.startTimeByTripIDMap.get(tripId);
			tripDescriptor.setStartTime(startTime);
			System.out.println("tripID = "+  busId+ ", startTime = "+ startTime);
			
			
			 TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();
			tripUpdate.setTrip(tripDescriptor);
			tripUpdate.setVehicle(vehicleDescriptor);
			
			for (int s=0; s < stopArr[i].length; s+=1){
				
				 StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate.newBuilder();
				 StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();
				 
				//stopId_int = stopArr[s][i];
				stopId_int = stopArr[s][0];
				StringBuilder stop = new StringBuilder();
				stop.append("");
				stop.append(stopId_int);
				String stopId = stop.toString();
				stopTimeUpdate.setStopId(stopId);
				
				 
				//Getting “unixtime”
				//long unixtime2 = Date.parse("01-March-2014 07:00:00").getTime()/1000;
		 
				
				String pattern = "dd-MMM-yyyy HH:mm:ss z";
				Date date = new SimpleDateFormat(pattern, Locale.ENGLISH).parse(refTime[i]);
				//DateTime unixTime = new DateTime(date.getTime());
				//number of seconds that have elapsed since 00:00:00 Coordinated Universal Time (UTC), Thursday, 1 January 1970
				//long unixTime = System.currentTimeMillis() / 1000L;
				long unixTime = date.getTime()/1000;
				//System.out.println("[busId][s]"+ busId + s+"unixTime =" + unixTime);
				
					
				long timePlusDelay = unixTime + delayArr[busId][s];
				System.out.println("bus= " + (int)(busId+1) + ", stopId ="+ stopId_int + ", delay in minutes= "+ (timePlusDelay-unixTime)/60);
				arrival.setTime(timePlusDelay);
				
				//arrival.setDelay(delayArr[busId][s]);
				
				
				
				//stopTimeUpdate.setStopSequence(s+1);
				stopTimeUpdate.setArrival(arrival);
				
				tripUpdate.addStopTimeUpdate(stopTimeUpdate);
				
			}
			System.out.println("----------next trip-----------------");
			busId ++;
			tripUpdateEntity.setTripUpdate(tripUpdate);
			tripUpdates.addEntity(tripUpdateEntity);
			 
	}
			 
		_gtfsRealtimeProvider.setTripUpdates(tripUpdates.build());
		 
	 
	}/// end of refresh!

	
	
	
	/**
	 * @return a JSON array parsed from the data pulled from the SEPTA vehicle
	 *         data API.
	 */
	public class Pair {
		private JSONArray array1;
		private JSONArray array2;

		public Pair(JSONArray array1, JSONArray array2) {
			this.array1 = array1;
			this.array2 = array2;

		}

		public JSONArray getArray1() {
			return array1;
		}

		public JSONArray getArray2() {
			return array2;
		}
	}

	private Pair downloadVehicleDetails() throws IOException, JSONException {

		BufferedReader reader = new BufferedReader(new InputStreamReader(
				_url.openStream()));

		StringBuilder builder = new StringBuilder();
		String inputLine;

		while ((inputLine = reader.readLine()) != null)
			builder.append(inputLine).append("\n");

		JSONObject object = (JSONObject) new JSONTokener(builder.toString())
				.nextValue();
//		String message = object.getString("PredictionDataMessage");
//
//		JSONObject child_obj = new JSONObject(message);
//		String data = child_obj.getString("PredictionData");

	 
		String data = object.getString("PredictionData");

		
		JSONObject child2_obj = new JSONObject(data);

		responseTimeStamp = child2_obj.getString("TimeStamp");

		JSONArray stopIDsArray = child2_obj.getJSONArray("StopPredictions");
		JSONArray vehicleArray = child2_obj.getJSONArray("VehicleLocationData");

		return new Pair(stopIDsArray, vehicleArray);
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
				refreshVehicles();
				//test_refreshVehicles();
			} catch (Exception ex) {
				_log.warn("Error in vehicle refresh task", ex);
			}
		}
	}

	// This method extract time from timestamp
	private long convertTime(String myTimeStamp) {

		//final SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX");
		DateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"){ 
		    public Date parse(String source,ParsePosition pos) {    
		        return super.parse(source.replaceFirst(":(?=[0-9]{2}$)",""),pos);
		    }
		};
		Date time;// = new Date();
		long result = 0;
		try {
			time = sdf.parse(myTimeStamp);
			result = time.getTime();

		} catch (ParseException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result;

	}

}