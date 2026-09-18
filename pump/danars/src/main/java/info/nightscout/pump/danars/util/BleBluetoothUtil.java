package info.nightscout.pump.danars.util;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.UUID;

import androidx.core.app.ActivityCompat;

public class BleBluetoothUtil {
    private static int REQUEST_ENABLE_BT = 10122;
    private static boolean isScanning = false;
    public static String UUID_STRING = "00001101-0000-1000-8000-00805F9B34FB";//串口服务使用一个标准的UUID
    public static final String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";//默认notify 通知通道描述uuid

    public static boolean isBLESupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE);
    }
    //打开蓝牙
    public static void openBleBluetooth(Activity activity) {
        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(activity.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        //请求开启蓝牙
        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                String[] permissions = new String[]{Manifest.permission.BLUETOOTH_CONNECT};
                ActivityCompat.requestPermissions(activity, permissions, REQUEST_ENABLE_BT);
                return;
            }
            activity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
    }

    //扫描蓝牙
    public static void scanBLeBluetooth(Activity activity, ScanCallback scanCallback) {
        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(activity.BLUETOOTH_SERVICE);
        BluetoothLeScanner bluetoothLeScanner = bluetoothManager.getAdapter().getBluetoothLeScanner();
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        bluetoothLeScanner.startScan(scanCallback);
    }

    public static void stopScanBleBluetooth(Activity activity, ScanCallback scanCallback) {
        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(activity.BLUETOOTH_SERVICE);
        BluetoothLeScanner bluetoothLeScanner = bluetoothManager.getAdapter().getBluetoothLeScanner();
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        bluetoothLeScanner.stopScan(scanCallback);
    }
    public static BluetoothDevice getBluetoothDevice(Context context,String address){
        BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return bluetoothManager.getAdapter().getRemoteDevice(address);
    }

    //连接到蓝牙
    public static BluetoothGatt connectToBluetooth(Context context, BluetoothDevice bluetoothDevice, BluetoothGattCallback gattCallback) {

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        BluetoothGatt bluetoothGatt = bluetoothDevice.connectGatt(context, true, gattCallback);
        return bluetoothGatt;
    }
    public static void setNotification(Context context,BluetoothGatt mGatt, BluetoothGattCharacteristic mCharacteristic, boolean mEnable) {
        if (mCharacteristic == null) {
            return;
        }
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean b = mGatt.setCharacteristicNotification(mCharacteristic, mEnable);
        //写通道
        if(mCharacteristic.getUuid().toString().toUpperCase().startsWith("0000FFE9")){
            for(int i=0;i<mCharacteristic.getDescriptors().size();i++){
                if(BleBluetoothUtil.isDescriptorForNotification(mCharacteristic.getDescriptors().get(i))){
                    BluetoothGattDescriptor descriptor = mCharacteristic.getDescriptors().get(i);
                    if(descriptor!=null){
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean setDescriptor = mGatt.writeDescriptor(descriptor);
                        break;
                    }
                }
            }
        }
        //notify接收数据 通道
        if(mCharacteristic.getUuid().toString().toUpperCase().startsWith("0000FFE4")){
            BluetoothGattDescriptor defaultDescriptor = mCharacteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));
            if(defaultDescriptor!=null){
                defaultDescriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                boolean setDescriptor = mGatt.writeDescriptor(defaultDescriptor);
            }
        }
    }
    //读取数据(特征)
    public static void readCharacteristic(Context context,BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGatt bluetoothGatt) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        bluetoothGatt.readCharacteristic(bluetoothGattCharacteristic);
    }
    public static void writeCharacteristic(Context context,BluetoothGattCharacteristic bluetoothGattCharacteristic, BluetoothGatt bluetoothGatt,byte[] data,int writeType) {
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S){
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return ;
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            //android 13以上使用
            bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic,data,writeType);
        }else{
            //android 12以下使用
            bluetoothGattCharacteristic.setWriteType(writeType);
            bluetoothGattCharacteristic.setValue(data);
            boolean b = bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
        }
    }
    //关闭蓝牙连接
    public static void closeConnection(Context context,BluetoothGatt bluetoothGatt) {
        if (bluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            bluetoothGatt.close();
            bluetoothGatt=null;
        }
    }

    //判断是否是通知的描述符
    public static boolean isDescriptorForNotification(BluetoothGattDescriptor descriptor) {
        return descriptor.getUuid().equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                || descriptor.getUuid().equals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
    }
}
