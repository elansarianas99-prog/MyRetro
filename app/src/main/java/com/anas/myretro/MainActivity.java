package com.anas.myretro;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String RA_PKG = "com.retroarch";
    private static final String RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";

    private SharedPreferences prefs;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private TextView status;
    private LinearLayout gamesBox;
    private boolean syncOnResume = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("myretro", MODE_PRIVATE);
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MyRetro");
        title.setTextSize(24);
        root.addView(title);

        addField(root, "رابط WebDAV (مثلا https://.../remote.php/dav/files/اسمك)", "url", "", false);
        addField(root, "اسم المستخدم", "user", "", false);
        addField(root, "كلمة السر", "pass", "", true);
        addField(root, "المجلد على السحاب", "remote", "MyRetro", false);
        addField(root, "مجلد الألعاب (ROMs)", "roms", sd + "/ROMs", false);
        addField(root, "مجلد saves ديال RetroArch", "saves", sd + "/RetroArch/saves", false);
        addField(root, "مجلد states ديال RetroArch", "states", sd + "/RetroArch/states", false);
        addField(root, "ملف retroarch.cfg", "cfg",
                sd + "/Android/data/" + RA_PKG + "/files/retroarch.cfg", false);

        Button saveBtn = new Button(this);
        saveBtn.setText("حفظ الإعدادات");
        saveBtn.setOnClickListener(v -> {
            collect();
            setStatus("تم حفظ الإعدادات");
        });
        root.addView(saveBtn);

        Button syncBtn = new Button(this);
        syncBtn.setText("مزامنة السحاب الآن");
        syncBtn.setOnClickListener(v -> runSync(null));
        root.addView(syncBtn);

        Button scanBtn = new Button(this);
        scanBtn.setText("عرض الألعاب");
        scanBtn.setOnClickListener(v -> scan());
        root.addView(scanBtn);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        gamesBox = new LinearLayout(this);
        gamesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesBox);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (syncOnResume) {
            syncOnResume = false;
            runSync(null);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void addField(LinearLayout parent, String label, String key, String def, boolean password) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(prefs.getString(key, def));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(et);
        fields.put(key, et);
    }

    private void collect() {
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, EditText> f : fields.entrySet()) {
            e.putString(f.getKey(), f.getValue().getText().toString().trim());
        }
        e.apply();
    }

    private String val(String key) {
        EditText e = fields.get(key);
        return e == null ? "" : e.getText().toString().trim();
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    /** Synchronise saves et states avec WebDAV, puis execute "after" (peut etre null) sur le thread UI. */
    private void runSync(final Runnable after) {
        collect();
        final String url = val("url");
        final String user = val("user");
        final String pass = val("pass");
        final String remote = val("remote").isEmpty() ? "MyRetro" : val("remote");
        final String saves = val("saves");
        final String states = val("states");

        if (url.isEmpty()) {
            setStatus("حط رابط WebDAV باش تخدم المزامنة");
            if (after != null) {
                after.run();
            }
            return;
        }
        setStatus("جاري المزامنة...");
        new Thread(() -> {
            String msg;
            try {
                WebDav dav = new WebDav(url, user, pass);
                String a = dav.sync(new File(saves), remote, "saves");
                String b = dav.sync(new File(states), remote, "states");
                msg = "تمت المزامنة ✔  saves " + a + " | states " + b;
            } catch (Exception e) {
                msg = "فشلت المزامنة: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> {
                setStatus(m);
                if (after != null) {
                    after.run();
                }
            });
        }).start();
    }

    private void scan() {
        collect();
        gamesBox.removeAllViews();
        List<File> found = new ArrayList<>();
        walk(new File(val("roms")), 0, found);
        Collections.sort(found);
        if (found.isEmpty()) {
            setStatus("ما لقيت حتى لعبة فـ: " + val("roms"));
            return;
        }
        setStatus("لقيت " + found.size() + " لعبة");
        for (final File rom : found) {
            final String core = coreFor(rom.getName());
            Button b = new Button(this);
            b.setText(rom.getName());
            b.setAllCaps(false);
            b.setOnClickListener(v -> runSync(() -> launch(rom, core)));
            gamesBox.addView(b);
        }
    }

    private void walk(File dir, int depth, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null || depth > 3) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                walk(f, depth + 1, out);
            } else if (coreFor(f.getName()) != null) {
                out.add(f);
            }
        }
    }

    /** Nom du core libretro selon l'extension (cores legers, adaptes a un vieux telephone). */
    private static String coreFor(String fileName) {
        String n = fileName.toLowerCase(Locale.US);
        if (n.endsWith(".nes") || n.endsWith(".fds")) return "fceumm";
        if (n.endsWith(".gb") || n.endsWith(".gbc")) return "gambatte";
        if (n.endsWith(".gba")) return "mgba";
        if (n.endsWith(".sfc") || n.endsWith(".smc")) return "snes9x2010";
        if (n.endsWith(".md") || n.endsWith(".gen") || n.endsWith(".smd")) return "genesis_plus_gx";
        if (n.endsWith(".cue") || n.endsWith(".chd") || n.endsWith(".pbp")) return "pcsx_rearmed";
        return null;
    }

    private void launch(File rom, String core) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName(RA_PKG, RA_ACTIVITY));
        i.putExtra("ROM", rom.getAbsolutePath());
        i.putExtra("LIBRETRO", "/data/data/" + RA_PKG + "/cores/" + core + "_libretro_android.so");
        File cfg = new File(val("cfg"));
        if (cfg.exists()) {
            i.putExtra("CONFIGFILE", cfg.getAbsolutePath());
        }
        i.putExtra("QUITFOCUS", "");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(i);
            syncOnResume = true;
        } catch (Exception e) {
            Toast.makeText(this,
                    "تأكد أن RetroArch مثبت وأن المحاكي (" + core + ") منزل",
                    Toast.LENGTH_LONG).show();
        }
    }
}
