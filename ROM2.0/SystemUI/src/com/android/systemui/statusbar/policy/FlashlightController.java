/*
* Copyright (C) 2014 MediaTek Inc.
* Modification based on code covered by the mentioned copyright
* and/or permission notice(s).
*/
/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.SurfaceTexture;
import android.hardware.IFlashLightListener;
import android.hardware.IFlashLightManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.os.*;
import android.os.Process;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
//import com.huaqin.common.featureoption.FeatureOption;
/**
 * Manages the flashlight.
 */
public class FlashlightController {

    private static final String TAG = "FlashlightController";
    private static final boolean DEBUG = true;//Log.isLoggable(TAG, Log.DEBUG);

    private static final int DISPATCH_ERROR = 0;
    private static final int DISPATCH_OFF = 1;
    private static final int DISPATCH_AVAILABILITY_CHANGED = 2;
    private static final int DISPATCH_STATUS_UPDATE = 3;
    
    private static final String ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO";
    
    private final CameraManager mCameraManager;
    /** Call {@link #ensureHandler()} before using */
    private Handler mHandler;

    /** Lock on mListeners when accessing */
    private final ArrayList<WeakReference<FlashlightListener>> mListeners = new ArrayList<>(1);

    /** Lock on {@code this} when accessing */
    private boolean mFlashlightEnabled;

    private String mCameraId;
    private boolean mCameraAvailable;
    private CameraDevice mCameraDevice;
    private CaptureRequest mFlashlightRequest;
    private CameraCaptureSession mSession;
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    //private final CameraManager mCameraManager;
    private Context mFlashlightContext;

    private IFlashLightManager mFlashLightManager;

    private IFlashLightListener mFlashLightListener = new IFlashLightListener.Stub(){

        @Override
        public void onFlashlightStatusUpdate(boolean enable) throws RemoteException {
		if(mFlashLightManager != null){
            		dispatchStatusUpdated(mFlashLightManager.getOnOff());
		}
        }

        @Override
        public void onFlashlightError() throws RemoteException {

        }
    };

    public FlashlightController(Context mContext) {
        Log.i(TAG, "[FlashlightController]");
        mFlashlightContext = mContext;
	if(com.huaqin.common.featureoption.FeatureOption.HQ_FLASHLIGHT_APP){
		/*
		mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);

		initialize();

		mFlashLightManager = IFlashLightManager.Stub.asInterface(ServiceManager.getService("flashlight"));
		try {
			if(mFlashLightManager != null){
		    		mFlashLightManager.setListener(mFlashLightListener);
			}
		} catch (RemoteException e) {
		    e.printStackTrace();
		}
		ensureHandler();
		// M: Turn off the flashlight when shutdown.
		initializeReceiver(mContext);
		*/

		mFlashLightManager = IFlashLightManager.Stub.asInterface(ServiceManager.getService("flashlight"));
		// initialize();
		 
		try {
		    mFlashLightManager.setListener(mFlashLightListener);
		} catch (RemoteException e) {
		    e.printStackTrace();
		}
		//ensureHandler();
		// M: Turn off the flashlight when shutdown.
		initializeReceiver(mContext);
		mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
		initialize();
		//mCameraManager.registerAvailabilityCallback(mAvailabilityCallback, mHandler);


	}else{

		mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
		initialize();
		// M: Turn off the flashlight when shutdown.
		initializeReceiver(mContext);
	}
    }

    public void initialize() {
        Log.i(TAG, "[initialize]");
        try {
            mCameraId = getCameraId();
        } catch (Throwable e) {
            Log.e(TAG, "Couldn't initialize.", e);
            return;
        }

        if (mCameraId != null) {
            ensureHandler();
            mCameraManager.registerAvailabilityCallback(mAvailabilityCallback, mHandler);
        }
    }

    public synchronized void setFlashlight(boolean enabled) {
        Log.i(TAG, "[setFlashlight]enabled=" + enabled);
        if (mFlashlightEnabled != enabled) {
            mFlashlightEnabled = enabled;
            postUpdateFlashlight();
        }
    }

