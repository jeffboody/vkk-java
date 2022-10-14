/*
 * Copyright (c) 2016 Jeff Boody
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 */

package com.jeffboody.vkk;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import android.util.Log;

public class VKKGpsService
extends Service
implements LocationListener
{
	private static final String TAG = "vkk";

	private static final int NOTIFICATION_ID = 42;

	// sensors
	private int     mGpsRunning = 0;
	private boolean mGpsEnable  = false;
	private boolean mGpsRecord  = false;

	// singleton
	private static VKKGpsService mService = null;

	/*
	 * Native interface
	 */

	private native void NativeGps(double lat, double lon,
	                              float accuracy, float altitude,
	                              float speed, float bearing,
	                              double ts);

	/*
	 * Activity interface
	 */

	void cmdGpsEnable()
	{
		Log.i(TAG, "cmdGpsEnable mGpsEnable=" + mGpsEnable);

		if(mGpsEnable == false)
		{
			gpsStart();
			mGpsEnable = true;
		}
	}

	void cmdGpsDisable()
	{
		Log.i(TAG, "cmdGpsDisable mGpsEnable=" + mGpsEnable);

		if(mGpsEnable)
		{
			gpsStop();
			mGpsEnable = false;
		}
	}

	void cmdGpsRecord(String app_name, Intent intent,
	                  int ic_notification)
	{
		Log.i(TAG, "cmdGpsRecord mGpsRecord=" + mGpsRecord);

		if(mGpsRecord == false)
		{
			NotificationChannel nc;
			nc = new NotificationChannel(app_name, "VKKGpsService",
			                             NotificationManager.IMPORTANCE_LOW);
			nc.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

			NotificationManager nm;
			nm = (NotificationManager)
			     getSystemService(Context.NOTIFICATION_SERVICE);
			nm.createNotificationChannel(nc);

			PendingIntent pi;
			pi = PendingIntent.getActivity(this, 0, intent,
			                               PendingIntent.FLAG_IMMUTABLE);
			Notification n = new NotificationCompat.Builder(this, app_name)
			.setCategory(Notification.CATEGORY_SERVICE)
			.setContentTitle("GPX track started")
			.setContentText("Tap to view track.")
			.setSmallIcon(ic_notification)
			.setContentIntent(pi)
			.setTicker("GPX recording ticker")
			.setOngoing(true)
			.setAutoCancel(false)
			.build();

			startForeground(NOTIFICATION_ID, n);
			gpsStart();
			mGpsRecord = true;
		}
	}

	void cmdGpsPause()
	{
		Log.i(TAG, "cmdGpsPause mGpsRecord=" + mGpsRecord);

		if(mGpsRecord)
		{
			stopForeground(true);
			gpsStop();
			mGpsRecord = false;
		}
	}

	public void onActivityPause()
	{
		Log.i(TAG, "onActivityPause mGpsEnable=" + mGpsEnable);

		// disable GPS on pause (if needed)
		if(mGpsEnable)
		{
			gpsStop();
		}
	}

	public void onActivityResume()
	{
		Log.i(TAG, "onActivityResume mGpsEnable=" + mGpsEnable);

		// restore GPS on resume (if needed)
		if(mGpsEnable)
		{
			gpsStart();
		}
	}

	/*
	 * Service interface
	 */

	@Override
	public void onCreate()
	{
		Log.i(TAG, "onCreate");
		super.onCreate();
		mService = this;
	}

	@Override
	public void onDestroy()
	{
		Log.i(TAG, "onDestroy1 mGpsRunning=" + mGpsRunning + ", mGpsRecord=" + mGpsRecord);
		mService = null;

		// stop GPS on destroy (if needed)
		if(mGpsRecord)
		{
			cmdGpsPause();
		}

		// verify reference count
		if(mGpsRunning > 0)
		{
			Log.e(TAG, "GPS still running in destroy");
		}

		super.onDestroy();

		Log.i(TAG, "onDestroy2 mGpsRunning=" + mGpsRunning + ", mGpsRecord=" + mGpsRecord);
	}

	@Override
	public int onStartCommand(Intent intent, int flags,
	                          int startId)
	{
		Log.i(TAG, "onStartCommand");
		return START_NOT_STICKY;
	}

	@Override
	public IBinder onBind(Intent intent)
	{
		Log.i(TAG, "onBind");
		return null;
	}

	/*
	 * Singleton interface
	 */

	public static VKKGpsService getSingleton()
	{
		return mService;
	}

	/*
	 * LocationListener interface
	 */

	private void gpsStart()
	{
		Log.i(TAG, "gpsStart mGpsRunning=" + mGpsRunning);

		if(mGpsRunning > 0)
		{
			++mGpsRunning;
			return;
		}

		// listen for gps
		try
		{
			LocationManager lm = (LocationManager)
			                     getSystemService(Context.LOCATION_SERVICE);
			if(lm.isProviderEnabled(LocationManager.GPS_PROVIDER) == false)
			{
				return;
			}

			lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0.0F, this, Looper.getMainLooper());
			onLocationChanged(lm.getLastKnownLocation(LocationManager.GPS_PROVIDER));
		}
		catch(Exception e)
		{
			Log.e(TAG, "exception: " + e);
			return;
		}

		mGpsRunning = 1;
	}

	private void gpsStop()
	{
		Log.i(TAG, "gpsStop mGpsRunning=" + mGpsRunning);

		if(mGpsRunning == 0)
		{
			return;
		}
		else if(mGpsRunning > 1)
		{
			--mGpsRunning;
			return;
		}

		mGpsRunning = 0;
		try
		{
			LocationManager lm = (LocationManager)
			                     getSystemService(Context.LOCATION_SERVICE);
			lm.removeUpdates(this);
		}
		catch(Exception e)
		{
			Log.e(TAG, "exception: " + e);
			// ignore
		}
	}

	public void onLocationChanged(Location location)
	{
		if(location == null)
		{
			// ignore
			return;
		}

		double lat      = location.getLatitude();
		double lon      = location.getLongitude();
		float  accuracy = location.getAccuracy();
		float  altitude = (float) location.getAltitude();
		float  speed    = location.getSpeed();
		float  bearing  = location.getBearing();
		double ts       = ((double) location.getTime())/1000.0;
		NativeGps(lat, lon, accuracy, altitude, speed, bearing, ts);
	}

	public void onProviderDisabled(String provider)
	{
	}

	public void onProviderEnabled(String provider)
	{
	}

	@Deprecated
	public void onStatusChanged(String provider, int status, Bundle extras)
	{
	}
}
