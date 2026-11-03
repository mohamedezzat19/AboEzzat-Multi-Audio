package com.aboezzat.multiaudio;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_BT = 401;
    private final int bg = Color.rgb(15,18,25), panel = Color.rgb(27,32,43);
    private final int accent = Color.rgb(66,165,245), muted = Color.rgb(170,180,195);
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private LinearLayout devicesBox;
    private TextView status;
    private final Map<String, BluetoothDevice> devices = new LinkedHashMap<>();
    private boolean receiverRegistered;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        BluetoothManager manager = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("AboEzzat Multi Audio", 26, true));
        TextView sub = label("Bluetooth • Equalizer • Audio Sources", 13, false);
        sub.setTextColor(muted); root.addView(sub);

        status = label("Bluetooth: جاهز للفحص", 14, true);
        status.setTextColor(accent); panel(root, status);

        LinearLayout actions = row();
        Button scan = button("بحث عن أجهزة");
        scan.setOnClickListener(v -> startScan());
        Button settings = button("إعدادات Bluetooth");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        actions.addView(scan, weight()); actions.addView(settings, weight()); panel(root, actions);

        header(root, "الأجهزة والسماعات");
        devicesBox = new LinearLayout(this); devicesBox.setOrientation(LinearLayout.VERTICAL);
        panel(root, devicesBox); renderDevices();

        header(root, "المعادل الصوتي EQ");
        TextView info = label("واجهة أولية للمعادل. سنربطه بمحرك تشغيل داخل التطبيق لاحقًا؛ لا يضمن تعديل صوت النظام كله.", 12, false);
        info.setTextColor(muted); panel(root, info);
        eq(root, "Bass"); eq(root, "Low"); eq(root, "Mid"); eq(root, "High"); eq(root, "Treble");

        header(root, "مصادر الصوت");
        LinearLayout sources = row();
        for (String source : new String[]{"Bluetooth","AUX","USB","TF","الهاتف"}) {
            Button b = button(source);
            b.setOnClickListener(v -> toast(source + ": يعتمد على دعم السماعة أو الجهاز"));
            sources.addView(b, weight());
        }
        panel(root, sources);

        header(root, "اسم البلوتوث والحماية");
        Button rename = button("طلب تغيير اسم الهاتف إلى AboEzzat Audio");
        rename.setOnClickListener(v -> renameBluetooth());
        panel(root, rename);
        TextView pin = label("PIN والاقتران يتحكم بهما Android والسماعة. التطبيق لا يستطيع فرض رقم سري عام على السماعات.", 12, false);
        pin.setTextColor(muted); panel(root, pin);

        TextView footer = label("Prototype 0.1 • اكتشاف الأجهزة لا يعني أن بث 3 سماعات مدعوم.", 12, false);
        footer.setTextColor(muted); panel(root, footer);
        updateStatus();
        loadPairedDevices();
    }

    // Show already-paired devices immediately; discovery alone may not return
    // devices that are currently connected or paired.
    private void loadPairedDevices() {
        if (adapter == null) return;
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d != null) devices.put(d.getAddress(), d);
            }
            renderDevices();
            if (!devices.isEmpty()) status.setText("تم عرض الأجهزة المقترنة. اضغط بحث لتحديث القائمة.");
        } catch (SecurityException e) {
            status.setText("اسمح بصلاحية Bluetooth لعرض الأجهزة المقترنة");
        }
    }

    private void startScan() {
        if (adapter == null) { toast("Bluetooth غير متاح"); return; }
        if (!permissionsGranted()) { requestBtPermissions(); return; }
        try {
            if (!adapter.isEnabled()) {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return;
            }
            loadPairedDevices();
            status.setText("جارٍ البحث عن أجهزة Bluetooth…");
            scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanCallback = new ScanCallback() {
                    @Override public void onScanResult(int type, ScanResult result) {
                        BluetoothDevice d = result.getDevice();
                        if (d != null) { devices.put(d.getAddress(), d); runOnUiThread(() -> renderDevices()); }
                    }
                    @Override public void onScanFailed(int error) {
                        runOnUiThread(() -> status.setText("فشل BLE scan: " + error));
                    }
                };
                scanner.startScan(scanCallback);
            }
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(classicReceiver, filter);
            receiverRegistered = true;
            adapter.startDiscovery();
        } catch (SecurityException e) {
            status.setText("صلاحيات Bluetooth غير كافية");
        } catch (Exception e) {
            status.setText("تعذر البحث: " + e.getMessage());
        }
    }

    private final BroadcastReceiver classicReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (!BluetoothDevice.ACTION_FOUND.equals(i.getAction())) return;
            BluetoothDevice d;
            if (Build.VERSION.SDK_INT >= 33) d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            else d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d != null) { devices.put(d.getAddress(), d); renderDevices(); }
        }
    };

    private void renderDevices() {
        if (devicesBox == null) return;
        devicesBox.removeAllViews();
        if (devices.isEmpty()) {
            TextView empty = label("لم تظهر أجهزة بعد. اضغط بحث عن أجهزة.", 13, false);
            empty.setTextColor(muted); devicesBox.addView(empty); return;
        }
        for (BluetoothDevice d : devices.values()) {
            String name = "Bluetooth Device";
            try { if (d.getName() != null && !d.getName().isEmpty()) name = d.getName(); }
            catch (SecurityException ignored) {}
            LinearLayout item = row();
            TextView nameView = label(name + "\n" + d.getAddress(), 12, true);
            item.addView(nameView, new LinearLayout.LayoutParams(0, -2, 1));
            Button manage = button("إدارة");
            manage.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
            item.addView(manage); devicesBox.addView(item);
            View line = new View(this); line.setBackgroundColor(Color.rgb(55,62,75));
            devicesBox.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void eq(LinearLayout root, String name) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        TextView value = label(name + "  0 dB", 13, true);
        SeekBar seek = new SeekBar(this); seek.setMax(24); seek.setProgress(12);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { value.setText(name + "  " + (p-12) + " dB"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(value); box.addView(seek); panel(root, box);
    }

    private void renameBluetooth() {
        if (adapter == null) { toast("Bluetooth غير متاح"); return; }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestBtPermissions(); return;
        }
        try { toast(adapter.setName("AboEzzat Audio") ? "تم إرسال طلب تغيير الاسم" : "النظام رفض تغيير الاسم؛ استخدم إعدادات Bluetooth"); }
        catch (SecurityException e) { toast("النظام لا يسمح بتغيير الاسم من التطبيق"); }
    }

    private boolean permissionsGranted() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                   checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BT);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] p, int[] r) {
        super.onRequestPermissionsResult(requestCode,p,r);
        if (requestCode == REQ_BT && permissionsGranted()) startScan();
        else if (requestCode == REQ_BT) toast("لم تمنح صلاحيات Bluetooth");
    }
    private void updateStatus() {
        if (adapter == null) status.setText("Bluetooth غير مدعوم");
        else try { status.setText(adapter.isEnabled() ? "Bluetooth: يعمل" : "Bluetooth: متوقف"); }
        catch (SecurityException e) { status.setText("Bluetooth يحتاج صلاحية"); }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
        try { if (receiverRegistered) unregisterReceiver(classicReceiver); } catch (Exception ignored) {}
    }

    private void header(LinearLayout root, String s) {
        TextView t = label(s,18,true); t.setPadding(0,dp(16),0,dp(8)); root.addView(t);
    }
    private void panel(LinearLayout root, View v) {
        LinearLayout p = new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(13),dp(12),dp(13),dp(12)); p.setBackgroundColor(panel); p.addView(v);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(8); root.addView(p,lp);
    }
    private LinearLayout row() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,dp(48),1); }
    private Button button(String s) {
        Button b=new Button(this); b.setText(s); b.setTextSize(11); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(40,105,170))); return b;
    }
    private TextView label(String s,int size,boolean bold) {
        TextView t=new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(Color.WHITE);
        if(bold)t.setTypeface(null, android.graphics.Typeface.BOLD); return t;
    }
    private int dp(int x) { return (int)(x*getResources().getDisplayMetrics().density+0.5f); }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
