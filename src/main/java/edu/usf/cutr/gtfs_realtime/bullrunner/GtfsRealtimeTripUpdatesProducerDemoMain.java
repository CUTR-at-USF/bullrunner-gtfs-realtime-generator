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

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Parser;
import org.json.JSONException;
import org.onebusaway.cli.CommandLineInterfaceLibrary;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFileWriter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeServlet;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeSource;

import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.VehiclePositions;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeGuiceBindingTypes.TripUpdates;
import org.onebusaway.guice.jsr250.JSR250Module;
import org.onebusaway.guice.jsr250.LifecycleService;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Module;

public class GtfsRealtimeTripUpdatesProducerDemoMain {

	private static final String ARG_TRIP_UPDATES_PATH = "tripUpdatesPath";

	private static final String ARG_TRIP_UPDATES_URL = "tripUpdatesUrl";

	private static final String ARG_VEHICLE_POSITIONS_PATH = "vehiclePositionsPath";

	private static final String ARG_VEHICLE_POSITIONS_URL = "vehiclePositionsUrl";

	public static void main(String[] args) throws Exception {
		GtfsRealtimeTripUpdatesProducerDemoMain m = new GtfsRealtimeTripUpdatesProducerDemoMain();
		m.run(args);
	}

	private GtfsRealtimeProviderImpl _provider;
//	private BullRunnerConfigExtract _providerConfig;
	private LifecycleService _lifecycleService;
//	@Inject
//	public void setProvider(BullRunnerConfigExtract providerConfig) {
//		_providerConfig = providerConfig;
//	}

	private GtfsRealtimeSource _tripUpdates;
 	private GtfsRealtimeSource _vehiclePositions;

/*
	@Inject
	public void setVehiclePositionsProducer(VehiclePositionsProducer producer) {
	    // This is just here to make sure VehiclePositionsProducer gets instantiated.
	}
*/
  
	@Inject
	public void setVehiclePositionsSource(@VehiclePositions GtfsRealtimeSource vehiclePositionsSource) {
	    _vehiclePositions = vehiclePositionsSource;
   	}
 
/* 
        @Inject
        public void setTripUpdatesProducer(TripUpdatesProducer producer) {
            // This is just here to make sure TripUpdatesProducer gets instantiated.
        }
*/

        @Inject
        public void setTripUpdatesSource(@TripUpdates GtfsRealtimeSource tripUpdatesSource) {
            _tripUpdates = tripUpdatesSource;
        }

	@Inject
	public void setProvider(GtfsRealtimeProviderImpl provider) {
		_provider = provider;
	}


	@Inject
	public void setLifecycleService(LifecycleService lifecycleService) {
		_lifecycleService = lifecycleService;
	}

	public void run(String[] args) throws Exception {

		if (args.length == 0 || CommandLineInterfaceLibrary.wantsHelp(args)) {
			printUsage();
			System.exit(-1);
		}

		Options options = new Options();
		buildOptions(options);
		Parser parser = new GnuParser();
		CommandLine cli = parser.parse(options, args);

		Set<Module> modules = new HashSet<Module>();
		GtfsRealtimeTripUpdatesProducerDemoModule
				.addModuleAndDependencies(modules);

		Injector injector = Guice.createInjector(modules);
		injector.injectMembers(this);

		 
		 
		_provider.setUrl(new URL( "http://api.syncromatics.com/feed/511/Prediction/?api_key=593e3f10de49d7fec7c8ace98f0ee6d1&format=json"));
		//only for test, creat a static json for 8:32pm, August 5th, 2014
	    //_provider.setUrl(new URL( "http://myweb.usf.edu/~mona2/syncromticOffLine_8_32August5.json"));
			

		if (cli.hasOption(ARG_TRIP_UPDATES_URL)) {
			URL url = new URL(cli.getOptionValue(ARG_TRIP_UPDATES_URL));
			GtfsRealtimeServlet servlet = injector.getInstance(GtfsRealtimeServlet.class);
			servlet.setSource(_tripUpdates);
			servlet.setUrl(url);
		}
		if (cli.hasOption(ARG_TRIP_UPDATES_PATH)) {
			File path = new File(cli.getOptionValue(ARG_TRIP_UPDATES_PATH));

			GtfsRealtimeFileWriter writer = injector.getInstance(GtfsRealtimeFileWriter.class);
			writer.setSource(_tripUpdates);
			writer.setPath(path);
		}

		if (cli.hasOption(ARG_VEHICLE_POSITIONS_URL)) {
			URL url = new URL(cli.getOptionValue(ARG_VEHICLE_POSITIONS_URL));

			GtfsRealtimeServlet servlet = injector.getInstance(GtfsRealtimeServlet.class);
			servlet.setSource(_vehiclePositions);
			servlet.setUrl(url);
		}
		if (cli.hasOption(ARG_VEHICLE_POSITIONS_PATH)) {
			File path = new File(cli.getOptionValue(ARG_VEHICLE_POSITIONS_PATH));
		 	GtfsRealtimeFileWriter writer = injector.getInstance(GtfsRealtimeFileWriter.class);
			writer.setSource(_vehiclePositions);
			writer.setPath(path);
		}

		_lifecycleService.start();
	}

	private void printUsage() {
		CommandLineInterfaceLibrary.printUsage(getClass());
	}

	protected void buildOptions(Options options) {
		options.addOption(ARG_TRIP_UPDATES_PATH, true, "trip updates path");
		options.addOption(ARG_TRIP_UPDATES_URL, true, "trip updates url");
		options.addOption(ARG_VEHICLE_POSITIONS_PATH, true,
				"vehicle positions path");
		options.addOption(ARG_VEHICLE_POSITIONS_URL, true,
				"vehicle positions url");

	}
}
