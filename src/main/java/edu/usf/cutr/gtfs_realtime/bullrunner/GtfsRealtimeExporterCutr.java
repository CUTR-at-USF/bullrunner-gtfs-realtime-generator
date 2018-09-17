package edu.usf.cutr.gtfs_realtime.bullrunner;

import com.google.transit.realtime.GtfsRealtime.FeedEntity;
import com.google.transit.realtime.GtfsRealtime.FeedHeader;
import com.google.transit.realtime.GtfsRealtime.FeedHeader.Incrementality;
import com.google.transit.realtime.GtfsRealtime.FeedMessage;
import com.google.transit.realtime.GtfsRealtimeConstants;
import com.google.transit.realtime.GtfsRealtimeOneBusAway;
import com.google.transit.realtime.GtfsRealtimeOneBusAway.OneBusAwayFeedHeader;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporter.AlertsExporter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporter.MixedFeedExporter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporter.TripUpdatesExporter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeExporter.VehiclePositionsExporter;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeFullUpdate;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeIncrementalListener;
import org.onebusaway.gtfs_realtime.exporter.GtfsRealtimeIncrementalUpdate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Private implementation of {@link GtfsRealtimeExporter}.
 * 
 * @author bdferris
 */
class GtfsRealtimeExporterCutr implements AlertsExporter, TripUpdatesExporter,
    VehiclePositionsExporter, MixedFeedExporter {

  private List<GtfsRealtimeIncrementalListener> _listeners = new CopyOnWriteArrayList<GtfsRealtimeIncrementalListener>();

  private FeedHeader _header;

  private Map<String, FeedEntity> _feedEntities = new HashMap<String, FeedEntity>();

  private FeedMessage _cachedFeed = null;

  private long _incrementalIndex = 1;

  private int _incrementalHeartbeatInterval = 60;

  /****
   * {@link GtfsRealtimeSink} Interface
   ****/

  @Override
  public synchronized void setFeedHeaderDefaults(FeedHeader header) {
    _header = header;
    _cachedFeed = null;
  }

  @Override
  public synchronized void handleFullUpdate(GtfsRealtimeFullUpdate update) {
    _cachedFeed = null;
    _feedEntities.clear();
    for (FeedEntity entity : update.getEntities()) {
      _feedEntities.put(entity.getId(), entity);
    }
    _incrementalIndex++;
    FeedMessage feed = getFeed();
    for (GtfsRealtimeIncrementalListener listener : _listeners) {
      listener.handleFeed(feed);
    }
  }

  @Override
  public synchronized void handleIncrementalUpdate(
      GtfsRealtimeIncrementalUpdate update) {
    _cachedFeed = null;

    for (FeedEntity toAdd : update.getUpdatedEntities()) {
      _feedEntities.put(toAdd.getId(), toAdd);
    }
    for (String toRemove : update.getDeletedEntities()) {
      _feedEntities.remove(toRemove);
    }

    FeedMessage.Builder feed = FeedMessage.newBuilder();
    feed.setHeader(createIncrementalHeader());
    feed.addAllEntity(update.getUpdatedEntities());
    for (String toRemove : update.getDeletedEntities()) {
      FeedEntity.Builder entity = FeedEntity.newBuilder();
      entity.setIsDeleted(true);
      entity.setId(toRemove);
      feed.addEntity(entity);
    }

    FeedMessage differentialFeed = feed.build();
    for (GtfsRealtimeIncrementalListener listener : _listeners) {
      listener.handleFeed(differentialFeed);
    }
    _incrementalIndex++;
  }

  /****
   * {@link GtfsRealtimeSource} Interface
   ****/

  @Override
  public synchronized FeedMessage getFeed() {
    if (_cachedFeed == null) {
      FeedHeader.Builder header = FeedHeader.newBuilder();
      if (_header != null) {
        header.mergeFrom(_header);
      }
      header.setIncrementality(Incrementality.FULL_DATASET);
      header.setTimestamp(System.currentTimeMillis() / 1000);
      header.setGtfsRealtimeVersion(GtfsRealtimeConstants.VERSION);

      setIncrementalIndex(header, _incrementalIndex - 1);

      FeedMessage.Builder feed = FeedMessage.newBuilder();
      feed.setHeader(header);
      feed.addAllEntity(_feedEntities.values());
      _cachedFeed = feed.build();
    }
    return _cachedFeed;
  }

  @Override
  public void addIncrementalListener(GtfsRealtimeIncrementalListener listener) {
    _listeners.add(listener);
    listener.handleFeed(getFeed());
  }

  @Override
  public void removeIncrementalListener(GtfsRealtimeIncrementalListener listener) {
    _listeners.remove(listener);
  }

  /***
   * Private Methods
   ****/

  private FeedHeader createIncrementalHeader() {
    FeedHeader.Builder header = FeedHeader.newBuilder();
    if (_header != null) {
      header.mergeFrom(_header);
    }
    header.setIncrementality(Incrementality.DIFFERENTIAL);
    header.setTimestamp(System.currentTimeMillis() / 1000);
    header.setGtfsRealtimeVersion(GtfsRealtimeConstants.VERSION);

    setIncrementalIndex(header, _incrementalIndex);

    return header.build();
  }

  private void setIncrementalIndex(FeedHeader.Builder header,
      long incrementalIndex) {
    OneBusAwayFeedHeader.Builder obaHeader = OneBusAwayFeedHeader.newBuilder();
    obaHeader.setIncrementalIndex(incrementalIndex);
    obaHeader.setIncrementalHeartbeatInterval(_incrementalHeartbeatInterval);
    header.setExtension(GtfsRealtimeOneBusAway.obaFeedHeader, obaHeader.build());
  }
}
