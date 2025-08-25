package de.kai_morich.simple_usb_terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
@RequiresApi(api = Build.VERSION_CODES.O)
public class SerialService extends Service implements SerialListener {

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private enum QueueType {Connect, ConnectError, Read, IoError}

    private static class QueueItem {
        QueueType type;
        byte[] datas; //maybe make this byte[] instead of ArrayDeque
        Exception e;

        QueueItem(QueueType type) { this.type=type; if(type==QueueType.Read) init(); }
        QueueItem(QueueType type, Exception e) { this.type=type; this.e=e; }
        QueueItem(QueueType type, byte[] data, Exception e) {
            this.type = type;
            this.datas = data;
            this.e = e;
        }

        void init() { datas = new byte[8]; }
    }

    private final Handler mainLooper;
    private final IBinder binder;
    private final Queue<QueueItem> queue1, queue2;

    private SerialSocket socket;
    // The object that wants to be forwarded the events from this service
    private SerialListener listener;
    private boolean connected;
    //private final Object packetLock = new Object();
    private static SerialService instance;

    /**
     * Lifecycle
     */
    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
        queue1 = new ArrayDeque<>();
        queue2 = new ArrayDeque<>();
    }

    public static SerialService getInstance() {
        return instance;
    }

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Api
     */
    public void connect(SerialSocket socket) throws IOException {
        socket.connect(this);
        this.socket = socket;
        connected = true;
    }

    public void disconnect() {
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
        }
    }

    public void write(byte[] data) throws IOException {
        if(!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

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
                print_to_terminal("Log file cleared"); //TODO add this function in with some modifications
            }
        } catch (Exception e) {
            Log.e("SerialService", "Error clearing log file", e);
        }
    }
    /**
     * Debug method to analyze current buffer contents
     */
    /*
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
    */

    public void attach(SerialListener listener) throws IOException{
        if(Looper.getMainLooper().getThread() != Thread.currentThread())
            throw new IllegalArgumentException("not in main thread");
        initNotification();
        cancelNotification();
        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.listener = listener;
        }
        for(QueueItem item : queue1) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        for(QueueItem item : queue2) {
            switch(item.type) {
                case Connect:       listener.onSerialConnect      (); break;
                case ConnectError:  listener.onSerialConnectError (item.e); break;
                case Read:          listener.onSerialRead         (item.datas); break;
                case IoError:       listener.onSerialIoError      (item.e); break;
            }
        }
        queue1.clear();
        queue2.clear();
    }

    public void detach() {
        if(connected)
            createNotification();
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
            nc.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(nc);
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    public boolean areNotificationsEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE;
    }

    private void createNotification() {
        Intent disconnectIntent = new Intent()
                .setPackage(getPackageName())
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent,  flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to "+socket.getName() : "Background Service")
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

    /**
     * SerialListener
     */
    public void onSerialConnect() {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnect();
                        } else {
                            queue1.add(new QueueItem(QueueType.Connect));
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.Connect));
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnectError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.ConnectError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.ConnectError, e));
                    disconnect();
                }
            }
        }
    }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at hundred chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     *
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    /* */
    public void onSerialRead(byte[] data) throws IOException{
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            try {
                                listener.onSerialRead(data);
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
    } /**/
/*
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
*/




    public void onSerialIoError(Exception e) {
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialIoError(e);
                        } else {
                            queue1.add(new QueueItem(QueueType.IoError, e));
                            disconnect();
                        }
                    });
                } else {
                    queue2.add(new QueueItem(QueueType.IoError, e));
                    disconnect();
                }
            }
        }
    }

}