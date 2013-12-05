/**
 * Radius Networks, Inc.
 * http://www.radiusnetworks.com
 * 
 * @author David G. Young
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.radiusnetworks.ibeacon.service;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.radiusnetworks.ibeacon.IBeacon;
import com.radiusnetworks.ibeacon.Region;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

/**
 * Issues:
 * 1. If two apps register ranges with the same id, they clobber eachother.  
 * 2. If an app goes away after staring monitoring or ranging, we will continue to make callbacks from the service
 * 3. Is sending so many intents efficient?
 * @author dyoung
 *
 * Differences from Apple's SDK:
 * 1. You can wildcard all fields in a region to get updates about ANY iBeacon
 * 2. Ranging updates don't come as reliably every second.
 * 3. The distance measurement algorithm is not exactly the same
 * 4. You can do ranging when the app is not in the foreground
 * 5. It requires Bluetooth Admin privileges
 */

public class IBeaconService extends Service {
	public static final String TAG = "IBeaconService";

	private Map<Region, RangeState> rangedRegionState = new HashMap<Region,RangeState>();
	private Map<Region, MonitorState> monitoredRegionState = new HashMap<Region,MonitorState>();
	private BluetoothAdapter bluetoothAdapter;
    private boolean scanning;
    private boolean scanningPaused;
    private Date lastIBeaconDetectionTime = new Date();
    private HashSet<IBeacon> trackedBeacons;
    private Handler handler = new Handler();
    private int bindCount = 0;
    /*
     * The scan period is how long we wait between restarting the BLE advertisement scans
     * Each time we restart we only see the unique advertisements once (e.g. unique iBeacons)
     * So if we want updates, we have to restart.  iOS gets updates once per second, so ideally we
     * would restart scanning that often to get the same update rate.  The trouble is that when you 
     * restart scanning, it is not instantaneous, and you lose any iBeacon packets that were in the 
     * air during the restart.  So the more frequently you restart, the more packets you lose.  The
     * frequency is therefore a tradeoff.  Testing with 14 iBeacons, transmitting once per second,
     * here are the counts I got for various values of the SCAN_PERIOD:
     * 
     * Scan period     Avg iBeacons      % missed
     *    1s               6                 57
     *    2s               10                29
     *    3s               12                14
     *    5s               14                0
     *    
     * Also, because iBeacons transmit once per second, the scan period should not be an even multiple
     * of seconds, because then it may always miss a beacon that is synchronized with when it is stopping
     * scanning.
     * 
     */
    private static final long SCAN_PERIOD = 1100;
    private static final long BACKGROUND_SCAN_PERIOD = 30000;
    private static final long BACKGROUND_BETWEEN_SCAN_PERIOD = 5*60*1000;

    private List<IBeacon> simulatedScanData = null;
    
    /**
     * Class used for the client Binder.  Because we know this service always
     * runs in the same process as its clients, we don't need to deal with IPC.
     */
    public class IBeaconBinder extends Binder {
        public IBeaconService getService() {
        	Log.i(TAG, "getService of IBeaconBinder called");        	
            // Return this instance of LocalService so clients can call public methods
            return IBeaconService.this;
        }
    }


    /** Command to the service to display a message */
    public static final int MSG_START_RANGING = 2;
    public static final int MSG_STOP_RANGING = 3;
    public static final int MSG_START_MONITORING = 4;
    public static final int MSG_STOP_MONITORING = 5;
    

    static class IncomingHandler extends Handler {
        private final WeakReference<IBeaconService> mService; 

