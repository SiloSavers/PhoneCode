package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Service that serves as our end of communication with the Gecko device. Because I forked this
 * from a separate demo app to get a jump start, this class probably can be narrowed down.
 * <p>
 * The queues serve as a buffer for the messages received during the times where a socket
 * has been connected to a device, but no UI elements have subscribed to receive those
 * messages yet. As a workaround for now, received packets are parsed here and sent to
 * FirebaseService to one of its methods
 * <p>
 * This means there is some duplicate logic going on in FirebaseService and TerminalFragment,
 * and most likely remains the messiest part of this app. One way to fix this might be to
 * adapt this to have a list of SerialListeners that can subscribe via attach()
 * <p>
 * <p>
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class SerialService extends Service implements SerialListener {

    private enum RotationState {
        IN_BOUNDS_CW,
        IN_BOUNDS_CCW,
        RETURNING_TO_BOUNDS_CW,
        RETURNING_TO_BOUNDS_CCW,
    }

    class SerialBinder extends Binder {
        SerialService getService() {
            return SerialService.this;
        }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        byte[] data;
        Exception e;

        QueueItem(QueueType type, byte[] data, Exception e) {
            this.type = type;
            this.data = data;
            this.e = e;
        }
    }

    private LocalDateTime lastHeadingTime;
    private final Handler mainLooper;
    private Handler motorHandler;
    private final IBinder binder;
    private final Queue<QueueItem> queue1, queue2;

    // The representation of the actual connection
    private SerialSocket socket;
    // The object that wants to be forwarded the events from this service
    private SerialListener uiFacingListener;
    private boolean connected;

    // rotation variables
    private final long motorRotateTime = 500; /*.5 s*/
    private final long motorSleepTime = 10000; /*10 s*/
    private RotationState rotationState = RotationState.IN_BOUNDS_CW;
    private static double headingMin = 0.0;
    //^^ default at 0 but user can set it to what they want.
    private static double headingMax = 360.0;
    //^^default to 360 but rewritten as what user wants.
    private static double MotorHeadingMin = -60;
    //^^ motor limit at -30
    private static double MotorHeadingMax = 420;
    //^^ motor limit at -30
    private static boolean treatHeadingMinAsMax = false;
    //in degrees, if the last time the motor moved less than this amount,
    // we assume the motor has stopped us and it is time to turn around
    private static boolean isMotorRunning = true;

    private final long temperatureInterval = 300000; /*5 min*/
    private Handler temperatureHandler;

    private Handler batteryCheckHandler;

    private BlePacket pendingPacket;
    private byte[] pendingBytes = null;
    private static SerialService instance;

    public static float potAngle = 0.0f;

    //adding to this pushes out the oldest element if the buffer is full, allowing for time series averaging and other functions
    public AngleMeasSeries angleMeasSeries = new AngleMeasSeries(5);

    public static float lastBatteryVoltage = 0.0f;

    private static int  phoneCharge = 0;

    public static String lastCommand;

    public static Boolean shouldbeMoving = false;

    private static long lastEventTime = -1;

    public static final String KEY_STOP_MOTOR_ACTION = "SerialService.stopMotorAction";
    public static final String KEY_MOTOR_SWITCH_STATE = "SerialService.motorSwitchState";
    public static final String KEY_HEADING_RANGE_ACTION = "SerialService.headingRangeAction";
    public static final String KEY_HEADING_RANGE_STATE = "SerialService.headingRangeState";
    public static final String KEY_HEADING_MIN_AS_MAX_ACTION = "SerialService.headingRangePositiveAction";
    public static final String KEY_HEADING_MIN_AS_MAX_STATE = "SerialService.headingRangePositiveState";

    // Packet buffering fields for improved serial communication
    private final Object packetLock = new Object();
    private byte[] packetBuffer = new byte[0];
    private static final int MAX_PACKET_SIZE = 1024; // Adjust based on your protocol
    private static final int MIN_PACKET_SIZE = 4; 
    
    // Processing queue for ordered operations
    private final Queue<Runnable> processingQueue = new LinkedList<>();
    private final Object processingLock = new Object();
    
    // Packet counters
    private int blePacketsProcessed = 0;
    private int angleBatteryPacketsProcessed = 0;
    private int temperaturePacketsProcessed = 0;
    private int knownResponsesProcessed = 0;
    
    // Truncation control
    private boolean truncate = true;
    
    // Packet output format control
    private boolean useDetailedPacketOutput = false;

    public static SerialService getInstance() {
        return instance;
    }

    public static float getBatteryVoltage() { return lastBatteryVoltage; }

    public static float getPhoneChargePercent() { return phoneCharge; }

    public static float getPotAngle() {
        return potAngle;
    }

    /**
     * Get packet processing statistics
     */
    public int getBlePacketsProcessed() { return blePacketsProcessed; }
    public int getAngleBatteryPacketsProcessed() { return angleBatteryPacketsProcessed; }
    public int getTemperaturePacketsProcessed() { return temperaturePacketsProcessed; }
    public int getKnownResponsesProcessed() { return knownResponsesProcessed; }
    public int getTotalPacketsProcessed() { 
        return blePacketsProcessed + angleBatteryPacketsProcessed + temperaturePacketsProcessed + knownResponsesProcessed; 
    }

    /**
     * Get/set packet output format control
     */
    public boolean isUseDetailedPacketOutput() { return useDetailedPacketOutput; }
    public void setUseDetailedPacketOutput(boolean useDetailed) { useDetailedPacketOutput = useDetailed; }

    /**
     * Creates an intent with the input string and passes it to Terminal Fragment, which then prints it
     *
     */
    void print_to_terminal(String input) {
        // Write to log file
        writeToLogFile(input);
        
        // Original terminal printing logic
        Intent intent = new Intent(TerminalFragment.GENERAL_PURPOSE_PRINT);
        intent.putExtra(TerminalFragment.GENERAL_PURPOSE_STRING, input);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    /**
     * Creates an intent with the input string and color, passes it to Terminal Fragment for colored output
     */
    void print_to_terminal(String input, int color) {
        // Write to log file
        writeToLogFile(input);
        
        // Colored terminal printing logic
        Intent intent = new Intent(TerminalFragment.GENERAL_PURPOSE_PRINT_COLORED);
        intent.putExtra(TerminalFragment.GENERAL_PURPOSE_STRING, input);
        intent.putExtra(TerminalFragment.GENERAL_PURPOSE_COLOR, color);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    /**
     * Write message to log file with timestamp
     */
    private void writeToLogFile(String message) {
        try {
            // Get external storage directory
            File externalDir = getExternalFilesDir(null);
            if (externalDir != null) {
                File logFile = new File(externalDir, "serial_debug.log");
                
                // Create timestamp
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));
                String logEntry = timestamp + " - " + message + "\n";
                
                // Append to file
                FileWriter writer = new FileWriter(logFile, true);
                writer.write(logEntry);
                writer.close();
            }
        } catch (IOException e) {
            Log.e("SerialService", "Error writing to log file", e);
        }
    }

    /**
     * Get the path to the debug log file
     */
    public String getLogFilePath() {
        File externalDir = getExternalFilesDir(null);
        if (externalDir != null) {
            File logFile = new File(externalDir, "serial_debug.log");
            return logFile.getAbsolutePath();
        }
        return null;
    }

    /**
     * Clear the debug log file
     */
    public void clearLogFile() {
        try {
            File externalDir = getExternalFilesDir(null);
            if (externalDir != null) {
                File logFile = new File(externalDir, "serial_debug.log");
                if (logFile.exists()) {
                    logFile.delete();
                }
                print_to_terminal("Log file cleared");
            }
        } catch (Exception e) {
            Log.e("SerialService", "Error clearing log file", e);
        }
    }

    /**
     * Debug method to analyze current buffer contents
     */
    public void debugBufferContents() {
        synchronized (packetLock) {
            print_to_terminal("=== BUFFER DEBUG ===");
            print_to_terminal("Buffer size: " + packetBuffer.length);
            if (packetBuffer.length > 0) {
                print_to_terminal("First 32 bytes: " + bytesToHex(Arrays.copyOfRange(packetBuffer, 0, Math.min(32, packetBuffer.length))));
                if (packetBuffer.length > 32) {
                    print_to_terminal("Last 16 bytes: " + bytesToHex(Arrays.copyOfRange(packetBuffer, packetBuffer.length - 16, packetBuffer.length)));
                }
            }
            
            // Search for BLE packet pattern
            int patternPos = findPacketPattern(packetBuffer);
            if (patternPos >= 0) {
                print_to_terminal("BLE Pattern found at position: " + patternPos);
                int packetStart = patternPos - 22;
                int packetEnd = patternPos + 252;
                print_to_terminal("Would extract BLE packet from " + packetStart + " to " + packetEnd);
                
                if (packetStart >= 0 && packetEnd <= packetBuffer.length) {
                    print_to_terminal("BLE packet extraction would be complete");
                } else {
                    print_to_terminal("BLE packet extraction would be incomplete");
                }
            } else {
                print_to_terminal("BLE Pattern not found in buffer");
            }
            
            // Search for angle/battery pattern
            int angleBattPos = findAngleBatteryPattern(packetBuffer);
            if (angleBattPos >= 0) {
                print_to_terminal("Angle/Battery Pattern found at position: " + angleBattPos);
                int packetStart = angleBattPos;
                int packetEnd = angleBattPos + 8;
                print_to_terminal("Would extract angle/battery packet from " + packetStart + " to " + packetEnd);
                
                if (packetEnd <= packetBuffer.length) {
                    print_to_terminal("Angle/Battery packet extraction would be complete");
                } else {
                    print_to_terminal("Angle/Battery packet extraction would be incomplete");
                }
            } else {
                print_to_terminal("Angle/Battery Pattern not found in buffer");
            }
            
            print_to_terminal("=== END BUFFER DEBUG ===");
        }
    }

    /**
     * Display packet processing statistics
     */
    public void displayPacketStatistics() {
        print_to_terminal("=== PACKET STATISTICS ===");
        print_to_terminal("BLE Packets: " + blePacketsProcessed);
        print_to_terminal("Angle/Battery Packets: " + angleBatteryPacketsProcessed);
        print_to_terminal("Temperature Packets: " + temperaturePacketsProcessed);
        print_to_terminal("Known Responses: " + knownResponsesProcessed);
        print_to_terminal("Total Packets: " + getTotalPacketsProcessed());
        print_to_terminal("=== END STATISTICS ===");
    }

    void send_heading_intent() {
        Intent intent = new Intent(TerminalFragment.RECEIVE_HEADING_STATE);
        intent.putExtra(TerminalFragment.RECEIVE_ROTATION_STATE, rotationState.toString());
        intent.putExtra(TerminalFragment.RECEIVE_ANGLE, potAngle);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    void send_battery_intent() {
        Intent intent = new Intent(TerminalFragment.RECEIVE_BATTERY_VOLTAGE);
        intent.putExtra(TerminalFragment.BATTERY_VOLTAGE, lastBatteryVoltage);
        LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
    }

    /**Used to set lastCommand so that the tracker doesn't automatically stop commands sent via UI
     *
     * @param str the command that TerminalFragment is about to send to the gecko
     */
    void setLastCommand(String str) {
        if (str.equals(BGapi.ROTATE_CCW) || str.equals(BGapi.ROTATE_CW) || str.equals(BGapi.ROTATE_STOP)) {
            lastCommand = str;
        }
    }

    static SerialService.RotationState lastRotationState = null;
    /**
     * Checks if the state of the rotate state machine has changed since last time, and if it has,
     * prints the state and decision variables for debugging.
     *
     */
    void rotateRunnable_statePrint(SerialService.RotationState newRotationState) {
        if (lastRotationState == null || lastRotationState != newRotationState) {
            String headingInfo = "currentHeading: "+ potAngle
                    + "\nmin: "+headingMin+"\nmax: "+headingMax
                    + "\nminAsMax: "+treatHeadingMinAsMax
                    + "\nstate: "+rotationState ;

            print_to_terminal(headingInfo);
        }
        lastRotationState = newRotationState;
    }

    // The packaged code sample that moves the motor and checks if it is time to turn around

    private final Runnable rotateRunnable = new Runnable() {

        @Override
        public void run() {

            try {
                if (connected) {

                    double oldHeading = SensorHelper.getMagnetometerReadingSingleDim();
                    String rotateCommand = BGapi.ROTATE_STOP;
                    if (rotationState == RotationState.IN_BOUNDS_CW || rotationState == RotationState.RETURNING_TO_BOUNDS_CW)
                        rotateCommand = BGapi.ROTATE_CW;
                    else
                        rotateCommand = BGapi.ROTATE_CCW;

                    lastCommand = rotateCommand;

                    //start rotation
                    write(TextUtil.fromHexString(rotateCommand));
                    shouldbeMoving = true;

                    //wait motorRotateTime, then stop rotation
                    motorHandler.postDelayed(() -> {
                        try {
                            shouldbeMoving = false;
                            write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                            lastCommand = BGapi.ROTATE_STOP;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, motorRotateTime);


                    //previous code to swivel the motor indefinitely
//                    double currentHeading = SensorHelper.getHeading(); //+180;
                    double currentHeading = potAngle;

                    //valid range goes around 0, such as 90->120
                    //where ---- is out of bounds and ==== is in bounds,
                    //and <-< or >-> marks the current heading and direction
                    // 0<----|========|----->360
                    switch (rotationState) {
                        case IN_BOUNDS_CW: // 0<--|====== >-> ====|-->360
                            // turn around once we pass the max
                            if (OutsideUpperBound(currentHeading)) {
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;
                            } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case IN_BOUNDS_CCW: // 0<--|== <-< ======|-->360
                            // turn around once we pass the min
                            if (OutsideLowerBound(currentHeading)) {
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;
                            } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside bounds
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case RETURNING_TO_BOUNDS_CW: // 0<-- >-> |========|-->360
                            // set to back in bounds after passing the min
                            //   and continue CW
                            if (InsideBounds(currentHeading)) {
                                rotationState = RotationState.IN_BOUNDS_CW;
                            } else if (OutsideUpperBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CCW;  // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                        case RETURNING_TO_BOUNDS_CCW: // 0<--|======| <-< -->360
                            // set back to in bounds after passing the max
                            //   and continue CCW
                            if (InsideBounds(currentHeading)) {
                                rotationState = RotationState.IN_BOUNDS_CCW;
                            } else if (OutsideLowerBound(currentHeading)) {             // if it gets off, make sure it knows it's outside the other bound
                                rotationState = RotationState.RETURNING_TO_BOUNDS_CW;   // and set it on a course towards what is most likely the nearest bound
                            }
                            break;
                    }

//                    System.out.println("About to write headings to firebase service companion");



                    if (lastHeadingTime == null) {
                        lastHeadingTime = LocalDateTime.now();
                    }

                    // Get GPS data from LocationBroadcastReceiver
                    String latitude = "0.0";
                    String longitude = "0.0";
                    if (de.kai_morich.simple_usb_terminal.LocationBroadcastReceiver.currentLocation != null) {
                        latitude = String.valueOf(de.kai_morich.simple_usb_terminal.LocationBroadcastReceiver.currentLocation.getLatitude());
                        longitude = String.valueOf(de.kai_morich.simple_usb_terminal.LocationBroadcastReceiver.currentLocation.getLongitude());
                        Log.d("SerialService", "GPS data available: lat=" + latitude + ", lon=" + longitude);
                    } else {
                        Log.d("SerialService", "GPS data not available, using default values");
                    }

                    // Get sensor data
                    float[] accelData = SensorHelper.getAccelerometerReadingThreeDim();
                    float[] magData = SensorHelper.getMagnetometerReadingThreeDim();
                    float[] gyroData = SensorHelper.getGyroscopeReadingThreeDim();

                    String headingStr = String.join(", ",
                            lastHeadingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss")),
                            String.valueOf(currentHeading),
                            latitude,
                            longitude,
                            String.valueOf(accelData[0]) + "," + String.valueOf(accelData[1]) + "," + String.valueOf(accelData[2]),
                            String.valueOf(magData[0]) + "," + String.valueOf(magData[1]) + "," + String.valueOf(magData[2]),
                            String.valueOf(gyroData[0]) + "," + String.valueOf(gyroData[1]) + "," + String.valueOf(gyroData[2]),
                            String.valueOf(headingMin),
                            String.valueOf(headingMax),
                            String.valueOf(treatHeadingMinAsMax),
                            String.valueOf(oldHeading),
                            rotationState.toString(),
                            "\n"
                    );

                    FirebaseService.Companion.getServiceInstance().appendHeading(headingStr);
                    Log.d("SerialService", "Heading data written to Firebase: " + headingStr.substring(0, Math.min(headingStr.length(), 100)) + "...");
//                    System.out.println("Wrote headings to firebase service companion: " + headingStr);

                }

                rotateRunnable_statePrint(rotationState);

                //As long as we are to continue moving, schedule this method to be run again
                if (isMotorRunning) {
                    motorHandler.postDelayed(this, motorSleepTime);
                }
            } catch (IOException e) {
                Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }


        }

        // for treatHeadingMinAsMax == false
        private boolean InsideBounds(double heading) {
            return (heading <= headingMax && heading >= headingMin);
        }

        private boolean OutsideLowerBound(double heading) {
            return (heading >= MotorHeadingMin && heading < headingMin);
        }

        private boolean OutsideUpperBound(double heading) {
            //if(heading < 366)
            return (heading > headingMax && heading < MotorHeadingMax);
        }

        // for treatHeadingMinAsMax == true
        private boolean InsideUpperBound(double heading) {
            return (heading >= headingMax && heading < MotorHeadingMax);
        }

        private boolean InsideLowerBound(double heading) {
            return (heading >= MotorHeadingMin && heading <= headingMin);
        }

        private boolean OutsideBounds(double heading) {
            return (heading > headingMin && heading < headingMax);
        }

    };

    private final Runnable temperatureRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                write(TextUtil.fromHexString(BGapi.GET_TEMP));
                Toast.makeText(getApplicationContext(), "Asked for temp", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                e.printStackTrace();
            }
            temperatureHandler.postDelayed(this, temperatureInterval);
        }
    };

    private static BatteryManager bm;

    private final Runnable batteryCheckRunnable = new Runnable() { //written by GPT 3.5 with prompts from Coby's code

        @Override
        public void run() {
            bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            phoneCharge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            print_to_terminal("Read Phone battery level: " + phoneCharge);
            System.out.print("Battery level " +  String.valueOf(phoneCharge) + "\n");

            Log.d("BatteryLevel", String.valueOf(phoneCharge));

            batteryCheckHandler.postDelayed(batteryCheckRunnable, 60 * 1000); //delay
        }
    };


    /**
     * Lifecycle
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new LinkedList<>();
        queue2 = new LinkedList<>();

        instance = this;

        startRotationHandler();
        startTemperatureHandler();
        startBatteryCheckHandler();
    }

    /**
     * Called by the system when another part of this app calls startService()
     * Shows the notification that is required by the system to signal that we will be
     * using constant access to system resources and sensors
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        createNotification();

        return START_STICKY;
    }

    /**
     * Create the Handler that will regularly call the code in rotateRunnable
     */
    private void startRotationHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            motorHandler = new Handler(looper);
            motorHandler.post(rotateRunnable);
        }
    }

    private void startTemperatureHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            temperatureHandler = new Handler(looper);
            temperatureHandler.postDelayed(temperatureRunnable, 5000);
        }
    }

    private void startBatteryCheckHandler() {
        Looper looper = Looper.myLooper();
        if (looper != null) {
            batteryCheckHandler = new Handler(looper);
            batteryCheckHandler.post(batteryCheckRunnable);
        }
    }

    /**
     * Called by the system hopefully never since the app should never die
     */
    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    /**
     * Inherited from Service
     * Called when a Fragment or Activity tries to bind to this service
     * in order to communicate with it
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    //endregion

    //region API

    /**
     * Called by TerminalFragment after it has created a SerialSocket and given it the details
     * necessary to open a connection to a USB serial device
     * Connects to the device
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    /**
     * Disconnect from the USB serial device and remove the socket
     */
    public void disconnect() {
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if (socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    /**
     * Write data to the USB serial device through the socket
     * Throws IOException if not currently connected to a device
     */
    public void write(byte[] data) throws IOException {
        if (!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    /**
     * Subscribe to any serial events that occur from the connected device
     * May immediately send events that were queued since last connection
     * <p>
     * This method is expected to be used by UI elements i.e. TerminalFragment
     */
    public void attach(SerialListener listener) throws IOException {
        //Not entirely sure why this is necessary
        if (Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.uiFacingListener = listener;
        }
        // queue1 will contain all events that posted in the time between disconnecting and detaching
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.data); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        // queue2 will contain all events that posted after detaching
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.data); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if (connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        uiFacingListener = null;
    }

    /**
     * Creates and configures the constant notification required by the system
     * Then shows this notification and promotes this service to a ForegroundService
     */
    private void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }

        Intent disconnectIntent = new Intent()
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to " + socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_clear_white_24dp, "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        stopForeground(true);
    }

    //endregion

    //region SerialListener

    /**
     * Each of these methods either forwards the event on to the listener that subscribed via
     * attach(), or queues it to be forwarded when a listener becomes available again
     * <p>
     * With the exception of onSerialRead, which also parses the data and sends packets
     * to FirebaseService
     */

    public void onSerialConnect() {
        if (connected) {
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect, null, null));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect, null, null));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        if (connected) {
            FirebaseService.Companion.getInstance().appendFile(e.getMessage() + "\n");
            FirebaseService.Companion.getInstance().appendFile(Log.getStackTraceString(e) + "\n");
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, null, e));
                            cancelNotification();
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, null, e));
                    cancelNotification();
                    disconnect();
                }
            }
        }
    }

    public void onSerialRead(byte[] data) throws IOException {
        if (connected) {
            // Thread-safe packet buffering
            synchronized (packetLock) {
                // Append new data to buffer
                packetBuffer = appendByteArray(packetBuffer, data);
                
                // Process complete packets from buffer
                while (processNextPacket()) {
                    // Continue processing until no complete packets remain
                }
                
                // Prevent buffer overflow
                if (packetBuffer.length > MAX_PACKET_SIZE) {
                    Log.w("SerialService", "Buffer overflow, clearing buffer");
                    packetBuffer = new byte[0];
                    pendingPacket = null;
                    pendingBytes = null;
                }
            }

            // Forward to UI listener (original logic)
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            try {
                                uiFacingListener.onSerialRead(data);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        } else {
                            queue1.add(new QueueItem(QueueType.Read, data, null));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Read, data, null));
                }
            }
        }
    }

    /**
     * Process the next complete packet from the buffer
     * @return true if a packet was processed, false if no complete packet available
     */
    private boolean processNextPacket() {
        if (packetBuffer.length < MIN_PACKET_SIZE) {
            return false; // Not enough data for even a minimal packet
        }

        // Check for angle/battery response
        int angleBattPatternStart = findAngleBatteryPattern(packetBuffer);
        if (angleBattPatternStart >= 0) {
            return processAngleBatteryResponseWithPattern(angleBattPatternStart);
        }

        // Check for temperature response
        if (BGapi.isTemperatureResponse(packetBuffer)) {
            return processTemperatureResponse();
        }

        // Check for scan report event (BLE packet) using pattern matching
        int patternStart = findPacketPattern(packetBuffer);
        if (patternStart >= 0) {
            return processBlePacketWithPattern(patternStart);
        }
//        else {
//            // Debug: Print buffer info when pattern is not found
//            String debugInfo = "Packet pattern not found - Buffer size: " + packetBuffer.length +
//                             ", First 16 bytes: " + bytesToHex(Arrays.copyOfRange(packetBuffer, 0, Math.min(16, packetBuffer.length)));
//            print_to_terminal(debugInfo);
//        }
        

        
        // Check for known responses
        if (BGapi.isKnownResponse(packetBuffer)) {
            return processKnownResponse();
        }
        
        // If we can't identify the packet type, try to find a known response pattern
        return findAndProcessKnownPattern();
    }

    /**
     * Find the packet pattern in the buffer
     * @param buffer The buffer to search in
     * @return The starting position of the pattern, or -1 if not found
     */
    private int findPacketPattern(byte[] buffer) {
        // Pattern to search for: 02 01 04 05 04 08 42 59 55 F8
        byte[] pattern = {(byte) 0x02, (byte) 0x01, (byte) 0x04, (byte) 0x05, 
                         (byte) 0x04, (byte) 0x08, (byte) 0x42, (byte) 0x59, 
                         (byte) 0x55, (byte) 0xF8};
        
        for (int i = 0; i <= buffer.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (buffer[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
//                print_to_terminal("Pattern found at position " + i + " in buffer of size " + buffer.length);
                return i;
            }
        }
        return -1;
    }

    /**
     * Find the angle/battery response pattern in the buffer
     * @param buffer The buffer to search in
     * @return The starting position of the pattern, or -1 if not found
     */
    private int findAngleBatteryPattern(byte[] buffer) {
        // Pattern to search for: A0 04 FF 00 03
        byte[] pattern = {(byte) 0xA0, (byte) 0x04, (byte) 0xFF, (byte) 0x00, (byte) 0x03};
        
        for (int i = 0; i <= buffer.length - pattern.length; i++) {
            boolean found = true;
            for (int j = 0; j < pattern.length; j++) {
                if (buffer[i + j] != pattern[j]) {
                    found = false;
                    break;
                }
            }
            if (found) {
//                print_to_terminal("Angle/Battery pattern found at position " + i + " in buffer of size " + buffer.length);
                return i;
            }
        }
        return -1;
    }

    /**
     * Process BLE packet using pattern-based detection
     * @param patternStart The starting position of the pattern
     * @return true if packet was processed, false if incomplete
     */
    private boolean processBlePacketWithPattern(int patternStart) {
        int packetStart = patternStart - 22;
        int packetEnd = patternStart + 252;
        
        // Check if we have enough data for the complete packet
        if (packetStart < 0 || packetEnd > packetBuffer.length) {
//            print_to_terminal("Incomplete packet - need positions " + packetStart + " to " + packetEnd +
//                            ", have buffer size " + packetBuffer.length);
            return false; // Keep in buffer, need more data
        }
        
        // Extract the complete packet
        byte[] packetData = Arrays.copyOfRange(packetBuffer, packetStart, packetEnd);
        
        // Debug: Show the extracted packet data
//        print_to_terminal("Extracted packet - Pattern at " + patternStart +
//                         ", Packet range: " + packetStart + " to " + packetEnd +
//                         ", Packet: " + bytesToHex(Arrays.copyOfRange(packetData, 0, packetData.length)));
        
        // Try to parse as BLE packet
        BlePacket packet = BlePacket.parsePacket(packetData);
        if (packet != null && !packet.isComplete()) {
//            print_to_terminal("packet incomplete, packet length = " + packet.getDataLen());
        }
        if (packet != null && packet.isComplete()) {
//            print_to_terminal("Packet appeared to parse successfully");
            // Complete packet found - queue Firebase operation
            final BlePacket finalPacket = packet;
            queueForProcessing(() -> {
                FirebaseService.Companion.getServiceInstance().appendFile(finalPacket.toCSV());
            });
            
            // Remove the processed packet from buffer
            removeFromBuffer(packetEnd);
            blePacketsProcessed++; // Increment counter
            
            // Choose output format based on setting
            String packetString;
            if (useDetailedPacketOutput) {
                // Truncate packet data for display like the old system
                packetString = packet.toString();
                if (truncate) {
                    int length = packetString.length();
                    int lastNewline = packetString.lastIndexOf('\n');
                    if (lastNewline >= 0 && length > lastNewline + 40) {
                        length = lastNewline + 40;
                    }
                    packetString = packetString.substring(0, length) + "…";
                }
            } else {
                // Use simplified format
                packetString = packet.toSimpleString();
            }
            
            print_to_terminal(packetString, Color.MAGENTA);
//            print_to_terminal("BLE packet processed successfully - Pattern at " + patternStart +
//                            ", Packet size: " + packetData.length + " bytes" +
//                            ", Total BLE packets: " + blePacketsProcessed);
            return true;
        } else if (packet != null) {
            // Partial packet - keep it in buffer
            pendingPacket = packet;
//            print_to_terminal("Partial BLE packet - waiting for more data");
            return false;
        } else {
            // Can't parse - might be incomplete, keep in buffer
            pendingBytes = packetData.clone();
//            print_to_terminal("BLE packet parse failed - keeping in buffer");
            return false;
        }
    }

    /**
     * Process angle/battery response using pattern-based detection
     * @param patternStart The starting position of the pattern
     * @return true if packet was processed, false if incomplete
     */
    private boolean processAngleBatteryResponseWithPattern(int patternStart) {
        // Extract 8-byte sequence starting at pattern position
        int packetStart = patternStart;
        int packetEnd = patternStart + 8;
        
        // Check if we have enough data for the complete packet
        if (packetEnd > packetBuffer.length) {
//            print_to_terminal("Incomplete angle/battery packet - need positions " + packetStart + " to " + packetEnd +
//                            ", have buffer size " + packetBuffer.length);
            return false; // Keep in buffer, need more data
        }
        
        // Extract the complete packet
        byte[] packetData = Arrays.copyOfRange(packetBuffer, packetStart, packetEnd);
        
        // Debug: Show the extracted packet data
        //        print_to_terminal("Extracted angle/battery packet - Pattern at " + patternStart +
        //                         ", Packet range: " + packetStart + " to " + packetEnd +
        //                         ", Full packet: " + bytesToHex(packetData));
        
        // Process the angle/battery data
        processAngleBatteryData(packetData);
        
        // Remove only the specific 8-byte packet from buffer
        removeSpecificRangeFromBuffer(packetStart, packetEnd);
        angleBatteryPacketsProcessed++; // Increment counter
        //        print_to_terminal("Angle/Battery packet processed successfully - Pattern at " + patternStart +
        //                         ", Packet size: " + packetData.length + " bytes");
//        print_to_terminal("Angle/Battery packet processed - Total: " + angleBatteryPacketsProcessed);
        return true;
    }

    private boolean processAngleBatteryResponse() {
        // Angle/battery responses should be complete
        if (packetBuffer.length >= 15) { // Minimum size for angle/battery response
            processAngleBatteryData(packetBuffer);
            removeFromBuffer(packetBuffer.length);
//            print_to_terminal("Angle/Battery packet processed - Size: " + packetBuffer.length);
            return true;
        }
//        print_to_terminal("Angle/Battery packet too small - Size: " + packetBuffer.length + " (need >= 15)");
        return false; // Keep in buffer if incomplete
    }

    private boolean processTemperatureResponse() {
        // Temperature responses should be complete
        if (packetBuffer.length >= 9) {
            final int temp = packetBuffer[packetBuffer.length - 2];
            queueForProcessing(() -> {
                FirebaseService.Companion.getServiceInstance().appendTemp(temp);
            });
            removeFromBuffer(packetBuffer.length);
            temperaturePacketsProcessed++; // Increment counter
            print_to_terminal("Temperature packet processed - Size: " + packetBuffer.length + ", Temp: " + temp + ", Total: " + temperaturePacketsProcessed);
            return true;
        }
//        print_to_terminal("Temperature packet too small - Size: " + packetBuffer.length + " (need >= 9)");
        return false;
    }

    private boolean processKnownResponse() {
        // Known responses should be complete
        Object[] result = BGapi.getResponseNameAndPosition(packetBuffer);
        if (result != null) {
            String responseName = (String) result[0];
            int position = (Integer) result[1];
            byte[] responsePattern = BGapi.getKnownResponses().get(responseName);
            
            if (responsePattern != null) {
                // Extract the specific response pattern from the buffer
                int responseStart = position;
                int responseEnd = position + responsePattern.length;
                
                // Remove only the specific response from buffer
                removeSpecificRangeFromBuffer(responseStart, responseEnd);
                
                handleKnownResponse(responseName);
                knownResponsesProcessed++; // Increment counter
//                print_to_terminal("Known response processed: " + responseName + " at position " + position + ", Total: " + knownResponsesProcessed);
                return true;
            }
        }
        return false;
    }

    private boolean findAndProcessKnownPattern() {
        // Look for known response patterns within the buffer
        // For now, we'll keep the original logic for unknown data
        // This could be enhanced later to search for known patterns
        
        // Debug: Print comprehensive buffer info when no packet type is recognized
//        String debugInfo = "No packet type recognized - Buffer size: " + packetBuffer.length +
//                          ", Full buffer: " + bytesToHex(packetBuffer);
//        print_to_terminal(debugInfo);
        
        return false; // Keep in buffer if we can't identify it
    }

    private void processAngleBatteryData(byte[] data) {
        if (data[data.length - 1] == (byte) 0xFF) {
            // Angle data
            byte[] lastTwoBytes = new byte[2];
            System.arraycopy(data, data.length - 3, lastTwoBytes, 0, 2);
            
            int pot_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);
            float pot_voltage = (float) (pot_bits * 0.002);
            potAngle = (float) (((pot_voltage - 0.332) / (2.7 - 0.332)) * 360);
            
            Boolean isMoving = angleMeasSeries.addMeasurementFiltered(potAngle);
            if (isMoving && !shouldbeMoving) {
                try {
                    write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                    System.out.println("Stopped Erroneous Rotation");
                } catch (IOException e) {
                    Log.e("SerialService", "Error stopping motor", e);
                }
            }
            
            lastHeadingTime = LocalDateTime.now();
            send_heading_intent();
            
        } else if (data[data.length - 1] == (byte) 0xF0) {
            // Battery data
            byte[] lastTwoBytes = new byte[2];
            System.arraycopy(data, data.length - 3, lastTwoBytes, 0, 2);
            
            int batt_bits = ((lastTwoBytes[0] & 0xFF) << 4) | ((lastTwoBytes[1] & 0xF0) >>> 4);
            float batt_voltage = ((float) (batt_bits * 0.002)) * 6;
            
            lastBatteryVoltage = batt_voltage;
            System.out.print("Battery voltage was " + batt_voltage + "\n");
        } else {
            Log.w("SerialService", "Unknown angle/battery response flag: " + data[data.length - 1]);
        }
    }

    private void handleKnownResponse(String responseName) {
        if ("message_system_boot".equals(responseName)) {
            try {
                write(TextUtil.fromHexString(BGapi.SCANNER_START));
            } catch (Exception e) {
                Log.e("SerialService", "Error restarting scanner", e);
            }
        } else if ("message_rotate_ccw_rsp".equals(responseName)) {
            if (lastCommand == null || !lastCommand.equals(BGapi.ROTATE_CCW)) {
                if (lastEventTime < 0) {
                    lastEventTime = System.currentTimeMillis();
                    Log.w("SerialService", "Unexpected " + responseName + " received for the first time");
//                } else {
                    long timeElapsed = System.currentTimeMillis() - lastEventTime;
                    lastEventTime = System.currentTimeMillis();
                    Log.w("SerialService", "Unexpected " + responseName + " received after " + timeElapsed/1000 + " seconds");
                }
                try {
                    write(TextUtil.fromHexString(BGapi.ROTATE_STOP));
                } catch (IOException e) {
                    Log.e("SerialService", "Error stopping motor", e);
                }
            }
        }
    }

    private void removeFromBuffer(int length) {
        if (length >= packetBuffer.length) {
            packetBuffer = new byte[0];
        } else {
            packetBuffer = Arrays.copyOfRange(packetBuffer, length, packetBuffer.length);
        }
    }

    /**
     * Remove a specific range of bytes from the buffer
     * @param start Start position (inclusive)
     * @param end End position (exclusive)
     */
    private void removeSpecificRangeFromBuffer(int start, int end) {
        if (start < 0 || end > packetBuffer.length || start >= end) {
            print_to_terminal("Invalid range for buffer removal: " + start + " to " + end + " (buffer size: " + packetBuffer.length + ")");
            return;
        }
        
        // Create new buffer with the range removed
        byte[] newBuffer = new byte[packetBuffer.length - (end - start)];
        
        // Copy bytes before the range
        System.arraycopy(packetBuffer, 0, newBuffer, 0, start);
        
        // Copy bytes after the range
        System.arraycopy(packetBuffer, end, newBuffer, start, packetBuffer.length - end);
        
        packetBuffer = newBuffer;
        
//        print_to_terminal("Removed bytes " + start + " to " + end + " from buffer. New size: " + packetBuffer.length);
    }

    /**
     * Given two byte arrays a,b, returns a new byte array that has appended b to the end of a
     **/
    private byte[] appendByteArray(byte[] a, byte[] b) {
        byte[] temp = new byte[a.length + b.length];
        System.arraycopy(a, 0, temp, 0, a.length);
        System.arraycopy(b, 0, temp, a.length, b.length);
        return temp;
    }

    /**
     * Convert byte array to hex string for debugging
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public void onSerialIoError(Exception e) {
        if (connected) {
            FirebaseService.Companion.getServiceInstance().appendFile(e.getMessage() + "\n");
            FirebaseService.Companion.getServiceInstance().appendFile(Log.getStackTraceString(e) + "\n");
            synchronized (this) {
                if (uiFacingListener != null) {
                    mainLooper.post(() -> {
                        if (uiFacingListener != null) {
                            uiFacingListener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, null, e));
                            cancelNotification();
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, null, e));
                    cancelNotification();
                    disconnect();
                }
            }
        }
    }

    /**
     * A custom BroadcastReceiver that can receive intents from the switch button in TerminalFragment
     * and toggles motor rotation
     * TODO: find a way to interrupt an already scheduled handler so that the
     * motor stops immediately on the switch being pushed
     * (It currently only stops after the next time it rotates)
     */
    public static class ActionListener extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction() != null) {
                if (intent.getAction().equals(KEY_STOP_MOTOR_ACTION)) {
                    isMotorRunning = intent.getBooleanExtra(KEY_MOTOR_SWITCH_STATE, false);
                    if (isMotorRunning) {
                        SerialService.getInstance().startRotationHandler();
                    }
                } else if (intent.getAction().equals(KEY_HEADING_RANGE_ACTION)) {
                    float[] headingLimits = intent.getFloatArrayExtra(KEY_HEADING_RANGE_STATE);
                    if (headingLimits != null && headingLimits.length == 2) {
                        headingMin = headingLimits[0];
                        headingMax = headingLimits[1];
                    }
                } else if (intent.getAction().equals(KEY_HEADING_MIN_AS_MAX_ACTION)) {
                    treatHeadingMinAsMax = !intent.getBooleanExtra(KEY_HEADING_MIN_AS_MAX_STATE, false);
                }
            }
        }
    }

    /**
     * Queue an operation for ordered processing
     */
    private void queueForProcessing(Runnable operation) {
        synchronized (processingLock) {
            processingQueue.offer(operation);
        }
        // Process the queue on the main thread to ensure ordering
        mainLooper.post(this::processQueuedOperations);
    }

    /**
     * Process all queued operations in order
     */
    private void processQueuedOperations() {
        synchronized (processingLock) {
            while (!processingQueue.isEmpty()) {
                Runnable operation = processingQueue.poll();
                if (operation != null) {
                    operation.run();
                }
            }
        }
    }

}

