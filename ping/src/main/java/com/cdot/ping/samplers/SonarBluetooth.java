/*
 * Copyright © 2020 C-Dot Consultants
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.cdot.ping.samplers;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;

import com.cdot.ping.BuildConfig;
import com.cdot.ping.R;

import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import no.nordicsemi.android.ble.callback.FailCallback;
import no.nordicsemi.android.ble.callback.SuccessCallback;
import no.nordicsemi.android.ble.callback.profile.ProfileDataCallback;
import no.nordicsemi.android.ble.data.Data;
import no.nordicsemi.android.ble.observer.ConnectionObserver;

/**
 * Bluetooth sample handlers
 * Isolates the sonar handling from the Bluetooth implementation (SonarBLE or SonarClassic)
 */
public class SonarBluetooth implements ConnectionObserver {
    public static final String TAG = SonarBluetooth.class.getSimpleName();

    // Bluetooth state, set by the ConnectionObserver callbacks. These values are used to
    // index a resource array.
    public static final int BT_STATE_DISCONNECTED = 0;
    public static final int BT_STATE_CONNECTING = 1;
    public static final int BT_STATE_CONNECTED = 2;
    public static final int BT_STATE_READY = 3;
    public static final int BT_STATE_DISCONNECTING = 4;
    public static final int BT_STATE_CONNECT_FAILED = 5;


    public static final String BT_DEVICE_NAME = "Erchang Fish";
    public static enum BtDeviceType {
        PING_ORIGINAL,
        F68
    }
    public static final BtDeviceType BT_DEVICE_TYPE = BtDeviceType.F68;

    // UUIDs
    public static final UUID SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID SAMPLE_CHARACTERISTIC_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    public static final UUID CONFIGURE_CHARACTERISTIC_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");
    // Messages sent by the service
    static final String CLASS_NAME = SonarBLE.class.getCanonicalName();
    public static final String ACTION_BT_STATE = CLASS_NAME + ".action_bt_state";
    // Message extras
    public static final String EXTRA_DEVICE = CLASS_NAME + ".device";
    public static final String EXTRA_STATE = CLASS_NAME + ".state";
    public static final String EXTRA_REASON = CLASS_NAME + ".reason";
    // Minimum depth change between recorded samples
    static final float MINIMUM_DELTA_DEPTH_DEFAULT = 0.5f; // metres
    // Never fired by a real device, this picks up locations from PingTest
    static final UUID LOCATION_CHARACTERISTIC_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    // ID bytes in every packet sent TO or received FROM the sonar unit
    static final byte ID0 = (byte) 'S'; // guessing "S for Sonar"
    static final byte ID1 = (byte) 'F'; // maybe "F for FishFinder"
    // Commands sent TO the sonar unit
    static final byte COMMAND_CONFIGURE = 1;
    // feet to metres. Bloody Americans, wake up and join the 20th Century!
    static final float ft2m = 0.3048f;
    static final float MIN_DELTA_TEMPERATURE = 1.0f; // degrees C
    // Bluetooth connection parameters
    static final int BT_CONNECT_TIMEOUT = 2000;
    static final int BT_CONNECT_RETRIES = 3;
    static final int BT_CONNECT_RETRY_DELAY = 500;
    Sample mLastLoggedSample = null;
    double mRawSampleRate;
    int mBluetoothState = SonarBluetooth.BT_STATE_DISCONNECTED;
    int mBluetoothStateReason = ConnectionObserver.REASON_UNKNOWN;
    // The logging service we're sampling for
    LoggingService mService;
    // Will be set true on startup and when logging is turned on
    boolean mMustLogNextSample = true;
    BTImplementation mImplementation;
    private float mMinDeltaDepth = SonarBluetooth.MINIMUM_DELTA_DEPTH_DEFAULT;
    // Set true if a location packet is received from PingTest - after it is set true, no more samples
    // will be accepted from LocationService
    private boolean mLocationsFromPingTest = false;
    // The most recent location given to the sampler, must never be null
    private Location mCurrentLocation = new Location(TAG);
    // The location most recently written to the log
    private Location mLastLoggedLocation = mCurrentLocation;
    // Min location change before a sample update will be fired
    private float mMinDeltaPos = 1; //m
    // Activity timeout
    private Timer mTimeoutTimer = null;
    private boolean mSampleReceived = true; // has a sample been seen since last timeout check?
    private int mSampleTimeout = 0; // must get another sample within this timeout, or we'll disconnect
    private boolean mTimedOut = false;
    // Sampling statistics
    private long mLastSampleTime;
    private long mTotalSamplesReceived;