    public void killFlashlight() {
        Log.i(TAG, "[killFlashlight]mFlashlightEnabled=" + mFlashlightEnabled);
        boolean enabled;
        synchronized (this) {
            enabled = mFlashlightEnabled;
        }
        if (enabled) {
            mHandler.post(mKillFlashlightRunnable);
        }
    }

    public synchronized boolean isAvailable() {
	//if(com.huaqin.common.featureoption.FeatureOption.HQ_FLASHLIGHT_APP){
	//	return mCameraAvailable;

	//}else{
		return mCameraAvailable;
	//}
    }

    public void addListener(FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
            mListeners.add(new WeakReference<>(l));
        }
    }

    public void removeListener(FlashlightListener l) {
        synchronized (mListeners) {
            cleanUpListenersLocked(l);
        }
    }

    private synchronized void ensureHandler() {
        if (mHandler == null) {
            HandlerThread thread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            mHandler = new Handler(thread.getLooper());
        }
    }

    private void startDevice() throws CameraAccessException {
        mCameraManager.openCamera(getCameraId(), mCameraListener, mHandler);
    }

    private void startSession() throws CameraAccessException {
        mSurfaceTexture = new SurfaceTexture(false);
        Size size = getSmallestSize(mCameraDevice.getId());
        mSurfaceTexture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        mSurface = new Surface(mSurfaceTexture);
        ArrayList<Surface> outputs = new ArrayList<>(1);
        outputs.add(mSurface);
        mCameraDevice.createCaptureSession(outputs, mSessionListener, mHandler);
    }

    private Size getSmallestSize(String cameraId) throws CameraAccessException {
        Size[] outputSizes = mCameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                .getOutputSizes(SurfaceTexture.class);
        if (outputSizes == null || outputSizes.length == 0) {
            throw new IllegalStateException(
                    "Camera " + cameraId + "doesn't support any outputSize.");
        }
        Size chosen = outputSizes[0];
        for (Size s : outputSizes) {
            if (chosen.getWidth() >= s.getWidth() && chosen.getHeight() >= s.getHeight()) {
                chosen = s;
            }
        }
        return chosen;
    }

    private void postUpdateFlashlight() {
        ensureHandler();
        mHandler.post(mUpdateFlashlightRunnable);
    }

    private String getCameraId() throws CameraAccessException {
        String[] ids = mCameraManager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = mCameraManager.getCameraCharacteristics(id);
            Boolean flashAvailable = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer lensFacing = c.get(CameraCharacteristics.LENS_FACING);
            if (flashAvailable != null && flashAvailable
                    && lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private void updateFlashlight(boolean forceDisable) {

	if(com.huaqin.common.featureoption.FeatureOption.HQ_FLASHLIGHT_APP){
		try {
		    boolean enabled;
		    synchronized (this) {
		        enabled = mFlashlightEnabled && !forceDisable;
		    }
			if(mFlashLightManager != null){
			    if (enabled) {
				mFlashLightManager.setOnOff(true);
			    } else {
				mFlashLightManager.setOnOff(false);
			    }
			}

		} catch (RemoteException e) {
		    Log.e(TAG, "Error in updateFlashlight", e);
		    handleError();
		}
	}else{
		try {
		    boolean enabled;
		    synchronized (this) {
		        enabled = mFlashlightEnabled && !forceDisable;
		    }
		    if (enabled) {
		        if (mCameraDevice == null) {
		            startDevice();
		            return;
		        }
		        if (mSession == null) {
		            startSession();
		            return;
		        }
		        /// M: [ALPS01753229]JE @{
		        if (mSurface == null) {
		            Log.e(TAG, "Error in updateFlashlight: mSurface is null");
		            handleError();
		            return;
		        }
		        /// M: [ALPS01753229]JE @}
		        if (mFlashlightRequest == null) {
		            CaptureRequest.Builder builder = mCameraDevice.createCaptureRequest(
		                    CameraDevice.TEMPLATE_PREVIEW);
		            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
		            builder.addTarget(mSurface);
		            CaptureRequest request = builder.build();
		            mSession.capture(request, null, mHandler);
		            mFlashlightRequest = request;
		        }
		    } else {
		        if (mCameraDevice != null) {
		            mCameraDevice.close();
		            teardown();
		        }
		    }

		} catch (CameraAccessException|IllegalStateException|UnsupportedOperationException e) {
		    Log.e(TAG, "Error in updateFlashlight", e);
		    handleError();
		}
	}

    }

    private void teardown() {
        /// M: [ALPS01753229]JE @{
        if (mCameraDevice != null) {
            mCameraDevice.close();
        }
        if (mSession != null) {
            mSession.close();
        }
        /// M: [ALPS01753229]JE @}
        mCameraDevice = null;
        mSession = null;
        mFlashlightRequest = null;
        if (mSurface != null) {
            mSurface.release();
            mSurfaceTexture.release();
        }
        mSurface = null;
        mSurfaceTexture = null;
    }

    private void handleError() {
        synchronized (this) {
            mFlashlightEnabled = false;
        }
        dispatchError();
        dispatchOff();
        updateFlashlight(true /* forceDisable */);
    }

    private void dispatchOff() {
        dispatchListeners(DISPATCH_OFF, false /* argument (ignored) */);
    }

    private void dispatchError() {
        dispatchListeners(DISPATCH_ERROR, false /* argument (ignored) */);
    }

    private void dispatchAvailabilityChanged(boolean available) {
        dispatchListeners(DISPATCH_AVAILABILITY_CHANGED, available);
    }

    private void dispatchStatusUpdated(boolean status) {
        mFlashlightEnabled = status;
        dispatchListeners(DISPATCH_STATUS_UPDATE, status);
    }

    private void dispatchListeners(int message, boolean argument) {
        synchronized (mListeners) {
            final int N = mListeners.size();
            boolean cleanup = false;
            for (int i = 0; i < N; i++) {
                FlashlightListener l = mListeners.get(i).get();
                if (l != null) {
                    if (message == DISPATCH_ERROR) {
                        l.onFlashlightError();
                    } else if (message == DISPATCH_OFF) {
                        l.onFlashlightOff();
                    } else if (message == DISPATCH_AVAILABILITY_CHANGED) {
                        l.onFlashlightAvailabilityChanged(argument);
                    }else if(message == DISPATCH_STATUS_UPDATE){
			if(com.huaqin.common.featureoption.FeatureOption.HQ_FLASHLIGHT_APP){
                        	l.onFlashlightStatusUpdate(argument);
			}
                    }
                } else {
                    cleanup = true;
                }
            }
            if (cleanup) {
                cleanUpListenersLocked(null);
            }
        }
    }

    private void cleanUpListenersLocked(FlashlightListener listener) {
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            FlashlightListener found = mListeners.get(i).get();
            if (found == null || found == listener) {
                mListeners.remove(i);
            }
        }
    }

    private final CameraDevice.StateListener mCameraListener = new CameraDevice.StateListener() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            postUpdateFlashlight();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            if (mCameraDevice == camera) {
                dispatchOff();
                teardown();
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: camera=" + camera + " error=" + error);
            if (camera == mCameraDevice || mCameraDevice == null) {
                handleError();
            }
        }
    };

    private final CameraCaptureSession.StateListener mSessionListener =
            new CameraCaptureSession.StateListener() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            if (session.getDevice() == mCameraDevice) {
                mSession = session;
            } else {
                session.close();
            }
            postUpdateFlashlight();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "Configure failed.");
            if (mSession == null || mSession == session) {
                handleError();
            }
        }
    };

    private final Runnable mUpdateFlashlightRunnable = new Runnable() {
        @Override
        public void run() {
            updateFlashlight(false /* forceDisable */);
        }
    };

    private final Runnable mKillFlashlightRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (this) {
                mFlashlightEnabled = false;
            }
            updateFlashlight(true /* forceDisable */);
            dispatchOff();
        }
    };

    private final CameraManager.AvailabilityCallback mAvailabilityCallback =
            new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String cameraId) {
            if (DEBUG) Log.d(TAG, "onCameraAvailable(" + cameraId + ")");
            //if (cameraId.equals(mCameraId)) {
                setCameraAvailable(true);
            //}
        }

        @Override
        public void onCameraUnavailable(String cameraId) {
            if (DEBUG) Log.d(TAG, "onCameraUnavailable(" + cameraId + ")");
            //if (cameraId.equals(mCameraId)) {
                setCameraAvailable(false);
            //}
        }

        private void setCameraAvailable(boolean available) {
            boolean changed;
            synchronized (FlashlightController.this) {
                changed = mCameraAvailable != available;
                mCameraAvailable = available;
            }
            if (changed) {
                if (DEBUG) Log.d(TAG, "dispatchAvailabilityChanged(" + available + ")");
                dispatchAvailabilityChanged(available);
            }
        }
    };

    public interface FlashlightListener {

        /**
         * Called when the flashlight turns off unexpectedly.
         */
        void onFlashlightOff();

        /**
         * Called when there is an error that turns the flashlight off.
         */
        void onFlashlightError();

        /**
         * Called when there is a change in availability of the flashlight functionality
         * @param available true if the flashlight is currently available.
         */
        void onFlashlightAvailabilityChanged(boolean available);

        void onFlashlightStatusUpdate(boolean enable);
    }

    // M: Turn off the flashlight when shutdown.
    private void initializeReceiver(Context context) {
        Log.i(TAG, "[initializeReceiver]");
        if (mCameraId == null) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SHUTDOWN);
        filter.addAction(ACTION_SHUTDOWN_IPO);

	if(com.huaqin.common.featureoption.FeatureOption.HQ_FLASHLIGHT_APP){
        	filter.addAction("com.android.service.flashlight.action.stop");
	}

        context.registerReceiver(mShutdownReceiver, filter);
	
	registerBatteryRecriver();
    }

    private final BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "[onReceive]intent=" + intent);
            killFlashlight();
        }
    };

