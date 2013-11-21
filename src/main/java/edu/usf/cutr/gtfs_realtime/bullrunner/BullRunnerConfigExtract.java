package edu.usf.cutr.gtfs_realtime.bullrunner;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
 
public class BullRunnerConfigExtract {
	private URL _url;
	 private static final String path2tripsFile = "../myGTFS/trips.txt";
	 private static final String path2calFile = "../myGTFS/calendar.txt";
	/**
	 * @param url
	 *            the URL for the SEPTA vehicle data API.
	 */
	public void setUrl(URL url) {
		_url = url;
	}

	public HashMap<Integer, String> routesMap = new HashMap<Integer, String>();
	public HashMap<String , String> tripIDMap = new HashMap<String, String>();
	/**
	 * @return a JSON array parsed from the data pulled from the SEPTA vehicle
	 *         data API.
	 */
	private JSONArray downloadCofiguration() throws IOException, JSONException {

		BufferedReader reader = new BufferedReader(new InputStreamReader(
				_url.openStream()));

		StringBuilder builder = new StringBuilder();
		String inputLine;

		while ((inputLine = reader.readLine()) != null)
			builder.append(inputLine).append("\n");

		JSONObject object = (JSONObject) new JSONTokener(builder.toString())
				.nextValue();

		String message = object.getString("ConfigurationDataMessage");
		JSONObject child_obj = new JSONObject(message);

		String data = child_obj.getString("ConfigurationData");
		JSONObject child2_obj = new JSONObject(data);

		JSONArray configArray = child2_obj.getJSONArray("Route");

		return configArray;
	}

	public void generatesRouteMap() throws IOException, JSONException {

		JSONArray configArray = downloadCofiguration();

		for (int i = 0; i < configArray.length(); i++) {

			JSONObject obj = configArray.getJSONObject(i);

			int key = obj.getInt("key");
			String title = obj.getString("title");
			// StringBuilder sb = new StringBuilder();
			// sb.append("");
			// sb.append(key);
			// String keySt = sb.toString();
			routesMap.put(key, title);
		}
	}
	
	
	/**
	 * create hash map of routeID ===> tripID 
	 * for this it needs the path to gtfs/trip.txt and also serviceID which again use gtfs/calendar.txt
	 */
	public void generateTripMap() throws IOException{
		
 
		
		String route_id="", line, trip_id ="", service_id = "";
				
		int DAY_OF_WEEK, order_DayOfWeek, notFound = 1;
		String[] tokens;
		String delims = "[,]+";
		
		Calendar cal = Calendar.getInstance();
		 DAY_OF_WEEK = cal.get(Calendar.DAY_OF_WEEK) -1;
		//System.out.println(DAY_OF_WEEK);
		
		 //extracting the service_id for today!
		BufferedReader  tripCal = new BufferedReader(new FileReader(path2calFile));
	    try {

	        line = tripCal.readLine();
	        
	        while (line != null && notFound == 1) {
	            line = tripCal.readLine();	            
	            tokens = line.split(delims);
	            if (DAY_OF_WEEK != 0 )
	            	order_DayOfWeek = DAY_OF_WEEK%7;
	            else
	            	order_DayOfWeek = 7;
	            if (tokens[order_DayOfWeek].equals("1")){
	            	notFound = 0;
	            	service_id = tokens[0];
	            
	            }
	            	
	        }
	    } finally {
	    	tripCal.close();
	    }
		  
		 
	    // extracting the trip_id
	    notFound = 1;
	    BufferedReader trips = new BufferedReader(new FileReader(path2tripsFile));
	    //it is just the headers, and we throw it away
	    line = trips.readLine();
	    try {
 
	        line = trips.readLine();
 
	        while (line != null ) {
	        	
	 	        tokens = line.split(delims);
	        	route_id = tokens[0];

	        	if (tokens[1].equals(service_id)){
	        		trip_id = tokens[2];
	        		tripIDMap.put(route_id, trip_id);
	        		//System.out.println("route_id = "+route_id + "trip_id = " + trip_id);
	        	}
	        	line = trips.readLine();
	        }
	     
	    } finally {
	    	trips.close();
	    }
	    if (trip_id.equals(""))
	    	throw new RuntimeException("Cannot find the route_id = " + route_id + ", or Service_id= " + service_id);

	}


}
