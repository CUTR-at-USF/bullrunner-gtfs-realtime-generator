package edu.usf.cutr.gtfs_realtime.bullrunner;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

 
public class BullRunnerConfigExtract {
	//private URL _url;
	 private static final String path2tripsFile = "../myGTFS/trips.txt";
	 private static final String path2calFile = "../myGTFS/calendar.txt";
	 private static final String path2routeFile = "../myGTFS/routes.txt";
	 private static final String path2stopTimesFile = "../myGTFS/stop_times.txt";
	 private static final String path2frequenciesFile = "../myGTFS/frequencies.txt";
	/**
	 * @param url
	 *            the URL for the SEPTA vehicle data API.
	 */
//	public void setUrl(URL url) {
//		_url = url;
//	}

	public HashMap<String, Integer> routesMap = new HashMap<String, Integer>();
	//public HashMap<Integer , String> serviceIDMap = new HashMap<Integer, String>();
	public String[] serviceIds = new String[7];  
	public BiHashMap<String , String, String> tripIDMap = new BiHashMap<String, String, String>();
	public HashMap<String , String> startTimeByTripIDMap = new HashMap<String, String>();
	public BiHashMap<String, String, String> stopSeqIDMap = new BiHashMap<String, String, String>();
	/**
	 * @return a JSON array parsed from the data pulled from the SEPTA vehicle
	 *         data API.
	 */
	public JSONArray downloadCofiguration(URL _url) throws IOException, JSONException {

		BufferedReader reader = new BufferedReader(new InputStreamReader(
				_url.openStream()));

		StringBuilder builder = new StringBuilder();
		String inputLine;

		while ((inputLine = reader.readLine()) != null)
			builder.append(inputLine).append("\n");
 
	     JSONArray object = (JSONArray) new JSONTokener(builder.toString())
			.nextValue(); 
	     
		return object;
	}
	
	public void generatesRouteMap(URL _url) throws IOException, JSONException {

		JSONArray configArray = downloadCofiguration(_url);

		for (int i = 0; i < configArray.length(); i++) {

			JSONObject obj = configArray.getJSONObject(i);
            /*
             * "ID":423,
      			"DisplayName":"A Route A",
             */
			int ID = obj.getInt("ID");
			String route = obj.getString("DisplayName").substring(0, 1);
			routesMap.put(route, ID);
		}
	}
		
	//extracting the service_id for dayOfWeek
	public void generateServiceMap()throws IOException{
		String splitBy = ",";
		String line;
		BufferedReader servicesBuffer = new BufferedReader(new FileReader(path2calFile));	 
		line = servicesBuffer.readLine(); 	
		while((line = servicesBuffer.readLine())!= null ){
			String[] tokens = line.split(splitBy);
			for (int i = 1; i<= 7; i++){
				int day = Integer.parseInt(tokens[i]);
				if (day == 1){
					if (i != 7)
						serviceIds[i] = tokens[0];
					else
						serviceIds[0] = tokens[0];
				}
			}		 
		}
	}
	public void generateTripMap() throws IOException{
		
		String  line; 
		BufferedReader tripsBuffer = new BufferedReader(new FileReader(path2tripsFile));
			
		String splitBy = ",";
		line = tripsBuffer.readLine(); 	
		while((line = tripsBuffer.readLine())!= null ){
			String [] tripRoute = line.split(splitBy);
			//System.out.println(tripRoute[0]+" , "+ tripRoute[1]+" , "+ tripRoute[2]);
			tripIDMap.put(tripRoute[0], tripRoute[1], tripRoute[2]);
		}   

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
		 	       String preStopSeq = "";
		 	     try {
		 	    	preStopSeq = stopSeqIDMap.get(trip_id, stop_id);
		 	     }catch(NullPointerException e){
		 	    	 
		 	     }    
		 	    if (preStopSeq == null)
		 	    	stopSeqIDMap.put(trip_id, stop_id, stop_sequence);	
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
		 	  line = frequencies.readLine();
			 }
		}finally{
			frequencies.close();
		}
	}
}