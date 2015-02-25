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
import java.io.Reader;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.text.ParsePosition;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Singleton;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.omg.CORBA.portable.InputStream;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporterModule;
import edu.usf.cutr.gtfs_realtime.bullrunner.GtfsRealtimeExporterCutr;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeLibrary;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSink;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFullUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.TripUpdates;
import org.onebusaway.guice.jsr250.JSR250Module;
import org.onebusaway.guice.jsr250.LifecycleService;
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
import com.google.transit.realtime.GtfsRealtime.VehiclePosition.OccupancyStatus;

import java.net.URL;

import com.google.inject.Guice;
import com.google.inject.Inject;
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

	private GtfsRealtimeExporterCutr _gtfsRealtimeProvider;
	private URL _url;
	private BiHashMap<String, String, Float> routeVehiDirMap;
	private BiHashMap<String, String, vehicleInfo> tripVehicleInfoMap;
	private BiHashMap<String, String, StartTimes> routeVehicleStartTimeMap;
	
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


        private GtfsRealtimeSink _vehiclePositionsSink;
	private GtfsRealtimeSink _tripUpdatesSink;

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
		
		routeVehicleStartTimeMap = new BiHashMap<String, String, StartTimes>();
		
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
		 
		if (stopIDsArray.length() == 0) {
			routeVehicleStartTimeMap.clear();
		}
		Calendar cal = Calendar.getInstance();
		int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) -1;
		String serviceID = _providerConfig.serviceIds[dayOfWeek];
 
		GtfsRealtimeFullUpdate tripUpdates = new GtfsRealtimeFullUpdate();		
  		GtfsRealtimeFullUpdate vehiclePositions = new GtfsRealtimeFullUpdate();

		 VehicleDescriptor.Builder vehicleDescriptor = null;
		 String route, trip;
		 int entity = 0;
		 int vehicleFeedID = 0;
		 String stopId = "";
		 String startTime = "";
		 String stopSeq;
		 long predictTime = 0;
		 TripUpdate.Builder tripUpdate = null;
		 TripDescriptor.Builder tripDescriptor = null;
		 
		 
		 List <TripUpdate.Builder> tripUpdateArr = new ArrayList<>();
		 List <stopTimeUpdateRecord> records = new ArrayList<stopTimeUpdateRecord>();
		 routeVehiDirMap = new BiHashMap<String, String, Float>();
		 tripVehicleInfoMap = new BiHashMap<String, String, vehicleInfo>();
		 BiHashMap<String, String, TripUpdate.Builder> tripUpdateMap =  new BiHashMap<String, String, TripUpdate.Builder>();
			 
		 for (int i = 0; i < stopIDsArray.length(); i ++) {
				JSONObject obj = stopIDsArray.getJSONObject(i);
				route = obj.getString("route").substring(6); 			
				trip = _providerConfig.tripIDMap.get(route, serviceID);	
				if (trip.equals("") || trip == null)
					_log.error("Route "+ route+ "dosn't exit in GTFS file");
				int stopId_int = obj.getInt("stop");
				stopId = Integer.toString(stopId_int);
				JSONArray childArray = obj.getJSONArray("Ptimes");			 
				
				for (int j = 0; j < childArray.length(); j++) {
					
					JSONObject child = childArray.getJSONObject(j);
					String predTimeStamp = child.getString("PredictionTime");
					predictTime = convertTime(predTimeStamp);
					String vehicleId = child.getString("VehicleId");
					
					if (!tripUpdateMap.containsKey(route, vehicleId)){
						tripUpdate = TripUpdate.newBuilder();
						vehicleDescriptor = VehicleDescriptor.newBuilder();
						vehicleDescriptor.setId(vehicleId);
						tripDescriptor = TripDescriptor.newBuilder();					
						tripDescriptor.setRouteId(route);
						tripDescriptor.setTripId(trip);
						tripUpdate.setVehicle(vehicleDescriptor);
						tripUpdate.setTrip(tripDescriptor);	
						tripUpdateMap.put(route, vehicleId, tripUpdate);
						tripUpdateArr.add(tripUpdate);					 
					}else{
						tripUpdate = tripUpdateMap.get(route, vehicleId);		
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
						System.out.println("Error: stopID: "+ stopId+ " is not available in GTFS files");
					}
					 
					if (stopSeq.equals("1")){
						startTime = convert2FormattedTime(predTimeStamp);						 
						//System.out.println("stopSeq =1,  route "+ route+ ", vehicleID = "+ vehicleId);
						tripDescriptor.setStartTime(startTime);	
						tripDescriptor.setScheduleRelationship(ScheduleRelationship.UNSCHEDULED);
						tripUpdate.setTrip(tripDescriptor);
						 
						if (routeVehicleStartTimeMap.containsKeys(route, vehicleId)){
							StartTimes startTimes= routeVehicleStartTimeMap.get(route, vehicleId);
							startTimes.previousStartT = startTimes.currentStartT;
							startTimes.currentStartT = startTime;
						} else{
							StartTimes startTInstance = new StartTimes(startTime, "0");
							routeVehicleStartTimeMap.put(route, vehicleId, startTInstance);
						}
						//System.out.println("current starttime = "+ tripUpdate.getTrip().getStartTime());
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
		
		 for (int j = 0; j < tripUpdateArr.size(); j++){
			FeedEntity.Builder tripUpdateEntity = FeedEntity.newBuilder();
			tripUpdate = tripUpdateArr.get(j);
			//System.out.println("-----size of trip Updates = " + tripUpdate.getStopTimeUpdateList().size());
			
			int noStopTimes = tripUpdate.getStopTimeUpdateList().size();
			route = tripUpdate.getTrip().getRouteId();
			trip = tripUpdate.getTrip().getTripId();
			String vehicleId = tripUpdate.getVehicle().getId();
			 
			if (tripUpdate.getStopTimeUpdate(0).getStopSequence() != 1){
				StartTimes startTInstance;
				if (routeVehicleStartTimeMap.containsKeys(route, vehicleId)){
					startTInstance = routeVehicleStartTimeMap.get(route, vehicleId);
				} else {
					//cold start
					startTInstance = new StartTimes("0", "0");	 
					routeVehicleStartTimeMap.put(route, vehicleId, startTInstance);
				}

				TripDescriptor.Builder newTripDescriptor = TripDescriptor.newBuilder();
				newTripDescriptor.setTripId(trip);
				newTripDescriptor.setRouteId(route);
				newTripDescriptor.setStartTime(startTInstance.currentStartT);
				newTripDescriptor.setScheduleRelationship(ScheduleRelationship.UNSCHEDULED);
				tripUpdate.setTrip(newTripDescriptor);

				}
//			System.out.println("responseTimeStamp: "+ responseTimeStamp + "change it to unix: "+ responseTimeStamp); 
			int delay;
			long preTime = 0;
			for(int h = 0; h < noStopTimes; h++){
				long timeStamp = tripUpdate.getStopTimeUpdate(h).getArrival().getTime();
				if (timeStamp < preTime){
					delay = calcDelayTime(timeStamp );
					if ( 60 < delay  ){
						 
						StopTimeEvent.Builder arrival = StopTimeEvent.newBuilder();
						arrival.setTime(timeStamp);
						
						StopTimeUpdate preStopTime = tripUpdate.getStopTimeUpdate(h-1);
						StopTimeUpdate.Builder newStopTimeUpdate = StopTimeUpdate.newBuilder(preStopTime);
						newStopTimeUpdate.setArrival(arrival);
						tripUpdate.setStopTimeUpdate(h-1, newStopTimeUpdate);
						preTime = timeStamp;
//						System.out.println("** h = "+ h+ " is "+ timeStamp+ " routeID: "+tripUpdate.getTrip().getRouteId()+ " vehicle: "+tripUpdate.getVehicle().getId());
//						System.out.println("\t DELAY:"+ delay + ", stop time of h-1 change to "+ tripUpdate.getStopTimeUpdate(h-1).getArrival().getTime()+ " from "+ preTime);
						
					} else {
//						System.out.println(" h = " + h+" routeID: "+tripUpdate.getTrip().getRouteId()+ " vehicle: "+tripUpdate.getVehicle().getId());
						
						List <StopTimeUpdate> allStopUpdates = tripUpdate.getStopTimeUpdateList();
						tripUpdate.clearStopTimeUpdate();
						TripUpdate.Builder newTripUpdate = tripUpdate.clone();
						 
						// we have to send out the old tripUpdate, but before that the rest of stopTimes should be deleted from it		
						newTripUpdate.addAllStopTimeUpdate(allStopUpdates.subList(0, h));
						entity ++; 
						tripUpdateEntity.setId(Integer.toString(entity));
						tripUpdateEntity.setTripUpdate(newTripUpdate);
						tripUpdates.addEntity(tripUpdateEntity.build());
						//System.out.println("what has been sent: size = "+ newTripUpdate.getStopTimeUpdateList().size()+ " stoptime");
						
						tripUpdate.addAllStopTimeUpdate(allStopUpdates.subList(h, noStopTimes));
						//System.out.println("current tripUpdate size = "+ tripUpdate.getStopTimeUpdateList().size()+ " stoptime");
						preTime = 0;
						noStopTimes = noStopTimes - h;
						h = -1; 
						StartTimes startTimes;
						if (routeVehicleStartTimeMap.containsKeys(route, vehicleId))
							startTimes = routeVehicleStartTimeMap.get(route, vehicleId);
						else{
							startTimes = new StartTimes(startTime, "0");						 
							routeVehicleStartTimeMap.put(route, vehicleId, startTimes);
						}
						 
						String previousStartT = startTimes.previousStartT; 
						TripDescriptor.Builder newTripDescriptor = TripDescriptor.newBuilder();
						newTripDescriptor.setTripId(trip);
						newTripDescriptor.setRouteId(route);
						newTripDescriptor.setStartTime(previousStartT);
						newTripDescriptor.setScheduleRelationship(ScheduleRelationship.UNSCHEDULED);
						tripUpdate.setTrip(newTripDescriptor);
						//System.out.println("second startTime = "+ tripUpdate.getTrip().getStartTime()+ "  &&&& first startTime = "+ newTripUpdate.getTrip().getStartTime());
					}
				}
				else
					preTime = timeStamp;
			}
			 
			entity ++;
			 
			tripUpdateEntity.setId(Integer.toString(entity));
			tripUpdateEntity.setTripUpdate(tripUpdate);
			tripUpdates.addEntity(tripUpdateEntity.build());
		 }
		 _tripUpdatesSink.handleFullUpdate(tripUpdates);
		  
			 _log.info("stoIDs extracted: " + tripUpdates.getEntities().size());
			// System.out.println("stoIDs extracted: " + tripUpdates.getEntityCount());
			
			 
			 
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
						
						//_log.info("vehicles' info: " + info); 
						bearing = info.bearing;
						
								//routeVehiDirMap.get(route, vehicleId);	
						position.setBearing(bearing);
						position.setLatitude(info.lat);
						position.setLongitude(info.longi);
						VehiclePosition.Builder vehiclePosition = VehiclePosition.newBuilder();
						vehiclePosition.setPosition(position);
						vehiclePosition.setTrip(tripDescriptor);

						if (info.APCPercentage <= 0) vehiclePosition.setOccupancyStatus( VehiclePosition.OccupancyStatus.EMPTY );
						else if (info.APCPercentage >= 95) vehiclePosition.setOccupancyStatus( VehiclePosition.OccupancyStatus.FULL );
						else if (info.APCPercentage <= 50) vehiclePosition.setOccupancyStatus( VehiclePosition.OccupancyStatus.MANY_SEATS_AVAILABLE );
						else vehiclePosition.setOccupancyStatus( VehiclePosition.OccupancyStatus.FEW_SEATS_AVAILABLE );

						vehicleDescriptor = VehicleDescriptor.newBuilder();
						vehicleDescriptor.setId(vehicleId);
						
						vehicleFeedID ++;

						vehiclePositionEntity.setId(Integer.toString(vehicleFeedID));
						vehiclePosition.setVehicle(vehicleDescriptor);
						vehiclePositionEntity.setVehicle(vehiclePosition);
						
						vehiclePositions.addEntity(vehiclePositionEntity.build());
						
					}
		 		}
			 _vehiclePositionsSink.handleFullUpdate(vehiclePositions);
			 _log.info("vehicles' location extracted: " + vehiclePositions.getEntities().size());	
			 //System.out.println("vehicles' location extracted: " + vehiclePositions.getEntityCount());
	}
 
 
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
		URLConnection connection = null;
		try {
		    connection = _url.openConnection();
		  } catch (Exception ex) {
		    _log.error("Error in opening feeds url", ex);
		  }
		  
		  connection.setConnectTimeout(10000);  // connectTimeout is time out in miliseconds
		  connection.setReadTimeout(10000);
		  java.io.InputStream in =  connection.getInputStream();

		  BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));

		  StringBuilder builder = new StringBuilder();
		  String inputLine;
		  JSONArray stopIDsArray;
		  JSONArray vehicleArray;
		  try {
		    while ((inputLine = reader.readLine()) != null)
		      builder.append(inputLine).append("\n");

		    JSONObject object = (JSONObject) new JSONTokener(builder.toString())
		            .nextValue();

		    String data = object.getString("PredictionData");
		    JSONObject child2_obj = new JSONObject(data);
		    responseTimeStamp = child2_obj.getString("TimeStamp");
		     stopIDsArray = child2_obj.getJSONArray("StopPredictions");
		     vehicleArray = child2_obj.getJSONArray("VehicleLocationData");

		  } catch (java.net.SocketTimeoutException ex) {
			  _log.error("Error readline, server dosn't close the connection.", ex);
		    stopIDsArray = null;
		    vehicleArray = null;
		  }
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
				refreshTripVehicle();
				//test_refreshVehicles();
			} catch (Exception ex) {
				_log.warn("Error in vehicle refresh task", ex);
			}
		}
	}
	private int calcDelayTime(long arrivalTime){
		int diff;
		String pattern = "yyyy-MM-dd'T'HH:mm:ssZ";
		DateTimeFormatter dtf = DateTimeFormat.forPattern(pattern);
		DateTime parsedDate = dtf.parseDateTime(responseTimeStamp);	 
		diff = (int) (arrivalTime - parsedDate.getMillis()/1000);
		return diff;
	}
	private String convert2FormattedTime(String myTimeStamp){
		
		   DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ssZ");
		   DateTime jodatime = dtf.parseDateTime(myTimeStamp);
		   DateTimeFormatter dtfOut = DateTimeFormat.forPattern("HH:mm:ss"); 
		   return dtfOut.print(jodatime);
	   }
   
	// This method extract time from timestamp
	private long convertTime(String myTimeStamp) {

		//final SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ssXXX");
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"){ 
		    public Date parse(String source,ParsePosition pos) {    
		        return super.parse(source.replaceFirst(":(?=[0-9]{2}$)",""),pos);
		    }
		};
		Date time;// = new Date();
		long result = 0;
		try {
			//dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
			//dateFormat.setTimeZone(dateFormat.getTimeZone());
			time = dateFormat.parse(myTimeStamp);
			result = time.getTime()/1000; 
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
	    public int APCPercentage;
	}
	private void extractHeading (String route) throws IOException, JSONException{
		int routeID = _providerConfig.routesMap.get(route);	
	
		String urlStr = ""+ "http://usfbullrunner.com/route/"+ routeID+"/vehicles";
		JSONArray jsonVehicle = _providerConfig.downloadCofiguration(new URL( urlStr ));
		
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

			info.APCPercentage = child.getInt("APCPercentage");
			 
			tripVehicleInfoMap.put(route, vehicleID, info);		 
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
    
	private class StartTimes{
		public String currentStartT;
		public String previousStartT;
		//constructor
		public StartTimes(String currentStartT, String previousStartT){
			this.currentStartT = currentStartT;
			this.previousStartT = previousStartT;
		}
	}
}
