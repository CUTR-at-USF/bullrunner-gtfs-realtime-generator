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


import java.util.List;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
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
import com.google.transit.realtime.GtfsRealtime.TripDescriptor.ScheduleRelationship;
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
//	public enum ScheduleRelationship{
//		 UNSCHEDULED, ADDED, SCHEDULED,CANCELED
//	}
	private static final Logger _log = LoggerFactory
			.getLogger(GtfsRealtimeProviderImpl.class);
	private String responseTimeStamp;
	private ScheduledExecutorService _executor;

	private GtfsRealtimeMutableProvider _gtfsRealtimeProvider;
	private URL _url;
	private BiHashMap<String, String, Float> routeVehiDirMap;
	private BiHashMap<String, String, vehicleInfo> tripVehicleInfoMap;
	
	/**
	 * How often vehicle data will be downloaded, in seconds.
	 */
	private int _refreshInterval = 5;
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
			//_providerConfig .setUrl(new URL( "http://usfbullrunner.com/region/0/routes"));
			_providerConfig.generatesRouteMap(new URL( "http://usfbullrunner.com/region/0/routes"));
			_providerConfig.generateTripMap();
			_providerConfig.generateServiceMap();
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
	private void refreshTripVehicle() throws IOException, JSONException {

		Pair pair = downloadVehicleDetails();
		
		JSONArray stopIDsArray = pair.getArray1();
		JSONArray vehicleArray = pair.getArray2();
		
		Calendar cal = Calendar.getInstance();
		int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) -1;
		String serviceID = _providerConfig.serviceIds[dayOfWeek];
		//System.out.println(dayOfWeek + " , "+ serviceID);
		FeedMessage.Builder tripUpdates = GtfsRealtimeLibrary.createFeedMessageBuilder();
		
		FeedMessage.Builder vehiclePositions = GtfsRealtimeLibrary.createFeedMessageBuilder();
		 VehicleDescriptor.Builder vehicleDescriptor = null;
		 String route, trip;
		 int entity = 0;
		 int vehicleFeedID = 0;
		 String stopId = "";
		 String stopSeq;
		 long predictTime = 0;
		 TripUpdate.Builder tripUpdate = null;
		 TripDescriptor.Builder tripDescriptor = null;
		 
		 BiHashMap<String, String, TripUpdate.Builder> tripUpdateMap = new BiHashMap<String, String, TripUpdate.Builder>();
		 List <TripUpdate.Builder> tripUpdateArr = new ArrayList();
		 List <stopTimeUpdateRecord> records = new ArrayList<stopTimeUpdateRecord>();
		 routeVehiDirMap = new BiHashMap<String, String, Float>();
		 tripVehicleInfoMap = new BiHashMap<String, String, vehicleInfo>();
		 
		 for (int i = 0; i < stopIDsArray.length(); i ++) {
				JSONObject obj = stopIDsArray.getJSONObject(i);
				route = obj.getString("route").substring(6); 			
				//trip = _providerConfig.tripIDMap.get(route);
				trip = _providerConfig.tripIDMap.get(route, serviceID);	
				if (trip.equals("") || trip == null)
					_log.error("Route "+ route+ "dosn't exit in GTFS file");
				int stopId_int = obj.getInt("stop");
				stopId = Integer.toString(stopId_int);
				JSONArray childArray = obj.getJSONArray("Ptimes");			 
				
				for (int j = 0; j < childArray.length(); j++) {
					
					JSONObject child = childArray.getJSONObject(j);
					String predTimeStamp = child.getString("PredictionTime");
					predictTime = convertTime(predTimeStamp) / 1000;
					String vehicleId = child.getString("VehicleId");
					
					if (!tripUpdateMap.containsKey(route, vehicleId)){
						tripUpdate = TripUpdate.newBuilder();
						vehicleDescriptor = VehicleDescriptor.newBuilder();
						vehicleDescriptor.setId(vehicleId);
						tripDescriptor = TripDescriptor.newBuilder();
						tripDescriptor.setScheduleRelationship(ScheduleRelationship.UNSCHEDULED);
						tripDescriptor.setRouteId(route);
						tripDescriptor.setTripId(trip);
						tripUpdate.setVehicle(vehicleDescriptor);
						tripUpdate.setTrip(tripDescriptor);	
						tripUpdateMap.put(route, vehicleId, tripUpdate);
						tripUpdateArr.add(tripUpdate);
					}else{
						tripUpdate = tripUpdateMap.get(route, vehicleId);
						
						//System.out.println("OLD entry = route "+ route+ ", vehicleID = "+ vehicleId);
					}
					
					StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();
					arrival.setTime(predictTime);
					StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate.newBuilder();
					stopTimeUpdate.setArrival(arrival);
					stopTimeUpdate.setStopId(stopId);					
					
					stopSeq = _providerConfig.stopSeqIDMap.get(trip, stopId);
					 if( stopSeq == null){
						stopSeq = "0";
						_log.warn("Error stopID: "+ stopId+ " is not available in GTFS files");
						//System.out.println("Error: stopID: "+ stopId+ " is not available in GTFS files");
					}
					stopTimeUpdate.setStopSequence(Integer.parseInt(stopSeq));
					records.add(new stopTimeUpdateRecord(tripUpdate, stopTimeUpdate));
					//tripUpdate.addStopTimeUpdate(stopTimeUpdate);							
				}				
		 }
		 Collections.sort(records);
		 for (int i=0; i< records.size();i++){
			 records.get(i).tripUpdate.addStopTimeUpdate(records.get(i).stopTimeUpdate);
		 }
				/**
				 * Create a new feed entity to wrap the trip update and add it
				 * to the GTFS-realtime trip updates feed.
				 */	
			 //System.out.println("len updtes = "+ tripUpdateArr.size()+ ", hass = "+  tripUpdateMap.getSize());
			 for (int j = 0; j < tripUpdateArr.size(); j++){
					FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
					tripUpdate = tripUpdateArr.get(j);
					entity ++;
					 
					tripUpdateEntity.setId(Integer.toString(entity));
					tripUpdateEntity.setTripUpdate(tripUpdate);
					tripUpdates.addEntity(tripUpdateEntity);
			 }
			 _gtfsRealtimeProvider.setTripUpdates(tripUpdates.build());
			  
			// _log.info("stoIDs extracted: " + tripUpdates.getEntityCount());
			 System.out.println("stoIDs extracted: " + tripUpdates.getEntityCount());
			
			 
			 float bearing;
			 for (int k = 0; k < vehicleArray.length(); k++) {
					JSONObject vehicleObj = vehicleArray.getJSONObject(k);
					route = vehicleObj.getString("route").substring(6);		 			
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
						tripDescriptor = TripDescriptor.newBuilder();
						tripDescriptor.setRouteId(route);
						Position.Builder position = Position.newBuilder();
						//position.setLatitude((float) lat);
						//position.setLongitude((float) lon);

						FeedEntity.Builder vehiclePositionEntity = FeedEntity
								.newBuilder();
						//int tripID_int = child.getInt("tripId"); 
						String vehicleId = child.getString("VehicleId");	
						if (!routeVehiDirMap.containsKey(route))						
							extractHeading(route);
						vehicleInfo info = tripVehicleInfoMap.get(route, vehicleId);
						bearing = info.bearing;
								//routeVehiDirMap.get(route, vehicleId);	
						position.setBearing(bearing);
						position.setLatitude(info.lat);
						position.setLongitude(info.longi);
						VehiclePosition.Builder vehiclePosition = VehiclePosition.newBuilder();
						vehiclePosition.setPosition(position);
						vehiclePosition.setTrip(tripDescriptor);
						vehicleDescriptor = VehicleDescriptor.newBuilder();
						vehicleDescriptor.setId(vehicleId);
						
						vehicleFeedID ++;

						vehiclePositionEntity.setId(Integer.toString(vehicleFeedID));
						vehiclePosition.setVehicle(vehicleDescriptor);
						vehiclePositionEntity.setVehicle(vehiclePosition);
						
						vehiclePositions.addEntity(vehiclePositionEntity);
						
					}
		 		}
			 _gtfsRealtimeProvider.setVehiclePositions(vehiclePositions.build());
			// _log.info("vehicles' location extracted: " + vehiclePositions.getEntityCount());	
	}
 
	
	private void test_refreshVehicles() throws IOException, JSONException, ParseException {

		String startTime = "";
		 int entity = 0;
		 String[] tripArr = {"1", "2"};
		 
		 int[][][] delayArr = new int [2][2][3];//trip, vehicleID, stopId
		 delayArr[0][0][0] = 2*60;
		 delayArr[0][0][1] = 4*60;
		 delayArr[0][0][2] = 11*60;
		 
		 delayArr[0][1][0] = 12*60;
		 delayArr[0][1][1] = 16*60;
		 delayArr[0][1][2] = 20*60;
 		 
		 delayArr[1][0][0] = 7*60;
		 delayArr[1][0][1] = 9*60;
		 
		 delayArr[1][1][0] = 23*60;
		 delayArr[1][1][1] = 29*60;
		 List<int[]> stopArr = new ArrayList<int[]>();
		 stopArr.add(new int[3]);
		 stopArr.add(new int[2]);
		 //int[][] stopArr = new int [2][3];
		 stopArr.get(0)[0] = 446;
		 stopArr.get(0)[1] = 426;
		 stopArr.get(0)[2] = 401;
		 
		 stopArr.get(1)[0] = 401;
		 stopArr.get(1)[1] = 801;
		  
		 //stopArr[1][2]= 158;
		 
		// stopArr[1][0]= 401;
		// stopArr[1][1]= 801;
		
		 String[] refTime= {"28-May-2014 07:00:00 PDT", "28-May-2014 07:00:00 PDT"};
		 
		 int stopId_int;
		 String[][] vehicleIDs = new String[2][2];
		 vehicleIDs[0][0] = "18";  vehicleIDs[0][1] = "19";
		 vehicleIDs[1][0] = "28";  vehicleIDs[1][1] = "29";
		 VehicleDescriptor.Builder vehicleDescriptor;
		
		 FeedMessage.Builder tripUpdates = GtfsRealtimeLibrary.createFeedMessageBuilder();
		 //String[] tripIDt = {"1", "2"};
		for (int t = 0; t < tripArr.length; t++) {
			System.out.println("----Trip "+ tripArr[t]+ "-------");
			
			for (int v = 0; v < vehicleIDs[t].length; v ++) {
				System.out.println("-------BusId = "+ vehicleIDs[t][v]);
				FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
				TripDescriptor.Builder tripDescriptor = TripDescriptor.newBuilder();
			    //tripID = Integer.toString(entity +1);	 
				tripDescriptor.setTripId(tripArr[t]);
				 
				tripDescriptor.setScheduleRelationship(ScheduleRelationship.UNSCHEDULED);
				vehicleDescriptor = VehicleDescriptor.newBuilder();
				vehicleDescriptor.setId(vehicleIDs[t][v]);
 
				//add the start_times filed
				//startTime = _providerConfig.startTimeByTripIDMap.get(tripArr[i]);
				//tripDescriptor.setStartTime(startTime);
			
				TripUpdate.Builder tripUpdate = TripUpdate.newBuilder();
				tripUpdate.setTrip(tripDescriptor);
				tripUpdate.setVehicle(vehicleDescriptor);
			 
			for (int s = 0; s < stopArr.get(t).length; s+=1){
			
				if((s==1 && t==0 && v ==0)||(s==2 && t==0 && v ==1)){
					
				}else{
			    
				 StopTimeUpdate.Builder stopTimeUpdate = StopTimeUpdate.newBuilder();
				 StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();		
				stopId_int = stopArr.get(t)[s];
				stopTimeUpdate.setStopId(Integer.toString(stopId_int));					 
				//Getting “unixtime”
				//long unixtime2 = Date.parse("01-March-2014 07:00:00").getTime()/1000;
				String pattern = "dd-MMM-yyyy HH:mm:ss z";
				Date date = new SimpleDateFormat(pattern, Locale.ENGLISH).parse(refTime[t]);
//				Date today = new Date();
//				System.out.println("dt ="+ today.getTime()+ ", date = "+ date.getTime()) ;
				//DateTime unixTime = new DateTime(date.getTime());
				//number of seconds that have elapsed since 00:00:00 Coordinated Universal Time (UTC), Thursday, 1 January 1970
				//long unixTime = System.currentTimeMillis() / 1000L;
				long unixTime = date.getTime()/1000;					
				long timePlusDelay = unixTime + delayArr[t][v][s];
				System.out.println("vehicleID= " + vehicleIDs[t][v] + ", stopId ="+ stopId_int + ", stopSeq = "+ (int)(s+1)+", delay(min)= "+ (timePlusDelay-unixTime)/60);
				arrival.setTime(timePlusDelay);
				
				//arrival.setDelay(delayArr[entity][s]);
				//stopTimeUpdate.setStopSequence(s+1);
				stopTimeUpdate.setArrival(arrival);
				tripUpdate.addStopTimeUpdate(stopTimeUpdate);
			}
			}
				entity ++;
				tripUpdateEntity.setId(Integer.toString(entity));
				tripUpdateEntity.setTripUpdate(tripUpdate);
				tripUpdates.addEntity(tripUpdateEntity);
				_gtfsRealtimeProvider.setTripUpdates(tripUpdates.build());
	
				 	
		}			 
	}

		//vehicle position for test
		Pair pair = downloadVehicleDetails();
		JSONArray vehicleArray = pair.getArray2();
		FeedMessage.Builder vehiclePositions = GtfsRealtimeLibrary
						.createFeedMessageBuilder();
		 
		 int vehicleFeedID = 0;
		int routeNumber;
		String routeTitle, trip, route;
		
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
				//int tripID_int = child.getInt("tripId");			
				//String TripID = Integer.toString(tripID_int);
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
		

		 _gtfsRealtimeProvider.setVehiclePositions(vehiclePositions.build());
		
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
		BufferedReader reader = null;
		try{
			reader = new BufferedReader(new InputStreamReader(
				_url.openStream()));
		} catch (Exception ex) {
			_log.error("Error in opening feeds url", ex);
		}
		StringBuilder builder = new StringBuilder();
		String inputLine;
		while ((inputLine = reader.readLine()) != null)
			builder.append(inputLine).append("\n");

		JSONObject object = (JSONObject) new JSONTokener(builder.toString())
				.nextValue();
 
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
				//_log.info("refreshing vehicles");
				refreshTripVehicle();
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
	      default:{
	    	  System.out.println("this dierection is not available : "+ direction);
	    	  _log.error("this dierection is not supported : "+ direction);
	    	  return 0;
	      }
	         
      }
	}
	class vehicleInfo {
	    public float lat;
	    public float longi;
	    public float bearing;
	}
	private void extractHeading (String route) throws IOException, JSONException{
		int routeID = _providerConfig.routesMap.get(route);	
		
		String urlStr = ""+ "http://usfbullrunner.com/route/"+ routeID+"/vehicles";
		JSONArray jsonVehicle = _providerConfig.downloadCofiguration(new URL( urlStr ));
		//System.out.println("routeID = "+ routeID+ " "+ route+  ", url: "+ urlStr+ ", "+ jsonVehicle.length());
		
		for (int i= 0; i < jsonVehicle.length(); i++ ){
			
			JSONObject child = jsonVehicle.getJSONObject(i);
			String heading = child.getString("Heading");
			float direction = getDirVal(heading);
			String vehicleID = child.getString("Name");
			routeVehiDirMap.put(route, vehicleID, direction);
			
			JSONObject coordinate = child.getJSONObject("Coordinate");
			vehicleInfo info = new vehicleInfo();
			info.lat = (float) coordinate.getDouble("Latitude");
			info.longi = (float) coordinate.getDouble("Longitude");
			info.bearing = direction;
			 
			tripVehicleInfoMap.put(route, vehicleID, info);		
			//System.out.println("Name = "+ vehicleID + ", Heading: "+ heading + ", "+ direction);
		}
		
	
	}
	private class stopTimeUpdateRecord implements Comparable<stopTimeUpdateRecord> {
		public StopTimeUpdate.Builder stopTimeUpdate;
		public TripUpdate.Builder tripUpdate;
		public stopTimeUpdateRecord(TripUpdate.Builder t, StopTimeUpdate.Builder s){
			tripUpdate = t;
			stopTimeUpdate = s;
		}
		@Override
		public int compareTo(stopTimeUpdateRecord other) {
			int currentStopSeq = this.stopTimeUpdate.getStopSequence();
			int otherStopSeq = other.stopTimeUpdate.getStopSequence();
		
			 if (currentStopSeq == otherStopSeq)
		            return 0;
		        else if (currentStopSeq > otherStopSeq)
		            return 1;
		        else
		            return -1;
		}
	}

}