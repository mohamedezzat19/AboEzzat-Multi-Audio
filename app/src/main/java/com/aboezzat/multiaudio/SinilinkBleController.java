package com.aboezzat.multiaudio;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.UUID;

/** BLE command bridge for Sinilink-compatible XY amplifiers. */
public final class SinilinkBleController {
    public interface Listener { void onStatus(String message); }
    private static final UUID WRITE_UUID = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_UUID = UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    private BluetoothGattCharacteristic notifyChar;
    private boolean connected;

    public SinilinkBleController(Context c, Listener l) { context=c.getApplicationContext(); listener=l; }
    private void status(String s) { main.post(() -> { if(listener!=null) listener.onStatus(s); }); }

    public void connect(BluetoothDevice device) {
        disconnect();
        if(device==null) { status("اختر جهاز Bluetooth أولاً"); return; }
        status("جارٍ الاتصال بالسماعة " + device.getAddress());
        try {
            if(Build.VERSION.SDK_INT >= 23) gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            else gatt=device.connectGatt(context,false,callback);
        } catch(SecurityException e) { status("صلاحية Bluetooth Connect غير ممنوحة"); }
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g,int st,int newState) {
            if(st==BluetoothGatt.GATT_SUCCESS && newState==BluetoothProfile.STATE_CONNECTED) {
                connected=true; status("اتصلنا بالسماعة؛ جارٍ اكتشاف خدمات BLE…"); g.discoverServices();
            } else if(newState==BluetoothProfile.STATE_DISCONNECTED) {
                connected=false; writeChar=null; notifyChar=null; status("انقطع اتصال السماعة");
            } else if(st!=BluetoothGatt.GATT_SUCCESS) status("فشل اتصال BLE: "+st);
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int statusCode) {
            if(statusCode!=BluetoothGatt.GATT_SUCCESS) { status("تعذر اكتشاف خدمات السماعة"); return; }
            for(BluetoothGattService s:g.getServices()) {
                BluetoothGattCharacteristic w=s.getCharacteristic(WRITE_UUID);
                BluetoothGattCharacteristic n=s.getCharacteristic(NOTIFY_UUID);
                if(w!=null) writeChar=w; if(n!=null) notifyChar=n;
            }
            if(writeChar==null) status("اتصل BLE لكن لم نجد قناة أوامر Sinilink (AE10)");
            else {
                if(notifyChar!=null) {
                    try {
                        g.setCharacteristicNotification(notifyChar,true);
                        BluetoothGattDescriptor d=notifyChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                        if(d!=null) { d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(d); }
                    } catch(Exception ignored) {}
                }
                status("اتصال Sinilink جاهز لإرسال الأوامر");
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st) {
            status(st==BluetoothGatt.GATT_SUCCESS ? "تم إرسال الأمر إلى السماعة" : "فشل إرسال الأمر: "+st);
        }
    };
    public boolean isConnected(){ return connected && writeChar!=null; }
    public void send(byte[] data) {
        if(!isConnected() || gatt==null) { status("وصل السماعة من زر إدارة الجهاز أولاً"); return; }
        int sum=0; for(byte b:data) sum+=(b&255);
        byte[] packet=new byte[data.length+1]; System.arraycopy(data,0,packet,0,data.length); packet[data.length]=(byte)(sum&255);
        try {
            if(Build.VERSION.SDK_INT>=33) {
                int result=gatt.writeCharacteristic(writeChar,packet,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if(result!=BluetoothStatusCodes.SUCCESS) status("تعذر بدء إرسال الأمر: "+result);
            } else {
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                writeChar.setValue(packet);
                if(!gatt.writeCharacteristic(writeChar)) status("تعذر بدء إرسال الأمر");
            }
        } catch(Exception e) { status("خطأ BLE أثناء إرسال الأمر"); }
    }
    public void source(String source) {
        if("BT".equals(source)) send(hex("7e051400"));
        else if("AUX".equals(source)) send(hex("7e051600"));
        else if("USB".equals(source)) send(hex("7e050400"));
        else if("TF".equals(source)) send(hex("7e050300"));
        else if("Audio Card".equals(source)) send(hex("7e051500"));
    }
    public void eq(String mode) {
        String[] names={"NORMAL","ROCK","POP","CLASSIC","JAZZ","COUNTRY"};
        int[] values={9,10,11,12,13,14};
        for(int i=0;i<names.length;i++) if(names[i].equals(mode)) send(new byte[]{0x7e,0x05,(byte)values[i],0x00});
    }
    public void playPause(){send(hex("7e050100"));}
    public void previous(){send(hex("7e050700"));}
    public void next(){send(hex("7e050800"));}
    public void promptTone(){send(hex("7e051800"));}
    public void volume(int value) {
        byte[] p=new byte[13]; p[0]=0x7e;p[1]=0x0f;p[2]=0x1d;p[3]=(byte)Math.max(0,Math.min(30,value)); send(p);
    }
    public void disconnect() {
        connected=false; writeChar=null; notifyChar=null;
        try { if(gatt!=null) { gatt.disconnect(); gatt.close(); } } catch(Exception ignored) {}
        gatt=null;
    }
    private static byte[] hex(String s) {
        byte[] b=new byte[s.length()/2]; for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16); return b;
    }
}