    SonarBluetooth(LoggingService service, BTImplementation impl) {
        mService = service;
        mImplementation = impl;
        impl.setCommon(this);
    }

    // Convert a double encoded in two bytes as realpart/fracpart to a double
    private static float b2f(byte real, byte frac) {
        int r = (int) real & 0xFF, f = (int) frac & 0xFF;
        return ((float) r + (float) f / 100.0f);
    }

    public void connect(BluetoothDevice device) {
        Log.d(TAG, "Initiating connect request " + device.getName());
        resetSampleRate();
        mImplementation.connectToDevice(device)
                .done(dev -> Log.d(TAG, "Connection to " + device.getName() + " done"))
                .fail((dev, e) -> Log.e(TAG, "Connection to " + device.getName() + " failed " + e))
                .enqueue();
    }

    public void disconnect() {
        mImplementation.disconnectFromDevice()
                .done(device -> Log.d(TAG, "Disconnected from " + (device != null ? device.getName() : "null")))
                .enqueue();
    }

    public void close() {
        mImplementation.close();
        mImplementation = null;
    }

    public int getConnectionState() {
        return mImplementation.getConnectionState();
    }

    public BluetoothDevice getBluetoothDevice() {
        return mImplementation.getBluetoothDevice();
    }

    void resetSampleRate() {
        mLastSampleTime = System.currentTimeMillis();
        mRawSampleRate = 0;
        mTotalSamplesReceived = 0;
        mTimedOut = false;
    }

    private void broadcastStateChange(int state, int reason) {
        mBluetoothState = state;
        mBluetoothStateReason = reason;
        broadcastStatus();
    }

    void broadcastStatus() {
        Intent intent = new Intent(SonarBluetooth.ACTION_BT_STATE);
        intent.putExtra(SonarBluetooth.EXTRA_STATE, mBluetoothState);
        intent.putExtra(SonarBluetooth.EXTRA_DEVICE, mImplementation.getBluetoothDevice());
        intent.putExtra(SonarBluetooth.EXTRA_REASON, mBluetoothStateReason);
        mService.sendBroadcast(intent);
    }

    private void broadcastStateChange(int state) {
        broadcastStateChange(state, REASON_UNKNOWN);
    }

    @Override // ConnectionObserver
    public void onDeviceConnecting(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceConnecting");
        broadcastStateChange(SonarBluetooth.BT_STATE_CONNECTING);
    }

    @Override // ConnectionObserver
    public void onDeviceConnected(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceConnected");
        broadcastStateChange(SonarBluetooth.BT_STATE_CONNECTED);
    }

    @Override // ConnectionObserver
    public void onDeviceFailedToConnect(@NonNull BluetoothDevice device, int reason) {
        Log.d(TAG, "onDeviceFailedToConnect");
        cancelTimeout();
        broadcastStateChange(SonarBluetooth.BT_STATE_CONNECT_FAILED, reason);
    }

    @Override // ConnectionObserver
    public void onDeviceReady(@NonNull BluetoothDevice device) {
        Log.d(TAG, "onDeviceReady");
        startTimeout();
        resetSampleRate();
        mService.playSound(R.raw.ping);
        broadcastStateChange(SonarBluetooth.BT_STATE_READY);
    }

    @Override // ConnectionObserver
    public void onDeviceDisconnecting(@NonNull BluetoothDevice device) {
        // TODO: This is the first indication we have that something is wrong. First thing to know is if
        // the disconnection was the result of a user action - shutting down logging, for example.
        // If it isn't, then we want to start the process
        // of reconnection.
        Log.d(TAG, "onDeviceDisconnecting " + mTimedOut);
        cancelTimeout();
        broadcastStateChange(SonarBluetooth.BT_STATE_DISCONNECTING);
    }

    @Override // ConnectionObserver
    public void onDeviceDisconnected(@NonNull BluetoothDevice device, int reason) {
        Log.d(TAG, "onDeviceDisconnected " + mTimedOut);
        cancelTimeout();
        // ConnectionObserver.REASON_TIMEOUT really means a connect timeout. Overloading it here
        // to also mean "device has gone quiet"
        broadcastStateChange(SonarBluetooth.BT_STATE_DISCONNECTED,
                mTimedOut ? ConnectionObserver.REASON_TIMEOUT : ConnectionObserver.REASON_UNKNOWN);
        mService.playSound(R.raw.boom);
        connect(device);
    }

