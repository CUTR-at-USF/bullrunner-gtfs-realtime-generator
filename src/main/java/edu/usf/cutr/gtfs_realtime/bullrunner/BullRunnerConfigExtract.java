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
	 private static final String path2stopTimesFile = "../myGTFS/stop_times.txt";
	 private static final String path2frequenciesFile = "../myGTFS/frequencies.txt";
	/**
	 * @param url
	 *            the URL for the SEPTA vehicle data API.
	 */
	public void setUrl(URL url) {
		_url = url;
	}

	public HashMap<String, Integer> routesMap = new HashMap<String, Integer>();
	public HashMap<String , String> tripIDMap = new HashMap<String, String>();
	public HashMap<String , String> startTimeByTripIDMap = new HashMap<String, String>();
	public BiHashMap<String, String, String> stopSeqIDMap = new BiHashMap<String, String, String>();
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

		JSONObject object0 = (JSONObject) new JSONTokener(builder.toString())
				.nextValue();
		// JSONObject obj = (JSONObject) JSONValue.parse(builder.toString()); 
	    // JSONArray array = (JSONArray)obj.get("response"); 
	     JSONArray object = (JSONArray) new JSONTokener(builder.toString())
			.nextValue(); 
	        
//		String message = object.getString("ConfigurationDataMessage");
//		JSONObject child_obj = new JSONObject(message);
//		String data = child_obj.getString("ConfigurationData");
//		JSONObject child2_obj = new JSONObject(data);
//
//		JSONArray configArray = child2_obj.getJSONArray("Route");
System.out.println("configArray = "+ object+", size = "+ object.length());
		return object;
	}

	public void generatesRouteMap() throws IOException, JSONException {

		JSONArray configArray = downloadCofiguration();

		for (int i = 0; i < configArray.length(); i++) {

			JSONObject obj = configArray.getJSONObject(i);
            /*
             * "ID":423,
      			"DisplayName":"A Route A",
             */
			int ID = obj.getInt("ID");
			String route = obj.getString("DisplayName").substring(1);
			System.out.println("ID , route"+ ID+ " , "+ route);
			routesMap.put(route, ID);
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

	 /**
	  * Associates the specified value with the specified keys in this map (optional operation). If the map previously
	  * contained a mapping for the key, the old value is replaced by the specified value.
	  * 
	  * @param key1
	  *            the first key
	  * @param key2
	  *            the second key
	  * @param value
	  *            the value to be set
	  */
  
    /**
	 * this function extract the corresponding sequence ID for each stop ID from stop_times.txt in GTFS files
	 * we need tripID and stopID to extract stop sequence 
	 * @throws IOException
	 */
	public void extractSeqId() throws IOException{
		
		String line;
		String[] tokens;
		String delims = "[,]+";
		String stop_id="", trip_id ="", stop_sequence = "";
		//Integer stop_id=0, trip_id =0, stop_sequence = 0;

		BufferedReader stop_times = new BufferedReader(new FileReader(path2stopTimesFile));
		line = stop_times.readLine();
		
		try{
			line = stop_times.readLine();
			 while (line != null ) {
				  
		 	       tokens = line.split(delims);
		 	       trip_id = tokens[0];
		 	       stop_id= tokens[3];
		 	       stop_sequence = tokens[4]; 
		 	     
		 	      stopSeqIDMap.put(trip_id, stop_id, stop_sequence);	

		 	     //System.out.println("tripID = "+ trip_id + ", stopID = " + stop_id + ", stopSeq = "+ stop_sequence);
		 	    line = stop_times.readLine();
		        }
			
		}finally{
			stop_times.close();
		}
		if (stop_sequence.equals(""))
	    	throw new RuntimeException("Cannot find the stop_sequence = " + stop_sequence + ", or stop_id= " + stop_id);

	}
	
   /**
	 * this function extract the corresponding start_time for each trip ID from frequencies.txt in GTFS files
	 * @throws IOException
	 */
	public void extractStartTime() throws IOException{
		
		String line;
		String[] tokens;
		String delims = "[,]+";
		BufferedReader  frequencies= null;
		try{
		  frequencies = new BufferedReader(new FileReader(path2frequenciesFile));
		  line = frequencies.readLine();
		  }catch(IOException e) {
		        System.out.println("error, not able to open" + e);
		}
		String start_time = "";
		String trip_id = "";
		
		try{
			line = frequencies.readLine();
			 while (line != null ) {
				 tokens = line.split(delims);
		 	     trip_id = tokens[0];
		 	     start_time = tokens[1];
		 	    startTimeByTripIDMap.put(trip_id, start_time);
		 	   //System.out.println("tripID = "+ trip_id+ ", startTime = "+ start_time);
		 	  line = frequencies.readLine();
			 }
		}finally{
			frequencies.close();
		}
	}
}