// add by hyman 

	private int mBatteryLevel;
	private int mLastBatteryLevel = -1;
	private OnBatteryLowListener mBatteryLowListener = null;
	public static final int BATTERY_LOWER_LEVEL = 20;
	// Disable to change flash mode when battery <= 15%
	public static final int ENABLE_FLASH_BATTERY_LEVEL = 15;
	// Whether to disable flash when battery <= 15%
	public static final boolean DISABLE_FLASH_WHEN_LOW_BATTERY = true;



	public int getBatteryLevel() {
		return mBatteryLevel;
	}

	public void setBatteryLowListener(OnBatteryLowListener listener) {
		this.mBatteryLowListener = listener;
	}

	public interface OnBatteryLowListener {
		public void onBatteryLowChanged();
		public void onFlashEnabled(boolean enable);
	}

	private void registerBatteryRecriver() {
		IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
		mFlashlightContext.registerReceiver(mBatteryReceiver, intentFilter);
	}

	private void unRegisterBatteryRecriver() {
		mFlashlightContext.unregisterReceiver(mBatteryReceiver);
	}

	/**
	 * Return if current battery is of low battery
	 * @return
	 */
	public boolean isBatteryLow() {
		return (mBatteryLevel != 0 && mBatteryLevel < BATTERY_LOWER_LEVEL) ? true : false;
	}

	public boolean isFlashEnabled() {
		if (DISABLE_FLASH_WHEN_LOW_BATTERY) {
			return (mBatteryLevel != 0 && mBatteryLevel > ENABLE_FLASH_BATTERY_LEVEL) ? true : false;
		} else{
			return true;
		}
	}

	/**
	 * Battery receiver
	 */
	private final BroadcastReceiver mBatteryReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if (action.equals(Intent.ACTION_BATTERY_CHANGED)) {
				final int level = intent.getIntExtra("level", 0);
				Log.i(TAG, "BroadcastReceiver, current level = " + level + ", last level = " + mLastBatteryLevel);
				mBatteryLevel = level;
				// Make sure BATTERY_LOWER_LEVEL > ENABLE_FLASH_BATTERY_LEVEL
				if (BATTERY_LOWER_LEVEL < ENABLE_FLASH_BATTERY_LEVEL)
					return;
				new Runnable() {
					@Override
					public void run() {
						if (mBatteryLowListener != null) {
							if (mLastBatteryLevel == -1) {
								// only first time of activity created.
								checkBetteryFirst(level);
							} else if (mLastBatteryLevel == (ENABLE_FLASH_BATTERY_LEVEL + 1) && level == ENABLE_FLASH_BATTERY_LEVEL) {
								if (DISABLE_FLASH_WHEN_LOW_BATTERY) {
									// from 16% to 15%
									mBatteryLowListener.onFlashEnabled(false);
									Log.i(TAG, "BroadcastReceiver, onFlashEnabled false 2");
								}
							} else if (mLastBatteryLevel == (ENABLE_FLASH_BATTERY_LEVEL + 1) && level == ENABLE_FLASH_BATTERY_LEVEL) {
								if (DISABLE_FLASH_WHEN_LOW_BATTERY) {
									// from 15% to 16%
									mBatteryLowListener.onFlashEnabled(true);
									Log.i(TAG, "BroadcastReceiver, onFlashEnabled true 2");
								}
							} else if (mLastBatteryLevel == BATTERY_LOWER_LEVEL && level == BATTERY_LOWER_LEVEL - 1) {
								// from 20% to 19%
								if (/*ADVISE_FLASH_MODE_IN_LOW_BATTERY*/true) {
									// Close flash mode and show toast in photo module or video module
									mBatteryLowListener.onBatteryLowChanged();
									Log.i(TAG, "BroadcastReceiver, low battery advice 2");
								}
							}
						}
					}
				};
				mLastBatteryLevel = level;
			}
		}
	};

	private void checkBetteryFirst(int curLevel) {
		if (curLevel <= ENABLE_FLASH_BATTERY_LEVEL) {
			if (DISABLE_FLASH_WHEN_LOW_BATTERY) {
				mBatteryLowListener.onFlashEnabled(false);
				Log.i(TAG, "BroadcastReceiver, onFlashEnabled false 0");
			} else {
				if (/*ADVISE_FLASH_MODE_IN_LOW_BATTERY*/true) {
					if (curLevel < BATTERY_LOWER_LEVEL) {
						// Close flash mode and show toast in photo module or video module
						mBatteryLowListener.onBatteryLowChanged();
						Log.i(TAG, "BroadcastReceiver, low battery advice 0");
					}
				}
			}
		} else {
			if (DISABLE_FLASH_WHEN_LOW_BATTERY) {
				mBatteryLowListener.onFlashEnabled(true);
				Log.i(TAG, "BroadcastReceiver, onFlashEnabled true 1");
			}
			if (/*ADVISE_FLASH_MODE_IN_LOW_BATTERY*/true) {
				if (curLevel < BATTERY_LOWER_LEVEL) {
					// Close flash mode and show toast in photo module or video module
					mBatteryLowListener.onBatteryLowChanged();
					Log.i(TAG, "BroadcastReceiver, low battery advice 1");
				}
			}
		}
	}


	private class BatteryLowListener implements OnBatteryLowListener {
		@Override
		public void onBatteryLowChanged() {

		}

		@Override
		public void onFlashEnabled(boolean enable) {

			if (!enable) {
				killFlashlight();
				mCameraAvailable = false;
			}else{
				mCameraAvailable = true;
			}

		}
	}

}

