package de.kai_morich.simple_usb_terminal;

import android.annotation.SuppressLint;
import android.location.Location;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * A class that holds all the details we want out of a BT packet received by the Gecko
 *
 * https://docs.silabs.com/bluetooth/3.2/group-sl-bt-evt-scanner-scan-report
 */

public class BlePacket {
    private LocalDateTime time;
    private double latt;
    private double longg;
    private double magHeading;
    private double potHeading;

    private float battVoltage;

    private float phoneCharge;
    private String addr;
    private byte rssi;
    private byte channel;
    private byte packet_type;
    private byte[] data;

    // IMU data fields
    private float[] accelerometerData;
    private float[] magnetometerData;
    private float[] gyroscopeData;

    /**
     * Constructor that grabs all necessary details about the current state of the device
     *  (datetime, heading, location), and stores them with the details of the packet
     * */
    @SuppressLint("NewApi")
    private BlePacket(String addr, byte rssi, byte channel, byte packet_type, byte[] data) {
        time = LocalDateTime.now();
        magHeading = SensorHelper.getMagnetometerReadingSingleDim();
        potHeading = SerialService.getPotAngle();
        battVoltage = SerialService.getBatteryVoltage();
        phoneCharge = SerialService.getPhoneChargePercent();

        Location location = LocationBroadcastReceiver.Companion.getCurrentLocation();
        if (location != null) {
            latt = location.getLatitude();
            longg = location.getLongitude();
        } else {
            latt = 0;
            longg = 0;
        }

        this.addr = addr;
        this.rssi = rssi;
        this.channel = channel;
        this.packet_type = packet_type;
        this.data = data;

        // Capture all IMU data
        this.accelerometerData = SensorHelper.getAccelerometerReadingThreeDim().clone();
        this.magnetometerData = SensorHelper.getMagnetometerReadingThreeDim().clone();
        this.gyroscopeData = SensorHelper.getGyroscopeReadingThreeDim().clone();
    }

    /**
     * Given a byte[] that is long enough, parses and returns the new organized BlePacket
     *  object representing the data that was received and the state of the device
     *
     * @return the newly created packet, or null if bytes was too short*/
    public static BlePacket parsePacket(byte[] bytes) {
        // https://docs.silabs.com/bluetooth/6.2.0/bluetooth-stack-api/sl-bt-evt-scanner-extended-advertisement-report-s
        // scanner_evt_extended_advertisement_report ->
//        A0 F7 05 01 83
//        address - 52 9D B5 5E 39 90
//        address type - 00
//        bonding - FF
//        primary phy - 04
//        secondary phy - 04
//        adv_sid - 00
//        TX power -7F
//        RSSI -C8
//        Channel - 04
//        periodic interval - 00
//        00 E5

            //251 total bytes in data sent by NCP over serial
            //22 bytes of NCP header
            //36 bytes of GATT header
            //192 bytes of recorded capacitance data


        if (bytes.length < 31)
            return null;
//        if (bytes.length > 600) {
//            System.out.println("parsePacket: rejected overlong input of length " + bytes.length);
//            return null;
//        }
        String addr = "";
        for(int i = 10; i > 4; i--){ //extended BLE UUID
        //for(int i = 9; i > 4; i--){ //Legacy BLE way
        //for(int i = 0; i < 40; i++){//testing
            addr += String.format("%02X", bytes[i]) + ":";
        }
        addr = addr.substring(0, addr.length() - 1);
        byte packet_type = 0;
        byte rssi = bytes[17]; //17 for legacy BLE
        byte channel = bytes[18]; //18 for legacy BLE

        //byte[] data = Arrays.copyOfRange(bytes, 41, 284); //31 to byte.len for legacy BLE
        //int dataStart =0; //for troubleshooting
        int dataStart =32;
        int dataEnd = Math.min(bytes.length, 274);  // Never go past byte 287
        byte[] data = Arrays.copyOfRange(bytes, dataStart, dataEnd);

        return new BlePacket(addr, rssi, channel, packet_type, data);
    }