        IncomingHandler(IBeaconService service) {
            mService = new WeakReference<IBeaconService>(service);
        }
        @Override
        public void handleMessage(Message msg)
        {
             IBeaconService service = mService.get();
             StartRMData startRMData = (StartRMData) msg.obj;

             if (service != null) {
                 switch (msg.what) {
                 case MSG_START_RANGING:
                 	Log.d(TAG, "start ranging received");
                 	service.startRangingBeaconsInRegion(startRMData.getRegionData(), new com.radiusnetworks.ibeacon.service.Callback(msg.replyTo, startRMData.getIntentActionForCallback()));                	
                    break;
                 case MSG_STOP_RANGING:
                 	Log.d(TAG, "stop ranging received");
                 	service.stopRangingBeaconsInRegion(startRMData.getRegionData());
                 	break;
                 case MSG_START_MONITORING:
                 	Log.d(TAG, "start monitoring received");
                 	service.startMonitoringBeaconsInRegion(startRMData.getRegionData(), new com.radiusnetworks.ibeacon.service.Callback(msg.replyTo, startRMData.getIntentActionForCallback()));
                 	break;
                 case MSG_STOP_MONITORING:
                 	Log.d(TAG, "stop monitoring received");
                 	service.stopMonitoringBeaconsInRegion(startRMData.getRegionData());
                 	break;

                 default:
                     super.handleMessage(msg);
                 }
             }
        }
    }
    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Messenger mMessenger = new Messenger(new IncomingHandler(this));

    /**
     * When binding to the service, we return an interface to our messenger
     * for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        Log.i(TAG, "binding");
        bindCount++;
        return mMessenger.getBinder();
    }   
    @Override
    public boolean onUnbind (Intent intent) {
    	Log.i(TAG, "unbind called");
    	bindCount--;
		return false;    	
    }
    
    
    @Override
    public void onCreate() {
    	Log.i(TAG, "onCreate of IBeaconService called");
		// Initializes Bluetooth adapter.
		final BluetoothManager bluetoothManager =
		        (BluetoothManager) this.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
		bluetoothAdapter = bluetoothManager.getAdapter();    	
		
		// Look for simulated scan data
		try {
			Class klass = Class.forName("com.radiusnetworks.ibeacon.SimulatedScanData");
			java.lang.reflect.Field f = klass.getField("iBeacons");
			this.simulatedScanData = (List<IBeacon>) f.get(null);			
		} catch (ClassNotFoundException e) {
			Log.d(TAG, "No com.radiusnetworks.ibeacon.SimulatedScanData class exists.");
		} catch (Exception e) {
			Log.e(TAG, "Cannot get simulated Scan data.  Make sure your com.radiusnetworks.ibeacon.SimulatedScanData class defines a field with the signature 'public static List<IBeacon> iBeacons'", e);
		}
    }
    @Override
    public void onDestroy() {
    	Log.i(TAG, "onDestory called.  stopping scanning");
    	scanLeDevice(false);
    	if (bluetoothAdapter != null) {
        	bluetoothAdapter.stopLeScan(leScanCallback);    		
    	}
    }
    
    private int ongoing_notification_id = 1;
    
    public void runInForeground(Class<? extends Activity> klass) {
    			
    	Intent notificationIntent = new Intent(this, klass);
    	PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
    	Notification notification = new Notification.Builder(this.getApplicationContext())
        .setContentTitle("Scanning for iBeacons")
        .setSmallIcon(android.R.drawable.star_on)
        .addAction(android.R.drawable.star_off, "this is the other title", pendingIntent)
        .build();
    	startForeground(ongoing_notification_id++, notification);
    }

    
    
    /* 
     * Returns true if the service is running, but no bound clients exist
     */
    private boolean isInBackground() {
    	Log.d(TAG, "bound client count:"+bindCount);
    	return bindCount == 0;
    }

    /** methods for clients */
				
	// TODO: make it so that regions between apps do not collide
	public void startRangingBeaconsInRegion(Region region, Callback callback) {
		if (rangedRegionState.containsKey(region)) {
			Log.d(TAG, "Already ranging that region -- will replace existing region.");
			rangedRegionState.remove(region); // need to remove it, otherwise the old object will be retained because they are .equal
		}
		rangedRegionState.put(region, new RangeState(callback));
		if (!scanning) {
		    scanLeDevice(true); 					
		}
	}
	public void stopRangingBeaconsInRegion(Region region) {
		rangedRegionState.remove(region);
		if (scanning && rangedRegionState.size() == 0 && monitoredRegionState.size() == 0) {
			scanLeDevice(false); 							
		}
	}
	public void startMonitoringBeaconsInRegion(Region region, Callback callback) {
		Log.d(TAG, "startMonitoring called");
		if (monitoredRegionState.containsKey(region)) {
			Log.d(TAG, "Already monitoring that region -- will replace existing region monitor.");
			monitoredRegionState.remove(region); // need to remove it, otherwise the old object will be retained because they are .equal
		}
		monitoredRegionState.put(region,  new MonitorState(callback));
		Log.d(TAG, "Currently monitoring "+monitoredRegionState.size()+" regions.");			
		if (!scanning) {
		    scanLeDevice(true); 					
		}
		
	}
	public void stopMonitoringBeaconsInRegion(Region region) {
		Log.d(TAG, "stopMonitoring called");
		monitoredRegionState.remove(region);
		Log.d(TAG, "Currently monitoring "+monitoredRegionState.size()+" regions.");
		if (scanning && rangedRegionState.size() == 0 && monitoredRegionState.size() == 0) {
			scanLeDevice(false); 							
		}		
	}