    // Disconnect timeout. If we don't get another sample within a timeout period, disconnect.
    private void startTimeout() {
        if (mSampleTimeout <= 0 || mTimeoutTimer != null)
            return; // no timeout
        if (mTimeoutTimer != null)
            mTimeoutTimer.cancel();
        mTimeoutTimer = new Timer(true);
        mTimeoutTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!mSampleReceived) {
                    Log.d(TAG, "Sample collection timed out, trying disconnect-reconnect");
                    mTimedOut = true;
                    // Try to disconnect and reconnect
                    mImplementation.disconnectFromDevice()
                            .done((BluetoothDevice device) -> {
                                connect(device);
                            })
                            .fail((dev, state) -> Log.e(TAG, "Disconnect failed " + state))
                            .enqueue();
                }
                mSampleReceived = false;
            }
        }, mSampleTimeout, mSampleTimeout);
    }

    private void cancelTimeout() {
        if (mTimeoutTimer == null)
            return;
        mTimeoutTimer.cancel();
        mTimeoutTimer = null;
    }

    /**
     * Location being set from LocationSampler (view LoggingService)
     *
     * @param loc location to set
     */
    void setLocation(Location loc) {
        if (!mLocationsFromPingTest)
            mCurrentLocation = loc;
    }

    /**
     * Configuration. The first three parameters are sent to the sonar device, the others configure
     * this module.
     *
     * @param sensitivity   1..10
     * @param noise         filtering 0..4 (off, low, med, high)
     * @param range         0..6 (3, 6, 9, 18, 24, 36, auto)
     * @param minDeltaDepth min depth change, in metres
     * @param minDeltaPos   min location change, in metres
     * @param sampleTimeout timeout waiting for a sample before we abandon the connection and try a different device. 0 means never.
     */
    void configure(int sensitivity, int noise, int range, float minDeltaDepth, float minDeltaPos, int sampleTimeout) {
        Log.d(TAG, "configure(" + sensitivity + "," + noise + "," + range + "," + minDeltaDepth + ")");
        mMinDeltaDepth = minDeltaDepth;
        mMinDeltaPos = minDeltaPos;

        cancelTimeout();
        mSampleTimeout = sampleTimeout;

        if (BT_DEVICE_TYPE == BtDeviceType.PING_ORIGINAL) {
            // reverse-engineered by sniffing packets sent by the official FishFinder software
            byte[] data = new byte[]{
                    // http://ww1.microchip.com/downloads/en/DeviceDoc/50002466B.pdf
                    SonarBluetooth.ID0, SonarBluetooth.ID1, // 0, 1
                    0, 0, SonarBluetooth.COMMAND_CONFIGURE, // 2, 3, 4
                    3, // 5 size
                    (byte) sensitivity, (byte) noise, (byte) range, // 6, 7, 8
                    0, 0, 0 // 9 checksum, 10, 11 might do more, not experimented.
            };
            // Compute checksum
            int sum = 0;
            for (int i = 0; i < 9; i++) {
                sum += data[i];
            }
            data[9] = (byte) (sum & 255);

            mImplementation.sendConfiguration(data);
        } else if (BT_DEVICE_TYPE == BtDeviceType.F68) {
            byte PrefLureFishLamp = 0; // (0)off, (1)on
            byte PrefFrequency = 0; // (0)125k, (1)330k, (2)auto
            Log.d(TAG, "PrefLureFishLamp=" + PrefLureFishLamp);
            byte[] data = new byte[] {
                    SonarBluetooth.ID0, SonarBluetooth.ID1, // 0, 1
                    1,
                    (byte) noise, // [0..3]
                    (byte) sensitivity, // [1..100]
                    PrefLureFishLamp,
                    (byte) range, // [1..7] and default, probably 0
                    PrefFrequency, // 7
                    0, 0, // 8, 9 checksum place holders
            };
            byte checksum = 0;
            for (int i = 0; i < 8; i++) {
                checksum += data[i];
            }
            data[8] = (byte) (checksum & 255);
            data[9] = (byte) 85;

            mImplementation.sendConfiguration(data);
        }


        resetSampleRate();
        startTimeout();
    }

    ProfileDataCallback getSonarHandler() {
        if (BT_DEVICE_TYPE == BtDeviceType.PING_ORIGINAL) {
            return new SonarHandler();
        } else if (BT_DEVICE_TYPE == BtDeviceType.F68) {
            return new SonarHandlerF68();
        } else {
            return null;
        }
    }

    ProfileDataCallback getLocationHandler() {
        return new LocationHandler();
    }

    interface Request {
        Request done(@NonNull final SuccessCallback callback);

        Request fail(@NonNull final FailCallback callback);

        void enqueue();
    }

    // Interface to the code that handles the actual device connection
    interface BTImplementation {
        void sendConfiguration(byte[] data);

        Request connectToDevice(BluetoothDevice device);

        Request disconnectFromDevice();

        void setCommon(SonarBluetooth o);

        BluetoothDevice getBluetoothDevice();

        int getConnectionState();

        void close();
    }

    // Handler for sample notifications coming from the sonar device
    class SonarHandler implements ProfileDataCallback {

        private String report(byte[] bytes) {
            StringBuilder mess = new StringBuilder("Packet ");
            for (int i = 0; i < bytes.length; i++)
                mess.append((i == 0 ? "[" : ",") + ((int) bytes[i] & 0xFF));
            return mess.append("]").toString();
        }

        @Override // ProfileDataCallback
        public void onDataReceived(@NonNull final BluetoothDevice device, @NonNull final Data data) {
            byte[] bytes = data.getValue();
            byte id0 = bytes[0];
            byte id1 = bytes[1];
            if (bytes.length != 18 || id0 != ID0 || id1 != ID1) {
                Log.e(TAG, "Bad signature " + report(bytes));
                //onInvalidDataReceived(device, data);
                return;
            }

            int checksum = 0;
            for (int i = 0; i < 17; i++)
                checksum = (checksum + bytes[i]) & 0xFF;
            int packcs = (int) bytes[17] & 0xFF;
            if (packcs != checksum) {
                // It's ok to ignore this, we will see the trace in the debug but otherwise
                // it won't stop us
                Log.e(TAG, "Bad checksum " + packcs + " != " + checksum + " " + report(bytes));
                return;
            }

            Sample sample = new Sample();

            // bytes[2], bytes[3] unknown, always seem to be 0
            if (bytes[2] != 0) Log.d(TAG, "Mysterious[2] " + report(bytes));
            if (bytes[3] != 0) Log.d(TAG, "Mysterious[3] " + report(bytes));

            if ((bytes[4] & 0xF7) != 0) Log.d(TAG, "Mysterious[4] " + report(bytes));

            sample.time = new Date().getTime();
            boolean isDry = (bytes[4] & 0x8) != 0;

            // bytes[5] unknown, seems to be always 9 (1001)
            if (bytes[5] != 9) Log.d(TAG, "Mysterious[5] " + report(bytes));
            // Convert fish depth to metres. We set a negative depth to flag when the device is out of water
            sample.depth = isDry ? -0.01f : ft2m * b2f(bytes[6], bytes[7]);
            // Data is coming from a byte, so naturally constrained to 255
            // Erchang SW: 30-40, "large weed", 40-50 "medium weed", and 50-60 "small weed". Any
            // value outside these ranges is "no weed". By setting MAX_STRENGTH to 256, we are
            // mapping these to: 11%-15%, 16%-19%, 20%-24%. Whether these percentages are pre-scaled
            // to the depth is unknown, but doesn't really matter for our purposes.
            sample.strength = 100 * ((int) bytes[8] & 0xFF) / 256;
            // Convert fish depth to metres
            sample.fishDepth = ft2m * b2f(bytes[9], bytes[10]);
            // Fish strength is in a nibble, so constrained to the range 0-15. Erchang interprets
            // this as  0 "no fish", 1 "small fish", 2 "medium fish", 3 "large fish or shoal".
            // Any other value is interpreted as "small fish".
            sample.fishStrength = 100 * ((int) bytes[11] & 0xF) / 16;
            // Max battery strength is 6. Scale to an integer percentage
            sample.battery = (byte) (100 * ((bytes[11] >> 4) & 0xF) / 6);
            // Convert temperature to sensible celcius
            sample.temperature = (b2f(bytes[12], bytes[13]) - 32.0f) * 5.0f / 9.0f;

            // bytes[14], bytes[15], bytes[16] always 0
            if (bytes[14] != 0) Log.d(TAG, "Mysterious[14] " + report(bytes));
            if (bytes[15] != 0) Log.d(TAG, "Mysterious[15] " + report(bytes));
            if (bytes[16] != 0) Log.d(TAG, "Mysterious[16] " + report(bytes));
            // bytes[17] is a checksum of bytes[0]..bytes[16]

            //Log.d(TAG, report(data));

            sample.latitude = mCurrentLocation.getLatitude();
            sample.longitude = mCurrentLocation.getLongitude();

            if (mMustLogNextSample
                    // Log if battery level has changed
                    || sample.battery != mLastLoggedSample.battery
                    // Log if temperature has changed enough
                    || Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE
                    // Log if depth has changed enough, and it's not dry
                    || Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth
                    // if we've moved further than the current location accuracy or the target min delta
                    || (mLastLoggedLocation.distanceTo(mCurrentLocation) > mMinDeltaPos)) {

                if (BuildConfig.DEBUG && false) {
                    StringBuilder reason = new StringBuilder();
                    reason.append("Logging sample because ");
                    if (mMustLogNextSample)
                        reason.append("I must");
                    else {
                        if (sample.battery != mLastLoggedSample.battery) reason.append("Battery, ");
                        if (Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE)
                            reason.append("Temperature, ");
                        if (Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth)
                            reason.append("Depth, ");
                        if (mLastLoggedLocation.distanceTo(mCurrentLocation) > mMinDeltaPos)
                            reason.append("Location ").append(mLastLoggedLocation.distanceTo(mCurrentLocation));
                    }
                    Log.d(TAG, reason.toString());
                }

                mMustLogNextSample = false;
                mLastLoggedSample = sample;
                mLastLoggedLocation = new Location(mCurrentLocation);

                if (mService != null)
                    mService.logSample(sample);
            }

            // Tell the timeout we're OK
            mSampleReceived = true;

            long now = System.currentTimeMillis();
            if (now > mLastSampleTime) {
                double samplingRate = 1000.0 / (now - mLastSampleTime);
                mRawSampleRate = ((mRawSampleRate * mTotalSamplesReceived) + samplingRate) / (mTotalSamplesReceived + 1);
                mTotalSamplesReceived++;
                mLastSampleTime = now;
            }
        }
    }


    // Handler for F68 device
    class SonarHandlerF68 implements ProfileDataCallback {

        private int mReceiveMCUDataCount;
        int BTDataTotalLength = 140;
        private int[] mReceiveMCUDataBuff = new int[BTDataTotalLength];

        private String report(byte[] bytes) {
            StringBuilder mess = new StringBuilder("Packet ");
            for (int i = 0; i < bytes.length; i++)
                mess.append((i == 0 ? "[" : ",") + ((int) bytes[i] & 0xFF));
            return mess.append("]").toString();
        }

        @Override
        public void onDataReceived(@NonNull final BluetoothDevice device, @NonNull final Data data) {
            byte[] hexStrArrToBytes = data.getValue();
            int i;
            if (hexStrArrToBytes == null || hexStrArrToBytes.length < 20) {
                return;
            }
            int i2 = this.mReceiveMCUDataCount;
            if (i2 == 0) {
                if (hexStrArrToBytes[0] != 83 || hexStrArrToBytes[1] != 70) {
                    return;
                }
                int i3 = 0;
                while (true) {
                    if (i3 >= 140) {
                        break;
                    }
                    this.mReceiveMCUDataBuff[i3] = 0;
                    i3++;
                }
                for (i = 0; i < 20; i++) {
                    this.mReceiveMCUDataBuff[i] = hexStrArrToBytes[i] & 255;
                }
                this.mReceiveMCUDataCount = 20;
            } else if (i2 < 120) {
                for (int i4 = 0; i4 < 20; i4++) {
                    this.mReceiveMCUDataBuff[this.mReceiveMCUDataCount + i4] = hexStrArrToBytes[i4] & 255;
                }
                this.mReceiveMCUDataCount += 20;
            } else {
                for (int i5 = 0; i5 < 20; i5++) {
                    this.mReceiveMCUDataBuff[this.mReceiveMCUDataCount + i5] = hexStrArrToBytes[i5] & 255;
                }
                this.mReceiveMCUDataCount = 0;
                int[] iArr = this.mReceiveMCUDataBuff;
                if (iArr[0] != 83 || iArr[1] != 70 || iArr[14] != 85 || iArr[135] != 170 || iArr[136] != 85 || iArr[137] != 170 || iArr[138] != 85 || iArr[139] != 170) {
                    return;
                }
                int i6 = 0;
                for (int i7 = 0; i7 < 13; i7++) {
                    i6 += this.mReceiveMCUDataBuff[i7];
                }
                int[] iArr2 = this.mReceiveMCUDataBuff;

                if ((i6 & 255) == iArr2[13]) {
                    int[] iArr3 = this.mReceiveMCUDataBuff;
                    int probeWaterDepth10ft = (iArr3[3] << 8) + iArr3[4];
                    float probeWaterDepth = probeWaterDepth10ft / 32.81f; // 10*ft to m

                    int probeFishDepth = (this.mReceiveMCUDataBuff[5] << 8) + this.mReceiveMCUDataBuff[6];
                    int probeFishSize = this.mReceiveMCUDataBuff[7];

                    byte probeBattery = (byte) this.mReceiveMCUDataBuff[8];

                    int[] iArr5 = this.mReceiveMCUDataBuff;
                    int probeWaterTemperature10F = (iArr5[9] << 8) + iArr5[10];
                    float probeWaterTemperature = ((probeWaterTemperature10F / 10f) - 32) * 5 / 9f; // 10*F to celsius

                    Sample sample = new Sample();
                    sample.time = new Date().getTime();
                    sample.depth = probeWaterDepth;
                    // Max battery strength is 6. Scale to an integer percentage
                    sample.battery = (byte) (100 * probeBattery / 6.0);
                    sample.temperature = probeWaterTemperature;
                    sample.fishDepth = probeFishDepth;
                    sample.fishStrength = probeFishSize;

                    sample.latitude = mCurrentLocation.getLatitude();
                    sample.longitude = mCurrentLocation.getLongitude();

                    if (mMustLogNextSample
                            // Log if battery level has changed
                            || sample.battery != mLastLoggedSample.battery
                            // Log if temperature has changed enough
                            || Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE
                            // Log if depth has changed enough, and it's not dry
                            || Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth
                            // if we've moved further than the current location accuracy or the target min delta
                            || (mLastLoggedLocation.distanceTo(mCurrentLocation) > mMinDeltaPos)) {

                        if (BuildConfig.DEBUG) {
                            StringBuilder reason = new StringBuilder();
                            reason.append("Logging sample because ");
                            if (mMustLogNextSample)
                                reason.append("I must");
                            else {
                                if (sample.battery != mLastLoggedSample.battery)
                                    reason.append("Battery, ");
                                if (Math.abs(sample.temperature - mLastLoggedSample.temperature) >= MIN_DELTA_TEMPERATURE)
                                    reason.append("Temperature, ");
                                if (Math.abs(sample.depth - mLastLoggedSample.depth) >= mMinDeltaDepth)
                                    reason.append("Depth, ");
                                if (mLastLoggedLocation.distanceTo(mCurrentLocation) > mMinDeltaPos)
                                    reason.append("Location ").append(mLastLoggedLocation.distanceTo(mCurrentLocation));
                            }
                            Log.d(TAG, reason.toString());
                        }

                        mMustLogNextSample = false;
                        mLastLoggedSample = sample;
                        mLastLoggedLocation = new Location(mCurrentLocation);

                        if (mService != null)
                            mService.logSample(sample);
                    }

                    // Tell the timeout we're OK
                    mSampleReceived = true;

                    long now = System.currentTimeMillis();
                    if (now > mLastSampleTime) {
                        double samplingRate = 1000.0 / (now - mLastSampleTime);
                        mRawSampleRate = ((mRawSampleRate * mTotalSamplesReceived) + samplingRate) / (mTotalSamplesReceived + 1);
                        mTotalSamplesReceived++;
                        mLastSampleTime = now;
                    }
                }
            }
        }
    }

    // Handler for test location received from PingTest. With a real device this should never fire.
    class LocationHandler implements ProfileDataCallback {
        @Override
        public void onDataReceived(@NonNull BluetoothDevice device, @NonNull Data data) {
            ByteBuffer byteBuffer = ByteBuffer.allocate(Double.BYTES);
            byteBuffer.put(data.getValue(), 0, Double.BYTES);
            byteBuffer.flip();
            mCurrentLocation.setLatitude(byteBuffer.getDouble());
            byteBuffer.clear();
            byteBuffer.put(data.getValue(), Double.BYTES, Double.BYTES);
            byteBuffer.flip();
            mCurrentLocation.setLongitude(byteBuffer.getDouble());
            mLocationsFromPingTest = true;
        }
    }
}
