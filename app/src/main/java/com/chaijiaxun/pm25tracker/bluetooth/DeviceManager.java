package com.chaijiaxun.pm25tracker.bluetooth;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import com.chaijiaxun.pm25tracker.database.SensorReading;
import com.chaijiaxun.pm25tracker.utils.AppData;
import com.chaijiaxun.pm25tracker.utils.ByteUtils;

import java.util.ArrayList;
import java.util.Date;

/**
 * Manages the device connection to the bluetooth device
 */

public class DeviceManager {
    private static final String TAG = "DeviceManager";
    static private DeviceManager singleton = new DeviceManager();
    static public DeviceManager getInstance() {
        return singleton;
    }
    private Device currentDevice;
    private BTPacketCallback packetCallback;
    private BTDisconnectCallback disconnectCallback;
    private BluetoothService bluetoothService;
    boolean receivedAck;

    // 0 if there is nothing,
    // 1 - 3 if it's connected.
    // Each number represents one missed acknowledgement packet.
    // Once it hits 4 it is assumed the device is lost.
    int connectionStatus = 0;


    final Handler handler = new Handler();
    Runnable aliveCheck;

    ArrayList<Byte> dataQueue;

    private DeviceManager() {
        dataQueue = new ArrayList<>();
        packetCallback = new BTPacketCallback() {
            @Override
            public void packetReceived(BluetoothSocket socket, byte[] data, int bytesReceived) {
                byte[] CRLF = "\r\n".getBytes();
                if ( data != null ) {
                    for (byte b: data) {
                        dataQueue.add(b);

                        int size = dataQueue.size();
                        if ( size > 2 ) {
                            if ( dataQueue.get(size-2) == CRLF[0] && dataQueue.get(size-1) == CRLF[1]) {
                                processPacket(dataQueue);
                                dataQueue.clear();
                            }
                        }
                    }

                    // Find first instance of CRLF
                }
            }
        };

        aliveCheck = new Runnable() {
            @Override
            public void run() {
//                Log.d(TAG, "Alive Check");
                if ( !isDeviceConnected() ) {
                    return;
                }
                if ( !receivedAck ) {
                    Log.d(TAG, "Have not heard from device");
                    connectionStatus++;
                    if ( connectionStatus > 3 ) {
                        unsetCurrentDevice();
                        if (disconnectCallback != null) {
                            disconnectCallback.deviceDisonnected();
                        }
                        AppData.getInstance().setMessageText("Not connected");
                        return;
                    }
                }
                try {
                    //do your code here
                    receivedAck = false;
                    byte [] bytes = {BTPacket.TYPE_CONNECTION_CHECK};
                    bluetoothService.write(bytes);
                }
                catch (Exception e) {
                    // TODO: handle exception
                }
                finally {
                    //also call the same runnable to call it at regular interval
                    handler.postDelayed(this, 5000);
                }
            }
        };
    }