    /**
     * Adds the contents of bytes to the end of the data of an already existing packet
     * */
    public void appendData(byte[] bytes) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try {
            stream.write(data);
            stream.write(bytes);
            data = stream.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public boolean isComplete() {
        if(data == null)
            return false;
        return data.length >= 241;
    }

    public int getDataLen() {
        if (data == null) {
            return 0;
        }
        return data.length;
    }

    /**
     * Get accelerometer data (x, y, z)
     */
    public float[] getAccelerometerData() {
        return accelerometerData.clone();
    }

    /**
     * Get magnetometer data (x, y, z)
     */
    public float[] getMagnetometerData() {
        return magnetometerData.clone();
    }

    /**
     * Get gyroscope data (x, y, z)
     */
    public float[] getGyroscopeData() {
        return gyroscopeData.clone();
    }

    /**
     * Returns the contents of this packet in a human readable form
     * */
    @NonNull
    @SuppressLint("NewApi")
    @Override
    public String toString() {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"))
                + "\nLat: " + latt
                + "\nLong: " + longg
                + "\nCompass Heading: " + magHeading
                + "\nPotentiometer angle: " + potHeading
                + "\nReceiver Battery Voltage: " + battVoltage
                + "\nPhone Charge Percent" + phoneCharge
                + "\nAddr: " + addr
                + "\nRSSI: " + rssi
                + "\nChannel: " + (channel & 0xFF /*'cast' to unsigned*/)
                + "\nPacket Type: 0x" + String.format("%02X", packet_type)
                + "\nAccelerometer (x,y,z): [" + String.format("%.3f, %.3f, %.3f", accelerometerData[0], accelerometerData[1], accelerometerData[2]) + "]"
                + "\nMagnetometer (x,y,z): [" + String.format("%.3f, %.3f, %.3f", magnetometerData[0], magnetometerData[1], magnetometerData[2]) + "]"
                + "\nGyroscope (x,y,z): [" + String.format("%.3f, %.3f, %.3f", gyroscopeData[0], gyroscopeData[1], gyroscopeData[2]) + "]"
                + "\nData: " + TextUtil.toHexString(data);
    }

    /**
     * Returns a simplified version with just address, RSSI, and truncated data
     * */
    @NonNull
    public String toSimpleString() {
        String dataHex = TextUtil.toHexString(data);
        // Truncate data to first 40 characters
        if (dataHex.length() > 40) {
            dataHex = dataHex.substring(0, 40) + "…";
        }
        return "Addr: " + addr + " | RSSI: " + rssi + 
               " | Mag: [" + String.format("%.1f, %.1f, %.1f", magnetometerData[0], magnetometerData[1], magnetometerData[2]) + "]" +
               " | Acc: [" + String.format("%.1f, %.1f, %.1f", accelerometerData[0], accelerometerData[1], accelerometerData[2]) + "]" +
               " | Gyro: [" + String.format("%.1f, %.1f, %.1f", gyroscopeData[0], gyroscopeData[1], gyroscopeData[2]) + "]" +
               " | Data: " + dataHex;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public String toShortString(){
        return "Datetime: "+time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"))
                +"\nAddr: "+addr
                +"\nData: "+TextUtil.toHexString(data)
                ;
    }

    /**
     * Returns the contents of this packet formatted as a single line for a csv file
     * */
    @SuppressLint("NewApi")
    public String toCSV() {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH:mm:ss"))
                + "," + latt
                + "," + longg
                + "," + potHeading
                + "," + magHeading
                + "," + battVoltage
                + "," + phoneCharge
                + "," + addr
                + "," + rssi
                + "," + (channel & 0xFF)
                + "," + String.format("%.3f,%.3f,%.3f", accelerometerData[0], accelerometerData[1], accelerometerData[2])
                + "," + String.format("%.3f,%.3f,%.3f", magnetometerData[0], magnetometerData[1], magnetometerData[2])
                + "," + String.format("%.3f,%.3f,%.3f", gyroscopeData[0], gyroscopeData[1], gyroscopeData[2])
                + "," + TextUtil.toHexString(data) + "\n";
    }




}
