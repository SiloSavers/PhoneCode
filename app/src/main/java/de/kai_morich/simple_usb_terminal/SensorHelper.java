package de.kai_morich.simple_usb_terminal;

import android.app.Service;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * Service that provides easy access to the current heading of the device.
 * I'm pretty sure that the system will automatically adjust the order of the reported
 * values to stay the same i.e. index 0 will always be the axis perpendicular to the
 * surface of the earth, but I have not 100% verified this
 *
 * After starting this Service via startService, the most up to date heading should always be
 * available via SensorHelper.getHeading()
 *
 * TODO: double check that index 0 is always the axis we want
 * TODO: do I need to deregister sensor event listeners?
 * TODO: May not actually need to be a service?
 * */

public class SensorHelper extends Service implements SensorEventListener {

    static float[] magnetometerReading = new float[3];
    static float[] gyroscopeReading = new float[3];
    static float[] accelerometerReading = new float[3];
    //float[] potentiometerReading = new float[3];
    private static double heading = 0.0;

    /**
     * Fetches the sensors we need and subscribes to updates, using this as the listener
     * */
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    public void onCreate() {
        super.onCreate();
        SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        //we subscribe to updates from the accelerometer, magnetometer, and gyroscope to get the most
        // accurate heading reading possible and full IMU data
        Sensor accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor magnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(this, magnetic, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        if (gyroscope != null) {
            sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL, SensorManager.SENSOR_DELAY_UI);
        }
    }

    /**
     * Inherited from Service
     * Called by the system when another part of the app calls startService
     * */
    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        onCreate();
        return START_STICKY;
    }

    public static void setHeading(float pot_angle)  {
        heading = pot_angle;
    }

    /**
     * Returns the most recent measurement of the axis perpendicular to the surface of the earth
     * (I think)
     * */

    public static double getMagnetometerReadingSingleDim() {
        return heading;
    }

    public static float[] getMagnetometerReadingThreeDim() {
        if (magnetometerReading == null) {
            magnetometerReading = new float[]{0.0f, 0.0f, 0.0f};
        }
        return magnetometerReading.clone();
    }

    public static float[] getAccelerometerReadingThreeDim() {
        if (accelerometerReading == null) {
            accelerometerReading = new float[]{0.0f, 0.0f, 0.0f};
        }
        return accelerometerReading.clone();
    }

    public static float[] getGyroscopeReadingThreeDim() {
        if (gyroscopeReading == null) {
            gyroscopeReading = new float[]{0.0f, 0.0f, 0.0f};
        }
        return gyroscopeReading.clone();
    }

    /**
     * Inherited from SensorEventListener.
     * Called by the system when there is new info from either of the sensors we
     * subscribed to (accelerometer, magnetometer). Does the math and saves the new heading
     * todo: is the math correct?
     * */

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null)
            return;

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            if (event.values.length >= 3) {
                accelerometerReading = event.values.clone();
            }
        }

        else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            if (event.values.length >= 3) {
                magnetometerReading = event.values.clone();
            }
        }

        else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            if (event.values.length >= 3) {
                gyroscopeReading = event.values.clone();
            }
        }

        // Only calculate heading if we have both accelerometer and magnetometer data
        if (accelerometerReading != null && magnetometerReading != null) {
            try {
                float[] rotationMatrix = new float[9];
                if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)) {
                    float[] orientation = SensorManager.getOrientation(rotationMatrix, new float[3]);
                    heading = (Math.toDegrees(orientation[0]));
                }
            } catch (Exception e) {
                // Log error but don't crash
                android.util.Log.e("SensorHelper", "Error calculating heading", e);
            }
        }
    }

    /**
     * Inherited from SensorEventListener
     * Unused
     * */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }


    /**
     * Inherited from Service
     * Unused
     * */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
