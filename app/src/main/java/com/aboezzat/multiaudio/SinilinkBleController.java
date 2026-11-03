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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/** BLE controller for Sinilink XY amplifier boards (XYV122UE control endpoint). */
public final class SinilinkBleController {
    public interface Listener { void onStatus(String message); }
    private static final UUID WRITE_UUID=UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb");
    private static final UUID NOTIFY_UUID=UUID.fromString("0000ae04-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final Context context;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Queue<byte[]> writeQueue=new ArrayDeque<>();
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar,notifyChar;
    private boolean connected,ready,writing;
    private int connectGeneration;

    public SinilinkBleController(Context c,Listener l){context=c.getApplicationContext();listener=l;}
    private void status(String s){main.post(()->{if(listener!=null)listener.onStatus(s);});}

    public void connect(BluetoothDevice device){
        disconnect();
        if(device==null){status("اختر جهاز Bluetooth أولاً");return;}
        final int generation=++connectGeneration;
        status("جارٍ الاتصال بقناة التحكم XYV122UE عبر BLE…");
        main.postDelayed(()->{
            if(generation==connectGeneration&&!ready&&gatt!=null){
                status("الاتصال لم يكتمل. تأكد أن XYV122UE ظاهر وقريب، وليس XinYi الخاص بالصوت فقط.");
                try{gatt.disconnect();gatt.close();}catch(Exception ignored){}
                gatt=null;connected=false;writeChar=null;notifyChar=null;
            }
        },20000);
        try{
            if(Build.VERSION.SDK_INT>=23)gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            else gatt=device.connectGatt(context,false,callback);
            if(gatt==null)status("تعذر بدء اتصال BLE");
        }catch(SecurityException e){status("صلاحية Bluetooth Connect غير ممنوحة");}
        catch(Exception e){status("فشل بدء اتصال BLE: "+e.getClass().getSimpleName());}
    }

    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int st,int state){
            if(st==BluetoothGatt.GATT_SUCCESS&&state==BluetoothProfile.STATE_CONNECTED){
                connected=true;status("اتصل BLE؛ جارٍ اكتشاف خدمات التحكم…");
                if(!g.discoverServices())status("اتصل BLE لكن تعذر بدء اكتشاف الخدمات");
            }else if(state==BluetoothProfile.STATE_DISCONNECTED){
                connected=false;ready=false;writeChar=null;notifyChar=null;writing=false;writeQueue.clear();
                status(st==BluetoothGatt.GATT_SUCCESS?"انقطع اتصال BLE":"فشل BLE برمز "+st+"؛ الجهاز قد لا يكون قناة Sinilink");
                try{g.close();}catch(Exception ignored){}
                if(gatt==g)gatt=null;
            }else if(st!=BluetoothGatt.GATT_SUCCESS){status("فشل اتصال BLE: "+st);}
        }
        @Override public void onServicesDiscovered(BluetoothGatt g,int st){
            if(st!=BluetoothGatt.GATT_SUCCESS){status("فشل اكتشاف خدمات BLE: "+st);return;}
            writeChar=null;notifyChar=null;
            for(BluetoothGattService service:g.getServices()){
                BluetoothGattCharacteristic w=service.getCharacteristic(WRITE_UUID);
                BluetoothGattCharacteristic n=service.getCharacteristic(NOTIFY_UUID);
                if(w!=null)writeChar=w;if(n!=null)notifyChar=n;
            }
            if(writeChar==null){status("هذا الجهاز لا يحتوي قناة أوامر Sinilink AE10؛ اختَر XYV122UE وليس XinYi.");return;}
            if(notifyChar==null){status("وجدنا AE10 لكن قناة الإشعارات AE04 غير موجودة؛ لا يمكن إكمال تهيئة التطبيق.");return;}
            try{
                if(!g.setCharacteristicNotification(notifyChar,true)){status("تعذر تفعيل إشعارات BLE");return;}
                BluetoothGattDescriptor d=notifyChar.getDescriptor(CCCD_UUID);
                if(d==null){status("قناة الإشعارات موجودة لكن CCCD غير موجود");return;}
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if(!g.writeDescriptor(d))status("تعذر بدء تفعيل إشعارات BLE");
                else status("قناة التحكم موجودة؛ جارٍ تفعيل الإشعارات وتهيئة DH100…");
            }catch(Exception e){status("خطأ تفعيل إشعارات BLE: "+e.getClass().getSimpleName());}
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){
            if(st!=BluetoothGatt.GATT_SUCCESS){status("فشل تفعيل إشعارات BLE: "+st);return;}
            // The Sinilink firmware needs a short delay after CCCD notification enable.
            main.postDelayed(()->initializeProtocol(),2000);
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st){
            writing=false;
            if(st!=BluetoothGatt.GATT_SUCCESS){status("فشل إرسال BLE: "+st);writeQueue.clear();return;}
            writeNext();
        }
        @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int st){
            if(st==BluetoothGatt.GATT_SUCCESS)status("استقبلنا رد التهيئة من DH100؛ جارٍ إنهاء المصافحة…");
            else status("أمر التهيئة أُرسل، لكن قراءة الرد لم تنجح: "+st);
            // Match reference timing: 600 ms before command 1, then 1.2 s between commands.
            main.postDelayed(()->{
                send(hex("7e051e00"));
                main.postDelayed(()->send(hex("7e051f00")),1200);
                main.postDelayed(()->{
                    send(hex("7e052000"));
                    // "ready" means sequence dispatched; it does not confirm speaker-side success.
                    main.postDelayed(()->{ready=true;status("تم إرسال تسلسل تهيئة DH100. استجابة السماعة تحتاج اختبارًا فعليًا.");},1200);
                },2400);
            },600);
        }
    };

    private void initializeProtocol(){
        if(!connected||gatt==null||writeChar==null)return;
        // Sinilink settings handshake uses a two-byte checksum (not the normal one-byte command checksum).
        int token=(int)(System.currentTimeMillis()%255)+1;
        byte[] raw={(byte)0x7e,0x07,(byte)0xaa,0x03,(byte)token};
        int sum=0;for(byte b:raw)sum+=b&255;
        byte[] packet={(byte)0x7e,0x07,(byte)0xaa,0x03,(byte)token,(byte)((sum>>8)&255),(byte)(sum&255)};
        enqueue(packet);
        main.postDelayed(()->{
            try{
                if(gatt!=null&&connected&&writeChar!=null){
                    if(Build.VERSION.SDK_INT>=33)gatt.readCharacteristic(writeChar);
                    else gatt.readCharacteristic(writeChar);
                }
            }catch(Exception e){status("أرسلنا طلب التهيئة لكن قراءة GATT لم تبدأ");}
        },500);
    }

    public boolean isConnected(){return connected&&writeChar!=null;}
    public boolean isReady(){return ready&&isConnected();}

    private byte[] withChecksum(byte[] data){
        int sum=0;for(byte b:data)sum+=b&255;
        byte[] packet=new byte[data.length+1];System.arraycopy(data,0,packet,0,data.length);packet[data.length]=(byte)(sum&255);return packet;
    }
    private void enqueue(byte[] packet){writeQueue.add(packet);writeNext();}
    private void writeNext(){
        if(writing||writeQueue.isEmpty()||gatt==null||writeChar==null||!connected)return;
        byte[] packet=writeQueue.poll();writing=true;
        try{
            if(Build.VERSION.SDK_INT>=33){
                int result=gatt.writeCharacteristic(writeChar,packet,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                if(result!=BluetoothStatusCodes.SUCCESS){writing=false;status("تعذر بدء كتابة BLE: "+result);writeNext();}
            }else{
                writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                writeChar.setValue(packet);
                if(!gatt.writeCharacteristic(writeChar)){writing=false;status("تعذر بدء كتابة BLE");writeNext();}
            }
        }catch(Exception e){writing=false;status("خطأ كتابة BLE: "+e.getClass().getSimpleName());writeNext();}
    }
    public void send(byte[] data){
        if(!isReady()){status(connected?"جارٍ تهيئة DH100؛ انتظر لحظات ثم جرّب":"اتصل بجهاز XYV122UE من زر إدارة الجهاز أولاً");return;}
        enqueue(withChecksum(data));
    }
    public void source(String source){
        if("BT".equals(source))send(hex("7e051400"));
        else if("AUX".equals(source))send(hex("7e051600"));
        else if("USB".equals(source))send(hex("7e050400"));
        else if("TF".equals(source))send(hex("7e050300"));
        else if("Audio Card".equals(source))send(hex("7e051500"));
    }
    public void eq(String mode){
        String[] names={"NORMAL","ROCK","POP","CLASSIC","JAZZ","COUNTRY"};int[] values={9,10,11,12,13,14};
        for(int i=0;i<names.length;i++)if(names[i].equals(mode))send(new byte[]{0x7e,0x05,(byte)values[i],0x00});
    }
    public void playPause(){send(hex("7e050100"));}
    public void previous(){send(hex("7e050700"));}
    public void next(){send(hex("7e050800"));}
    public void promptTone(){send(hex("7e051800"));}
    public void volume(int value){
        // 7E 0F 1D + one volume byte + ten reserved bytes, then checksum.
        byte[] p=new byte[14];p[0]=0x7e;p[1]=0x0f;p[2]=0x1d;p[3]=(byte)Math.max(0,Math.min(30,value));
        send(p);
    }
    public void disconnect(){
        connectGeneration++;connected=false;ready=false;writing=false;writeQueue.clear();
        try{if(gatt!=null){gatt.disconnect();gatt.close();}}catch(Exception ignored){}
        gatt=null;writeChar=null;notifyChar=null;
    }
    private static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}