    private void scanLeDevice(final Boolean enable) {
    	if (bluetoothAdapter == null) {
    		Log.e(TAG, "no bluetooth adapter.  I cannot scan.");
    		if (simulatedScanData == null) {
    			Log.w(TAG, "exiting");
    			return;
    		}
    		else {
    			Log.w(TAG, "proceeding with simulated scan data");
    		}
    	}
        if (enable) {
            // Stops scanning after a pre-defined scan period.
        	
        	long scanPeriod = SCAN_PERIOD;
        	if (isInBackground()) {
        		scanPeriod = BACKGROUND_SCAN_PERIOD;
        	}
        	
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "Done with scan cycle");
                	if (scanning == true ) {
                        processRangeData();
                		Log.d(TAG, "Restarting scan.  Unique beacons seen last cycle: "+trackedBeacons.size());
                    	if (bluetoothAdapter != null) {
                    		if (bluetoothAdapter.isEnabled()) {
                        		bluetoothAdapter.stopLeScan(leScanCallback);                    		                    			
                    		}
                    		else {
                    			Log.w(TAG, "Bluetooth is disabled.  Cannot scan for iBeacons.");
                    		}
                    	}

                        scanningPaused = true;                        
                        // If we want to use simulated scanning data, do it here.  This is used for testing in an emulator
                        if (simulatedScanData != null) {
                        	// if simulatedScanData is provided, it will be seen every scan cycle.  *in addition* to anything actually seen in the air
                        	// it will not be used if we are not in debug mode
                        	if ( 0 != ( getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE ) ) {
                            	for (IBeacon iBeacon : simulatedScanData) {
                            		processIBeaconFromScan(iBeacon);
                            	}                        		
                        	}
                        	else {
                        		Log.w(TAG, "Simulated scan data provided, but ignored because we are not running in debug mode.  Please remove simulated scan data for production.");
                        	}
                        }
                        if (isInBackground()) {
                        	Log.d(TAG, "We are in the background.  Waiting a little bit before scanning again.");
                        	handler.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                	scanLeDevice(true);                        	
                                }
                        	}, BACKGROUND_BETWEEN_SCAN_PERIOD);
                        }
                        else {
                        	scanLeDevice(true);                		                        		
                        }                    		
                			
                	}
                }
            }, scanPeriod);
            
            trackedBeacons = new HashSet<IBeacon>();
            if (scanning == false || scanningPaused == true) {
            	scanning = true;
            	scanningPaused = false;
            	try {
            		if (bluetoothAdapter != null) {
                		if (bluetoothAdapter.isEnabled()) {
                    		bluetoothAdapter.startLeScan(leScanCallback);                    		                    			
                		}
                		else {
                			Log.w(TAG, "Bluetooth is disabled.  Cannot scan for iBeacons.");
                		}
            		}
            	}
            	catch (Exception e) {
            		Log.e("TAG", "Exception starting bluetooth scan.  Perhaps bluetooth is disabled or unavailable?");
            	}
            }
            else {
            	Log.d(TAG, "We are already scanning");
            }
            Log.d(TAG, "Scan started");
        } else {
    		Log.d(TAG, "disabling scan");
        	scanning = false;
        	if (bluetoothAdapter != null) {
                bluetoothAdapter.stopLeScan(leScanCallback);        		
        	}
        }        
        processExpiredMonitors();
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback leScanCallback =
            new BluetoothAdapter.LeScanCallback() {

    	@Override
        public void onLeScan(final BluetoothDevice device, final int rssi,
                final byte[] scanRecord) {
    		Log.d(TAG, "got record");
    		new ScanProcessor().execute(new ScanData(device, rssi, scanRecord));

       }
    };	
    
    private class ScanData {
    	public ScanData(BluetoothDevice device, int rssi, byte[] scanRecord) {
			this.device = device;
			this.rssi = rssi;
			this.scanRecord = scanRecord;
		}
		@SuppressWarnings("unused")
		public BluetoothDevice device;
    	public int rssi;
    	public byte[] scanRecord;
    }
    
    private void processRangeData() {
    	Iterator<Region> regionIterator = rangedRegionState.keySet().iterator();
    	while (regionIterator.hasNext()) {
    		Region region = regionIterator.next();
    		RangeState rangeState = rangedRegionState.get(region);
			Log.d(TAG, "Calling ranging callback with "+rangeState.getIBeacons().size()+" iBeacons");
			rangeState.getCallback().call(IBeaconService.this, "monitoringData", new RangingData(rangeState.getIBeacons(), region));    			
    		rangeState.clearIBeacons();
    	}

    }
    
    private void processExpiredMonitors() {
		  Iterator<Region> monitoredRegionIterator = monitoredRegionState.keySet().iterator();
		  while (monitoredRegionIterator.hasNext()) {
			  Region region = monitoredRegionIterator.next();
			  MonitorState state = monitoredRegionState.get(region);
			  if (state.isNewlyOutside()) {
				  Log.d(TAG, "found a monitor that expired: "+region);
				  state.getCallback().call(IBeaconService.this, "monitoringData", new MonitoringData(state.isInside(), region));
			  }     			  
		  }
    }
    
	private void processIBeaconFromScan(IBeacon iBeacon) {
		lastIBeaconDetectionTime = new Date();
		trackedBeacons.add(iBeacon);
		Log.d(TAG,
				"iBeacon detected :" + iBeacon.getProximityUuid() + " "
						+ iBeacon.getMajor() + " " + iBeacon.getMinor()
						+ " accuracy: " + iBeacon.getAccuracy()
						+ " proximity: " + iBeacon.getProximity());

		List<Region> matchedRegions = matchingRegions(iBeacon,
				monitoredRegionState.keySet());
		Iterator<Region> matchedRegionIterator = matchedRegions.iterator();
		while (matchedRegionIterator.hasNext()) {
			Region region = matchedRegionIterator.next();
			MonitorState state = monitoredRegionState.get(region);
			if (state.markInside()) {
				state.getCallback().call(IBeaconService.this, "monitoringData",
						new MonitoringData(state.isInside(), region));
			}
		}

		Log.d(TAG, "looking for ranging region matches for this ibeacon");
		matchedRegions = matchingRegions(iBeacon, rangedRegionState.keySet());
		matchedRegionIterator = matchedRegions.iterator();
		while (matchedRegionIterator.hasNext()) {
			Region region = matchedRegionIterator.next();
			Log.d(TAG, "matches ranging region: " + region);
			RangeState rangeState = rangedRegionState.get(region);
			rangeState.addIBeacon(iBeacon);
		}		
	}
    
	private class ScanProcessor extends AsyncTask<ScanData, Void, Void> {

		@Override
		protected Void doInBackground(ScanData... params) {
			ScanData scanData = params[0];

			IBeacon iBeacon = IBeacon.fromScanData(scanData.scanRecord,
					scanData.rssi);
			if (iBeacon != null) {
				processIBeaconFromScan(iBeacon);
			}
			return null;
		}

		@Override
		protected void onPostExecute(Void result) {
		}

		@Override
		protected void onPreExecute() {
		}

		@Override
		protected void onProgressUpdate(Void... values) {
		}
	}

    private List<Region> matchingRegions(IBeacon iBeacon, Collection<Region> regions) {
    	List<Region> matched = new ArrayList<Region>();
    	Iterator<Region> regionIterator = regions.iterator();
    	while (regionIterator.hasNext()) {
    		Region region = regionIterator.next();
    		if (region.matchesIBeacon(iBeacon)) {
    			matched.add(region);
    		}
    		else {
    			Log.d(TAG, "This region does not match: "+region);
    		}
    				
    	}
    	return matched;    	
    }

}
