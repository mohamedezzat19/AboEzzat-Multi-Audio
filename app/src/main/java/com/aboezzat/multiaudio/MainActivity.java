package com.aboezzat.multiaudio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.os.Handler;
import android.os.Looper;
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
    private boolean scanning;
    private Button scanButton;
    private SinilinkBleController sinilink;
    private BluetoothDevice selectedSpeaker;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private final Runnable scanTimeout = () -> stopScan("انتهى البحث. الأجهزة المقترنة تظل ظاهرة.");

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
        sinilink = new SinilinkBleController(this, message -> { if(status!=null) status.setText(message); });

        root.addView(label("AboEzzat Multi Audio", 26, true));
        TextView sub = label("Bluetooth • Equalizer • Audio Sources", 13, false);
        sub.setTextColor(muted); root.addView(sub);

        status = label("Bluetooth: جاهز للفحص", 14, true);
        status.setTextColor(accent); panel(root, status);

        LinearLayout actions = row();
        scanButton = button("بحث عن أجهزة");
        scanButton.setOnClickListener(v -> { if (scanning) stopScan("تم إيقاف البحث."); else startScan(); });
        Button settings = button("إعدادات Bluetooth");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        actions.addView(scanButton, weight()); actions.addView(settings, weight()); panel(root, actions);

        header(root, "الأجهزة والسماعات");
        devicesBox = new LinearLayout(this); devicesBox.setOrientation(LinearLayout.VERTICAL);
        panel(root, devicesBox); renderDevices();

        header(root, "Audio Switch");
        LinearLayout sourceRow = row();
        for (String source : new String[]{"TF", "USB", "BT", "Audio Card", "AUX"}) {
            Button b = button(source);
            b.setOnClickListener(v -> showSourceMenu(source));
            sourceRow.addView(b, weight());
        }
        panel(root, sourceRow);

        header(root, "EQ");
        LinearLayout eqRow = row();
        Button eqButton = button("🎵 اختيار وضع الصوت");
        eqButton.setOnClickListener(v -> showEqMenu());
        eqRow.addView(eqButton, new LinearLayout.LayoutParams(-1, dp(48)));
        panel(root, eqRow);

        header(root, "Set");
        LinearLayout settingsRow = row();
        Button settingsButton = button("إعدادات السماعة");
        settingsButton.setOnClickListener(v -> showSpeakerSettings());
        settingsRow.addView(settingsButton, new LinearLayout.LayoutParams(-1, dp(48)));
        panel(root, settingsRow);

        header(root, "التحكم في التشغيل");
        LinearLayout playback = row();
        for (String action : new String[]{"كتم", "السابق", "تشغيل / إيقاف", "التالي"}) {
            Button b = button(action);
            b.setOnClickListener(v -> { if(sinilink==null) return; if("كتم".equals(action)) sinilink.volume(0); else if("السابق".equals(action)) sinilink.previous(); else if("تشغيل / إيقاف".equals(action)) sinilink.playPause(); else if("التالي".equals(action)) sinilink.next(); });
            playback.addView(b, weight());
        }
        panel(root, playback);

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
        if (adapter == null) { status.setText("Bluetooth غير متاح على هذا الهاتف"); return; }
        if (!permissionsGranted()) { status.setText("مطلوب إذن الأجهزة المجاورة؛ اضغط بحث ووافق على الإذن"); requestBtPermissions(); return; }
        try {
            if (!adapter.isEnabled()) {
                status.setText("Bluetooth متوقف. فعّله ثم اضغط بحث.");
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
                return;
            }
            stopScan(null);
            loadPairedDevices();
            scanning = true;
            scanButton.setText("إيقاف البحث");
            status.setText("جارٍ البحث لمدة 12 ثانية…");
            scanHandler.removeCallbacks(scanTimeout);
            scanHandler.postDelayed(scanTimeout, 12000);

            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            if (!receiverRegistered) {
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(classicReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                else registerReceiver(classicReceiver, filter);
                receiverRegistered = true;
            }

            scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) {
                scanCallback = new ScanCallback() {
                    @Override public void onScanResult(int type, ScanResult result) {
                        BluetoothDevice d = result.getDevice();
                        if (d != null) {
                            devices.put(d.getAddress(), d);
                            runOnUiThread(() -> renderDevices());
                        }
                    }
                    @Override public void onScanFailed(int error) {
                        runOnUiThread(() -> stopScan("فشل البحث عن Bluetooth (رمز " + error + "). جرّب إيقاف/تشغيل Bluetooth."));
                    }
                };
                scanner.startScan(scanCallback);
            }

            boolean classicStarted = adapter.startDiscovery();
            if (!classicStarted && scanner == null) {
                stopScan("الهاتف رفض بدء البحث. أوقف Bluetooth وشغّله ثم جرّب.");
                return;
            }
        } catch (SecurityException e) {
            stopScan("إذن الأجهزة المجاورة غير كافٍ. راجع أذونات التطبيق.");
        } catch (Exception e) {
            stopScan("تعذر بدء البحث: " + e.getClass().getSimpleName());
        }
    }

    private void stopScan(String message) {
        scanHandler.removeCallbacks(scanTimeout);
        try { if (scanner != null && scanCallback != null) scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        try { if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (Exception ignored) {}
        scanning = false;
        if (scanButton != null) scanButton.setText("بحث عن أجهزة");
        if (message != null && status != null) status.setText(message);
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
            manage.setOnClickListener(v -> { selectedSpeaker=d; if(sinilink!=null) sinilink.connect(d); });
            item.addView(manage); devicesBox.addView(item);
            View line = new View(this); line.setBackgroundColor(Color.rgb(55,62,75));
            devicesBox.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        }
    }

    private void showSourceMenu(String selected) {
        String[] options = {"TF", "USB", "BT", "Audio Card", "AUX"};
        new AlertDialog.Builder(this).setTitle("Audio Switch")
            .setSingleChoiceItems(options, java.util.Arrays.asList(options).indexOf(selected),
                (dialog, which) -> {
                    if(sinilink!=null) sinilink.source(options[which]);
                    dialog.dismiss();
                }).setNegativeButton("إلغاء", null).show();
    }

    private void showEqMenu() {
        String[] modes = {"NORMAL", "ROCK", "POP", "CLASSIC", "JAZZ", "COUNTRY"};
        new AlertDialog.Builder(this).setTitle("EQ • وضع الصوت")
            .setItems(modes, (dialog, which) ->
                if(sinilink!=null) sinilink.eq(modes[which]))
            .setNegativeButton("إلغاء", null).show();
    }

    private void showSpeakerSettings() {
        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView prompt = label("نغمة التنبيه", 14, true);
        layout.addView(prompt);
        Button tone = button("تبديل نغمة التنبيه");
        tone.setOnClickListener(v -> { if(sinilink!=null) sinilink.promptTone(); });
        layout.addView(tone);
        TextView name = label("اسم السماعة وكلمة المرور", 14, true);
        name.setPadding(0, dp(12), 0, dp(4));
        layout.addView(name);
        TextView note = label("هذه الإعدادات ستُفعّل بعد توصيل DH100 عبر بروتوكول Sinilink.", 12, false);
        note.setTextColor(muted);
        layout.addView(note);
        new AlertDialog.Builder(this).setTitle("إعدادات السماعة").setView(layout)
            .setPositiveButton("تم", null).show();
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
