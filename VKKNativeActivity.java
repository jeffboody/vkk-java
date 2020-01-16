/*
 * Copyright (c) 2020 Jeff Boody
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

import android.Manifest;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.SensorManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.media.AudioManager;
import android.location.LocationManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.support.v4.app.ActivityCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import java.util.LinkedList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class VKKNativeActivity
extends NativeActivity
implements Handler.Callback,
           SensorEventListener,
           ActivityCompat.OnRequestPermissionsResultCallback
{
	private static final String TAG = "vkk";

	// native interface
	private native void NativeAccelerometer(float ax, float ay,
	                                        float az, int rotation,
	                                        double ts);
	private native void NativeButtonDown(int id, int button, double ts);
	private native void NativeButtonUp(int id, int button, double ts);
	private native void NativeDensity(float density);
	private native void NativeGrantPermission(int permission);
	private native void NativeGyroscope(float ax, float ay, float az,
	                                    double ts);
	private native void NativeKeyDown(int keycode, int meta, double ts);
	private native void NativeKeyUp(int keycode, int meta, double ts);
	private native void NativeMagnetometer(float mx, float my,
	                                       float mz, double ts,
	                                       float gfx, float gfy,
	                                       float gfz);

	// native commands
	private static final int VKK_PLATFORM_CMD_ACCELEROMETER_OFF = 1;
	private static final int VKK_PLATFORM_CMD_ACCELEROMETER_ON  = 2;
	private static final int VKK_PLATFORM_CMD_CHECK_PERMISSIONS = 3;
	private static final int VKK_PLATFORM_CMD_EXIT              = 4;
	private static final int VKK_PLATFORM_CMD_GYROSCOPE_OFF     = 5;
	private static final int VKK_PLATFORM_CMD_GYROSCOPE_ON      = 6;
	private static final int VKK_PLATFORM_CMD_LOADURL           = 7;
	private static final int VKK_PLATFORM_CMD_MAGNETOMETER_OFF  = 8;
	private static final int VKK_PLATFORM_CMD_MAGNETOMETER_ON   = 9;
	private static final int VKK_PLATFORM_CMD_PLAY_CLICK        = 10;
	private static final int VKK_PLATFORM_CMD_REQ_LOCATION_PERM = 11;
	private static final int VKK_PLATFORM_CMD_REQ_STORAGE_PERM  = 12;
	private static final int VKK_PLATFORM_CMD_SOFTKEY_HIDE      = 13;
	private static final int VKK_PLATFORM_CMD_SOFTKEY_SHOW      = 14;

	// permissions
	private static final int VKK_PERMISSION_LOCATION = 1;
	private static final int VKK_PERMISSION_STORAGE  = 2;

	private static LinkedList<Integer> mCmdQueue = new LinkedList<Integer>();
	private static Lock                mCmdLock  = new ReentrantLock();

	// "singleton" used for callbacks
	// handler is used to trigger commands on UI thread
	private static Handler mHandler = null;
	private static String  mURL     = "";

	/*
	 * Command Queue - A queue is needed to ensure commands
	 * are not lost between the rendering thread and the
	 * main thread when pausing the app.
	 */

	private void DrainCommandQueue(boolean handler)
	{
		mCmdLock.lock();
		try
		{
			while(mCmdQueue.size() > 0)
			{
				int cmd = mCmdQueue.remove();
				if(cmd == VKK_PLATFORM_CMD_ACCELEROMETER_ON)
				{
					cmdAccelerometerEnable();
				}
				else if(cmd == VKK_PLATFORM_CMD_ACCELEROMETER_OFF)
				{
					cmdAccelerometerDisable();
				}
				else if(cmd == VKK_PLATFORM_CMD_MAGNETOMETER_ON)
				{
					cmdMagnetometerEnable();
				}
				else if(cmd == VKK_PLATFORM_CMD_MAGNETOMETER_OFF)
				{
					cmdMagnetometerDisable();
				}
				else if(cmd == VKK_PLATFORM_CMD_GYROSCOPE_ON)
				{
					cmdGyroscopeEnable();
				}
				else if(cmd == VKK_PLATFORM_CMD_GYROSCOPE_OFF)
				{
					cmdGyroscopeDisable();
				}
				else if(cmd == VKK_PLATFORM_CMD_PLAY_CLICK)
				{
					if(handler)
					{
						AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
						am.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.5F);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_CHECK_PERMISSIONS)
				{
					if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
					   PackageManager.PERMISSION_GRANTED)
					{
						NativeGrantPermission(VKK_PERMISSION_LOCATION);
					}

					if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
					   PackageManager.PERMISSION_GRANTED)
					{
						NativeGrantPermission(VKK_PERMISSION_STORAGE);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_REQ_LOCATION_PERM)
				{
					if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
					   PackageManager.PERMISSION_GRANTED)
					{
						NativeGrantPermission(VKK_PERMISSION_LOCATION);
					}
					else
					{
						String[] perm = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
						ActivityCompat.requestPermissions(this, perm,
						                                  VKK_PERMISSION_LOCATION);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_REQ_STORAGE_PERM)
				{
					if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
					   PackageManager.PERMISSION_GRANTED)
					{
						NativeGrantPermission(VKK_PERMISSION_STORAGE);
					}
					else
					{
						String[] perm = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};
						ActivityCompat.requestPermissions(this, perm, VKK_PERMISSION_STORAGE);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_LOADURL)
				{
					try
					{
						Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(mURL));
						startActivity(intent);
					}
					catch(Exception e)
					{
						// ignore
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_SOFTKEY_HIDE)
				{
					if(handler)
					{
						InputMethodManager imm;
						imm = (InputMethodManager)
						      getSystemService(Context.INPUT_METHOD_SERVICE);
						View    decorView = getWindow().getDecorView();
						IBinder iBinder   = decorView.getWindowToken();
						imm.hideSoftInputFromWindow(iBinder, 0);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_SOFTKEY_SHOW)
				{
					if(handler)
					{
						// bug fix for older versions of Android
						View decorView = getWindow().getDecorView();
						if(decorView.hasFocus())
						{
							decorView.clearFocus();
						}
						decorView.requestFocus();

						// show/hide should be called on mNativeContentView
						// but this isn't accesible when extending
						// NativeActivity and as a result the soft keyboard is
						// overlayed on the mNativeContentView obstructing its
						// contents rather than resizing its contents
						// See vkk_platform_cmd() for more details.
						InputMethodManager imm;
						imm = (InputMethodManager)
						      getSystemService(Context.INPUT_METHOD_SERVICE);
						imm.showSoftInput(decorView, 0);
					}
				}
				else if(cmd == VKK_PLATFORM_CMD_EXIT)
				{
					if(handler)
					{
						finish();
					}
				}
				else
				{
					Log.w(TAG, "unknown cmd=" + cmd);
				}
			}
		}
		catch(Exception e)
		{
			Log.e(TAG, "exception: " + e);
		}
		mCmdLock.unlock();
	}

	/*
	 * Callback interface
	 */

	private static void CallbackCmd(int cmd, String msg)
	{
		try
		{
			if(cmd == VKK_PLATFORM_CMD_LOADURL)
			{
				mURL = msg;
			}

			mCmdLock.lock();
			mHandler.sendMessage(Message.obtain(mHandler, cmd));
			mCmdQueue.add(cmd);
			mCmdLock.unlock();
		}
		catch(Exception e)
		{
			// ignore
		}
	}

	public boolean handleMessage(Message msg)
	{
		DrainCommandQueue(true);
		return true;
	}

	/*
	 * Event handling
	 */

	// special keys
	private static final int VKK_KEYCODE_ENTER     = 0x00D;
	private static final int VKK_KEYCODE_ESCAPE    = 0x01B;
	private static final int VKK_KEYCODE_BACKSPACE = 0x008;
	private static final int VKK_KEYCODE_DELETE    = 0x07F;
	private static final int VKK_KEYCODE_UP        = 0x100;
	private static final int VKK_KEYCODE_DOWN      = 0x101;
	private static final int VKK_KEYCODE_LEFT      = 0x102;
	private static final int VKK_KEYCODE_RIGHT     = 0x103;
	private static final int VKK_KEYCODE_HOME      = 0x104;
	private static final int VKK_KEYCODE_END       = 0x105;
	private static final int VKK_KEYCODE_PGUP      = 0x106;
	private static final int VKK_KEYCODE_PGDOWN    = 0x107;
	private static final int VKK_KEYCODE_INSERT    = 0x108;

	// sensors
	private Sensor  mAccelerometer;
	private Sensor  mMagnetic;
	private Sensor  mGyroscope;
	private boolean mEnableAccelerometer = false;
	private boolean mEnableMagnetometer  = false;
	private boolean mEnableGyroscope     = false;

	private static boolean isGameKey(int keycode)
	{
		if((keycode == KeyEvent.KEYCODE_DPAD_CENTER) ||
		   (keycode == KeyEvent.KEYCODE_DPAD_UP)     ||
		   (keycode == KeyEvent.KEYCODE_DPAD_DOWN)   ||
		   (keycode == KeyEvent.KEYCODE_DPAD_LEFT)   ||
		   (keycode == KeyEvent.KEYCODE_DPAD_RIGHT))
		{
			return true;
		}

		return KeyEvent.isGamepadButton(keycode);
	}

	private static int getAscii(int keycode, KeyEvent event)
	{
		int ascii = event.getUnicodeChar(0);
		if(keycode == KeyEvent.KEYCODE_ENTER)
		{
			return VKK_KEYCODE_ENTER;
		}
		else if(keycode == KeyEvent.KEYCODE_ESCAPE)
		{
			return VKK_KEYCODE_ESCAPE;
		}
		else if(keycode == KeyEvent.KEYCODE_DEL)
		{
			return VKK_KEYCODE_BACKSPACE;
		}
		else if(keycode == KeyEvent.KEYCODE_FORWARD_DEL)
		{
			return VKK_KEYCODE_DELETE;
		}
		else if(keycode == KeyEvent.KEYCODE_DPAD_UP)
		{
			return VKK_KEYCODE_UP;
		}
		else if(keycode == KeyEvent.KEYCODE_DPAD_DOWN)
		{
			return VKK_KEYCODE_DOWN;
		}
		else if(keycode == KeyEvent.KEYCODE_DPAD_LEFT)
		{
			return VKK_KEYCODE_LEFT;
		}
		else if(keycode == KeyEvent.KEYCODE_DPAD_RIGHT)
		{
			return VKK_KEYCODE_RIGHT;
		}
		else if(keycode == KeyEvent.KEYCODE_MOVE_HOME)
		{
			return VKK_KEYCODE_HOME;
		}
		else if(keycode == KeyEvent.KEYCODE_MOVE_END)
		{
			return VKK_KEYCODE_END;
		}
		else if(keycode == KeyEvent.KEYCODE_PAGE_UP)
		{
			return VKK_KEYCODE_PGUP;
		}
		else if(keycode == KeyEvent.KEYCODE_PAGE_DOWN)
		{
			return VKK_KEYCODE_PGDOWN;
		}
		else if(keycode == KeyEvent.KEYCODE_BACK)
		{
			return VKK_KEYCODE_ESCAPE;
		}
		else if((ascii > 0) && (ascii < 128))
		{
			return ascii;
		}
		return 0;
	}

	private static double getTimestamp(double t0)
	{
		// convert "uptime" timestamp to UTC timestamp
		double now = (double) System.currentTimeMillis();
		double t1  = (double) SystemClock.uptimeMillis();
		return (now + t0 - t1)/1000.0;
	}

	/*
	 * Activity interface
	 */

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		mHandler = new Handler(this);
	}

	@Override
	protected void onResume()
	{
		super.onResume();

		View decorView = getWindow().getDecorView();
		int  ui_flags  = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
		                 View.SYSTEM_UI_FLAG_HIDE_NAVIGATION  |
		                 View.SYSTEM_UI_FLAG_FULLSCREEN;
		decorView.setSystemUiVisibility(ui_flags);

		DisplayMetrics metrics = new DisplayMetrics();
		WindowManager  wm      = (WindowManager)
		                         getSystemService(Context.WINDOW_SERVICE);
		wm.getDefaultDisplay().getMetrics(metrics);
		NativeDensity(metrics.density);

		if(mEnableAccelerometer)
		{
			cmdAccelerometerEnable();
		}

		if(mEnableMagnetometer)
		{
			cmdMagnetometerEnable();
		}

		if(mEnableGyroscope)
		{
			cmdGyroscopeEnable();
		}
	}

	@Override
	protected void onPause()
	{
		// update the sensor state
		DrainCommandQueue(false);
		boolean enable_accelerometer = mEnableAccelerometer;
		boolean enable_magnetometer  = mEnableMagnetometer;
		boolean enable_gyroscope     = mEnableGyroscope;

		cmdAccelerometerDisable();
		cmdMagnetometerDisable();
		cmdGyroscopeDisable();

		mEnableAccelerometer = enable_accelerometer;
		mEnableMagnetometer  = enable_magnetometer;
		mEnableGyroscope     = enable_gyroscope;

		super.onPause();
	}

	@Override
	protected void onDestroy()
	{
		mHandler = null;
		super.onDestroy();
	}

	@Override
	public boolean onKeyDown(int keycode, KeyEvent event)
	{
		int    ascii = getAscii(keycode, event);
		int    meta  = event.getMetaState();
		double ts    = getTimestamp(event.getEventTime());

		if(ascii > 0)
		{
			NativeKeyDown(ascii, meta, ts);
		}

		if(isGameKey(keycode))
		{
			NativeButtonDown(event.getDeviceId(), keycode, ts);
		}

		return true;
	}

	@Override
	public boolean onKeyUp(int keycode, KeyEvent event)
	{
		int    ascii = getAscii(keycode, event);
		int    meta  = event.getMetaState();
		double ts    = getTimestamp(event.getEventTime());

		if(ascii > 0)
		{
			NativeKeyUp(ascii, meta, ts);
		}

		if(isGameKey(keycode))
		{
			NativeButtonUp(event.getDeviceId(), keycode, ts);
		}

		return true;
	}

	/*
	 * SensorEventListener interface
	 */

	private void cmdAccelerometerEnable()
	{
		if(mAccelerometer == null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			mAccelerometer = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
			if(mAccelerometer != null)
			{
				sm.registerListener(this,
				                    mAccelerometer,
				                    SensorManager.SENSOR_DELAY_GAME);
			}
		}
		mEnableAccelerometer = true;
	}

	private void cmdMagnetometerEnable()
	{
		if(mMagnetic == null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			mMagnetic = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
			if(mMagnetic != null)
			{
				sm.registerListener(this,
				                    mMagnetic,
				                    SensorManager.SENSOR_DELAY_GAME);
			}
		}
		mEnableMagnetometer = true;
	}

	private void cmdGyroscopeEnable()
	{
		if(mGyroscope == null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			mGyroscope = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
			if(mGyroscope != null)
			{
				sm.registerListener(this,
				                    mGyroscope,
				                    SensorManager.SENSOR_DELAY_GAME);
			}
		}
		mEnableGyroscope = true;
	}

	private void cmdAccelerometerDisable()
	{
		if(mAccelerometer != null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			sm.unregisterListener(this, mAccelerometer);
			mAccelerometer = null;
		}
		mEnableAccelerometer = false;
	}

	private void cmdMagnetometerDisable()
	{
		if(mMagnetic != null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			sm.unregisterListener(this, mMagnetic);
			mMagnetic = null;
		}
		mEnableMagnetometer = false;
	}

	private void cmdGyroscopeDisable()
	{
		if(mGyroscope != null)
		{
			SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
			sm.unregisterListener(this, mGyroscope);
			mGyroscope = null;
		}
		mEnableGyroscope = false;
	}

	public void onSensorChanged(SensorEvent event)
	{
		if(event == null)
		{
			// ignore
			return;
		}

		// convert nanosecond to second
		double ts = (double) event.timestamp/1000000000.0;
		if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
		{
			int   r  = 90*getWindowManager().getDefaultDisplay().getRotation();
			float ax = event.values[0];
			float ay = event.values[1];
			float az = event.values[2];
			NativeAccelerometer(ax, ay, az, r, ts);
		}
		else if(event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
		{
			// get the geomagnetic field to correct magnetic north
			float gfx = 0.0f;
			float gfy = 1.0f;
			float gfz = 1.0f;
			try
			{
				LocationManager lm;
				Location        l;
				lm = (LocationManager)
				     getSystemService(Context.LOCATION_SERVICE);
				l  = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
				if(l != null)
				{
					GeomagneticField gf;

					// remap geomagnetic field to world coordinate system
					// getX - north
					// getY - east
					// getZ - down
					gf = new GeomagneticField((float) l.getLatitude(),
					                          (float) l.getLongitude(),
					                          (float) l.getAltitude(),
					                          l.getTime());
					gfx = gf.getY();
					gfy = gf.getX();
					gfz = gf.getZ();
				}
			}
			catch(Exception e)
			{
				Log.e(TAG, "exception: " + e);
			}

			float mx  = event.values[0];
			float my  = event.values[1];
			float mz  = event.values[2];
			NativeMagnetometer(mx, my, mz, ts, gfx, gfy, gfz);
		}
		else if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE)
		{
			float ax = event.values[0];
			float ay = event.values[1];
			float az = event.values[2];
			NativeGyroscope(ax, ay, az, ts);
		}
	}

	public void onAccuracyChanged(Sensor sensor, int accuracy)
	{
	}

	/*
	 * Permissions interface
	 */

	public void onRequestPermissionsResult(int requestCode,
	                                       String[] permissions,
	                                       int[] grantResults)
	{
		if(requestCode == VKK_PERMISSION_LOCATION)
		{
			if((grantResults.length > 0) &&
			   (grantResults[0] == PackageManager.PERMISSION_GRANTED))
			{
				NativeGrantPermission(VKK_PERMISSION_LOCATION);
			}
		}
		else if(requestCode == VKK_PERMISSION_STORAGE)
		{
			if((grantResults.length > 0) &&
			   (grantResults[0] == PackageManager.PERMISSION_GRANTED))
			{
				NativeGrantPermission(VKK_PERMISSION_STORAGE);
			}
		}
	}
}