    public void processPacket(ArrayList<Byte> data) {
//        Log.d(TAG, "Process packet");
        byte type = data.get(0);
        byte length = data.get(1);
        connectionStatus = 1; // When packet received set the connection status back to 1
        switch ( type ) {
            case BTPacket.TYPE_CONNECTION_ACK:
//                Log.d(TAG, "Connection still active");
//                Log.d(TAG, "Length: " + length);
//
//                Log.d(TAG, "Actual Length: " + data.size());
                receivedAck = true;
                if ( data.size() > 6 ) {
                    byte [] longData = {
                            data.get(2),
                            data.get(3),
                            data.get(4),
                            data.get(5)
                    };
                    long deviceTimestamp = ByteUtils.arduinoLongTSToAndroidLongTS(longData);
                    currentDevice.setDeviceTime(deviceTimestamp);
//                    Log.d(TAG, "Timestamp " + deviceTimestamp);
                }
                break;
            case BTPacket.TYPE_READING_COUNT:
//                Log.d(TAG, ByteUtils.byteArrayToString(data));
//                Log.d(TAG, "Size: " + data.size());
                if ( data.size() > 4 ) {
                    byte [] countBytes = extract2Bytes(data, 2);
                    int pendingReadings = ByteUtils.arduinoUint16ToAndroidInt(countBytes);
                    Log.d(TAG, "Reading count received: " + pendingReadings);

                    AppData.getInstance().setPacketsLeft(pendingReadings);

                    byte [] timeBytes = ByteUtils.androidLongTSToAndroidLongTS(0);

                    byte [] bytes = new byte[8];
                    bytes[0] = BTPacket.TYPE_READY_TO_RECEIVE;
                    bytes[1] = 6;
                    bytes[2] = timeBytes[0];
                    bytes[3] = timeBytes[1];
                    bytes[4] = timeBytes[2];
                    bytes[5] = timeBytes[3];
                    bytes[6] = data.get(2);
                    bytes[7] = data.get(3);

                    bluetoothService.write(bytes);

                }
                break;
            case BTPacket.TYPE_READING_PACKET:
                AppData.getInstance().decrementPacketsLeft();
                Log.d(TAG, "Packets left: " + AppData.getInstance().getPacketsLeft());
                Log.d(TAG, ByteUtils.byteArrayToString(data));
                Log.d(TAG, "Size: " + data.size());
                if ( data.size() > 25 ) {
                    byte [] timeBytes       = extract4Bytes(data, 2);
                    byte [] readingBytes    = extract4Bytes(data, 2+4);
                    byte [] latBytes        = extract4Bytes(data, 2+8);
                    byte [] lonBytes        = extract4Bytes(data, 2+12);
                    byte [] accBytes        = extract4Bytes(data, 2+16);
                    byte [] eleBytes        = extract4Bytes(data, 2+20);
                    byte microclimate       = data.get(24);

                    long time = ByteUtils.arduinoLongTSToAndroidLongTS(timeBytes);
                    float reading = ByteUtils.byteArrayToFloat(ByteUtils.reverseArray(readingBytes));
                    float lat = ByteUtils.byteArrayToFloat(ByteUtils.reverseArray(latBytes));
                    float lon = ByteUtils.byteArrayToFloat(ByteUtils.reverseArray(lonBytes));
                    float acc = ByteUtils.byteArrayToFloat(ByteUtils.reverseArray(accBytes));
                    float ele = ByteUtils.byteArrayToFloat(ByteUtils.reverseArray(eleBytes));
                    int microclimateInt = (int) microclimate;
//                    Log.d(TAG, time+"\n"+reading+"\n"+lat+"\n"+lon+"\n"+acc+"\n"+ele+"\n"+microclimateInt);

                    SensorReading dbReading = new SensorReading(0, new Date(time), reading, microclimateInt, lat, lon, ele, acc);
                    dbReading.save();
                }
                break;
            case BTPacket.TYPE_MICROCLIMATE_PACKET:
                int microclimate = (int)data.get(2);
                currentDevice.setMicroclimate(microclimate);


                break;
        }
    }


    private byte [] extract2Bytes(ArrayList<Byte> data, int from) {
        byte [] bytes = { data.get(from), data.get(from+1) };
        return bytes;
    }
    private byte [] extract4Bytes(ArrayList<Byte> data, int from) {
        byte [] bytes = { data.get(from), data.get(from+1), data.get(from+2), data.get(from+3) };
        return bytes;
    }
    public void unsetCurrentDevice() {
        if ( bluetoothService != null ) {
            bluetoothService.destroy();
        }
        connectionStatus = 0;
        currentDevice = null;
    }
    public void setCurrentDevice(BluetoothDevice d) {
        setCurrentDevice(new Device(d));
    }

    public void setCurrentDevice(Device d) {
        currentDevice = d;
    }

    public boolean hasLastDevice() {
        return currentDevice != null;
    }

    public boolean isDeviceConnected() {
        return currentDevice != null && bluetoothService != null;
    }

    public Device getCurrentDevice() {
        return currentDevice;
    }

    public BluetoothService getBluetoothService() {
        return bluetoothService;
    }

    public void setBluetoothService(BluetoothSocket socket) {
        this.bluetoothService = new BluetoothService(socket);
        this.bluetoothService.setCallback(packetCallback);
        connectionStatus = 1;
        aliveCheck.run();
    }

    public void setDisconnectCallback(BTDisconnectCallback disconnectCallback) {
        this.disconnectCallback = disconnectCallback;
    }


    public void setMicroclimate(int microclimate) {
        if ( bluetoothService == null || currentDevice == null ) {
            return;
        }
        byte mc = (byte)microclimate;
        byte [] bytes = {BTPacket.TYPE_SET_MICROCLIMATE, mc};
        this.bluetoothService.write(bytes);
    }
}
