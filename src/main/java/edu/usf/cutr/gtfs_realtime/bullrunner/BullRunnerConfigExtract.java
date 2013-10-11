package edu.usf.cutr.gtfs_realtime.bullrunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.HashMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class BullRunnerConfigExtract {
	private URL _url;

	/**
	 * @param url
	 *            the URL for the SEPTA vehicle data API.
	 */
	public void setUrl(URL url) {
		_url = url;
	}

	public HashMap<Integer, String> routesMap = new HashMap<Integer, String>();
	public HashMap<Integer, String> busIDMap = new HashMap<Integer, String>();
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

	public void generatesRouteAndStopsMap() throws IOException, JSONException {

		JSONArray configArray = downloadCofiguration();

		for (int i = 0; i < configArray.length(); i++) {

			JSONObject obj = configArray.getJSONObject(i);

			int key = obj.getInt("key");
			String title = obj.getString("title");
			// StringBuilder sb = new StringBuilder();
			// sb.append("");
			// sb.append(key);
			// String keySt = sb.toString();
			// System.out.println("key = " + keySt);

			routesMap.put(key, title);
		}
	}
}
