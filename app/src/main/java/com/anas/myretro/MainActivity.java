package com.anas.myretro;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.net.ssl.SSLException;

public class MainActivity extends Activity {

    private static final String RA_PKG = "com.retroarch";
    private static final String RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    private static final int REQ_PICK = 11;
    private static final String VERSION = "v4.1";

    /** Tache reseau WebDAV executee hors du thread UI. */
    private interface Job {
        String run(WebDav dav, WebDav.Progress progress) throws Exception;
    }

    /** Tache d'arriere-plan sans WebDAV. */
    private interface PlainJob {
        String run(WebDav.Progress progress) throws Exception;
    }

    private static class AppItem {
        String label;
        String pkg;
        Drawable icon;
    }

    private SharedPreferences prefs;
    private final Map<String, String> defaults = new HashMap<>();
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final List<File> allGames = new ArrayList<>();
    private final List<AppItem> allApps = new ArrayList<>();

    private FrameLayout content;
    private TextView status;
    private LinearLayout gamesBox;
    private LinearLayout catalogBox;
    private LinearLayout appsBox;
    private EditText search;
    private EditText appSearch;

    private int currentTab = 0;
    private boolean syncOnResume = false;
    private String lastResult = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ================================================================== cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("myretro", MODE_PRIVATE);
        String sd = Environment.getExternalStorageDirectory().getAbsolutePath();

        defaults.put("url", "");
        defaults.put("user", "");
        defaults.put("pass", "");
        defaults.put("remote", "MyRetro");
        defaults.put("roms", sd + "/ROMs");
        defaults.put("saves", sd + "/RetroArch/saves");
        defaults.put("states", sd + "/RetroArch/states");
        defaults.put("cfg", sd + "/Android/data/" + RA_PKG + "/files/retroarch.cfg");
        defaults.put("dlurl", "");
        defaults.put("catalog", "");

        if (prefs.getBoolean("logged", false)) {
            showHome();
        } else {
            showLogin();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        collect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!prefs.getBoolean("logged", false)) {
            return;
        }
        if (syncOnResume) {
            // On revient de RetroArch : on envoie les nouveaux saves
            syncOnResume = false;
            syncSaves(null);
        }
        if (currentTab == 0 && gamesBox != null) {
            loadGames();
        }
    }

    // ================================================================== utilitaires UI

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout verticalPage(ScrollView holder) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        holder.addView(root);
        return root;
    }

    private void section(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(4));
        parent.addView(tv);
    }

    private void note(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setPadding(0, dp(2), 0, dp(6));
        parent.addView(tv);
    }

    private Button addButton(LinearLayout parent, String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private void addField(LinearLayout parent, String label, String key, boolean password) {
        TextView tv = new TextView(this);
        tv.setText(label);
        parent.addView(tv);
        EditText et = new EditText(this);
        et.setSingleLine(true);
        String d = defaults.get(key);
        et.setText(prefs.getString(key, d == null ? "" : d));
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        parent.addView(et);
        fields.put(key, et);
    }

    private TextView newStatus(LinearLayout parent) {
        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        parent.addView(status);
        return status;
    }

    private void setStatus(String s) {
        if (status != null) {
            status.setText(s);
        }
    }

    /** Sauvegarde les champs actuellement affiches. */
    private void collect() {
        if (fields.isEmpty()) {
            return;
        }
        SharedPreferences.Editor e = prefs.edit();
        for (Map.Entry<String, EditText> f : fields.entrySet()) {
            e.putString(f.getKey(), f.getValue().getText().toString().trim());
        }
        e.apply();
    }

    /** Valeur d'un reglage : champ affiche, sinon valeur enregistree. */
    private String val(String key) {
        EditText e = fields.get(key);
        if (e != null) {
            return e.getText().toString().trim();
        }
        String d = defaults.get(key);
        return prefs.getString(key, d == null ? "" : d).trim();
    }

    private String remoteRoot() {
        String r = val("remote");
        return r.isEmpty() ? "MyRetro" : r;
    }

    private void resetRefs() {
        fields.clear();
        status = null;
        gamesBox = null;
        catalogBox = null;
        appsBox = null;
        search = null;
        appSearch = null;
    }

    // ================================================================== ecran de connexion

    private void showLogin() {
        resetRefs();
        content = null;
        ScrollView sv = new ScrollView(this);
        LinearLayout root = verticalPage(sv);

        TextView title = new TextView(this);
        title.setText("MyRetro");
        title.setTextSize(30);
        root.addView(title);
        note(root, "سجل الدخول للسحاب باش تتحفظ الألعاب والحفظ ديالك");

        addField(root, "رابط WebDAV", "url", false);
        note(root, "مثال Koofr: https://app.koofr.net/dav/Koofr");
        addField(root, "اسم المستخدم (الإيميل)", "user", false);
        addField(root, "كلمة السر (App password)", "pass", true);

        addButton(root, "دخول", v -> login());
        addButton(root, "استعمال بلا سحاب (محلي فقط)", v -> {
            collect();
            prefs.edit().putBoolean("logged", true).apply();
            showHome();
        });
        newStatus(root);
        setContentView(sv);
    }

    private void login() {
        collect();
        lastResult = "";
        final String root = remoteRoot();
        runJob("جاري التحقق من الحساب...", (dav, p) -> dav.test(root), () -> {
            if (lastResult.startsWith("✔")) {
                prefs.edit().putBoolean("logged", true).apply();
                showHome();
            }
        });
    }

    // ================================================================== ecran principal

    private void showHome() {
        resetRefs();
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(16), dp(10), dp(16), dp(10));
        TextView title = new TextView(this);
        title.setText("MyRetro  " + VERSION);
        title.setTextSize(20);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView who = new TextView(this);
        String user = val("user");
        who.setText(user.isEmpty() ? "محلي" : user);
        who.setTextSize(12);
        top.addView(who);
        main.addView(top);

        content = new FrameLayout(this);
        main.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        addTab(tabs, "الألعاب", 0);
        addTab(tabs, "المكتبة", 1);
        addTab(tabs, "البرامج", 2);
        addTab(tabs, "الإعدادات", 3);
        main.addView(tabs);

        setContentView(main);
        ensureStorageAccess();
        showTab(currentTab);
    }

    private void addTab(LinearLayout tabs, String label, final int index) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> showTab(index));
        tabs.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private void showTab(int tab) {
        collect();
        currentTab = tab;
        resetRefs();
        content.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout root = verticalPage(sv);
        content.addView(sv);

        if (tab == 0) {
            buildGamesTab(root);
            loadGames();
        } else if (tab == 1) {
            buildLibraryTab(root);
        } else if (tab == 2) {
            buildAppsTab(root);
        } else {
            buildSettingsTab(root);
        }
    }

    // ------------------------------------------------------------------ onglet Bibliotheque

    private void buildLibraryTab(LinearLayout root) {
        section(root, "المكتبة: ألعاب مجانية وقانونية");
        note(root, "ألعاب homebrew مجانية من مطورين مستقلين، وكتخدم بمحاكيات خفيفة على التيليفونات القديمة. "
                + "إلا كان الرابط ديال ملف لعبة كيتحمل مباشرة، وإلا كيتحل فـ المتصفح. "
                + "ملي تنزل اللعبة من الموقع، استعمل 'إضافة لعبة من التيليفون' فـ تبويب الألعاب.");
        newStatus(root);
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box);

        final List<String[]> all = new ArrayList<>();
        try {
            all.addAll(parseCatalog(readAsset("catalog.txt")));
        } catch (Exception e) {
            setStatus("❌ ما قدرتش نقرا المكتبة: " + e.getMessage());
        }
        renderLibrary(all, box);

        // Catalogue supplementaire (meme format) : le lien est dans l'onglet Jeux
        final String extra = val("catalog");
        if (!extra.isEmpty()) {
            new Thread(() -> {
                try {
                    final List<String[]> more = parseCatalog(Downloader.fetchText(extra));
                    ui.post(() -> {
                        all.addAll(more);
                        renderLibrary(all, box);
                    });
                } catch (Exception e) {
                    final String m = "❌ الكتالوج الإضافي: " + friendly(e);
                    ui.post(() -> setStatus(m));
                }
            }).start();
        }
    }

    private String readAsset(String name) throws IOException {
        InputStream in = getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    /** Format : categorie|nom|lien|appareil  (ou simplement nom|lien). */
    private static List<String[]> parseCatalog(String txt) {
        List<String[]> out = new ArrayList<>();
        for (String raw : txt.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("﻿#")) {
                continue;
            }
            String[] p = line.split("\\|", -1);
            for (int i = 0; i < p.length; i++) {
                p[i] = p[i].trim();
            }
            if (p.length >= 4) {
                out.add(new String[]{p[0], p[1], p[2], p[3]});
            } else if (p.length >= 2) {
                out.add(new String[]{"أخرى", p[0], p[1], ""});
            }
        }
        return out;
    }

    private static boolean isRomUrl(String url) {
        String u = url.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        return u.endsWith(".nes") || u.endsWith(".gb") || u.endsWith(".gbc") || u.endsWith(".gba")
                || u.endsWith(".sfc") || u.endsWith(".smc") || u.endsWith(".md") || u.endsWith(".gen")
                || u.endsWith(".smd") || u.endsWith(".zip");
    }

    private void renderLibrary(List<String[]> items, LinearLayout box) {
        box.removeAllViews();
        Map<String, List<String[]>> groups = new TreeMap<>();
        for (String[] it : items) {
            List<String[]> l = groups.get(it[0]);
            if (l == null) {
                l = new ArrayList<>();
                groups.put(it[0], l);
            }
            l.add(it);
        }
        for (Map.Entry<String, List<String[]>> e : groups.entrySet()) {
            TextView h = new TextView(this);
            h.setText(e.getKey());
            h.setTextSize(18);
            h.setPadding(0, dp(12), 0, dp(4));
            box.addView(h);
            for (final String[] it : e.getValue()) {
                Button b = new Button(this);
                b.setAllCaps(false);
                b.setText(it[3].isEmpty() ? it[1] : it[1] + "   •   " + it[3]);
                b.setOnClickListener(v -> openEntry(it[2]));
                box.addView(b);
            }
        }
    }

    private void openEntry(String url) {
        if (isRomUrl(url)) {
            collect();
            downloadFromUrl(url);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "ما لقيتش متصفح باش نحل الرابط", Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ onglet Jeux

    private void buildGamesTab(LinearLayout root) {
        section(root, "الألعاب");
        addButton(root, "تحميل الألعاب من السحاب (مجلد roms)", v -> pullGames());
        addButton(root, "إضافة لعبة من التيليفون", v -> pickGame());
        addButton(root, "مزامنة الحفظ الآن", v -> syncSaves(null));
        newStatus(root);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("🔍 بحث عن لعبة");
        search.addTextChangedListener(new SimpleWatcher(this::renderGames));
        root.addView(search);

        gamesBox = new LinearLayout(this);
        gamesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesBox);

        section(root, "تحميل ألعاب من الإنترنت");
        addField(root, "رابط مباشر لملف اللعبة", "dlurl", false);
        addButton(root, "تحميل من الرابط", v -> {
            collect();
            String u = val("dlurl");
            if (u.isEmpty()) {
                setStatus("حط رابط اللعبة أولا");
            } else {
                downloadFromUrl(u);
            }
        });
        addField(root, "رابط الكتالوج (ملف نصي، كل سطر: الاسم|الرابط)", "catalog", false);
        addButton(root, "فتح الكتالوج", v -> openCatalog());
        catalogBox = new LinearLayout(this);
        catalogBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalogBox);
    }

    // ------------------------------------------------------------------ onglet Programmes

    private void buildAppsTab(LinearLayout root) {
        section(root, "البرامج المثبتة فـ التيليفون");
        note(root, "اضغط على برنامج باش تحلو. RetroArch كيبان فـ الأول.");
        appSearch = new EditText(this);
        appSearch.setSingleLine(true);
        appSearch.setHint("🔍 بحث عن برنامج");
        appSearch.addTextChangedListener(new SimpleWatcher(this::renderApps));
        root.addView(appSearch);

        appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(appsBox);

        loadApps();
        renderApps();
    }

    private void loadApps() {
        allApps.clear();
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        Set<String> seen = new HashSet<>();
        for (ResolveInfo r : list) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            AppItem a = new AppItem();
            a.pkg = pkg;
            a.label = String.valueOf(r.loadLabel(pm));
            a.icon = r.loadIcon(pm);
            allApps.add(a);
        }
        Collections.sort(allApps, (x, y) -> {
            boolean xr = x.pkg.equals(RA_PKG);
            boolean yr = y.pkg.equals(RA_PKG);
            if (xr != yr) {
                return xr ? -1 : 1;
            }
            return x.label.compareToIgnoreCase(y.label);
        });
    }

    private void renderApps() {
        if (appsBox == null) {
            return;
        }
        appsBox.removeAllViews();
        String q = appSearch == null ? "" : appSearch.getText().toString().trim().toLowerCase(Locale.US);
        int shown = 0;
        for (final AppItem a : allApps) {
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

            TextView name = new TextView(this);
            name.setText(a.label);
            name.setTextSize(16);
            name.setPadding(dp(12), 0, 0, 0);
            row.addView(name);

            row.setOnClickListener(v -> {
                try {
                    Intent li = getPackageManager().getLaunchIntentForPackage(a.pkg);
                    if (li != null) {
                        startActivity(li);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "ما قدرتش نحل هاد البرنامج", Toast.LENGTH_SHORT).show();
                }
            });
            appsBox.addView(row);
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("ما لقيت حتى برنامج");
            appsBox.addView(empty);
        }
    }

    // ------------------------------------------------------------------ onglet Reglages

    private void buildSettingsTab(LinearLayout root) {
        section(root, "الحساب (السحاب)");
        addField(root, "رابط WebDAV", "url", false);
        addField(root, "اسم المستخدم", "user", false);
        addField(root, "كلمة السر (App password)", "pass", true);
        addField(root, "المجلد على السحاب", "remote", false);
        addButton(root, "اختبار الاتصال", v -> testConnection());

        section(root, "المجلدات فـ التيليفون");
        addField(root, "مجلد الألعاب (ROMs)", "roms", false);
        addField(root, "مجلد saves ديال RetroArch", "saves", false);
        addField(root, "مجلد states ديال RetroArch", "states", false);
        addField(root, "ملف retroarch.cfg", "cfg", false);
        addButton(root, "حفظ الإعدادات", v -> {
            collect();
            setStatus("تم حفظ الإعدادات");
        });
        addButton(root, "منح صلاحية الوصول للملفات", v -> ensureStorageAccess());
        newStatus(root);

        section(root, "الحساب");
        addButton(root, "تسجيل الخروج", v -> {
            collect();
            prefs.edit().putBoolean("logged", false).remove("pass").apply();
            showLogin();
        });
    }

    // ================================================================== permissions

    /**
     * Android 5.1 : rien a faire (permissions donnees a l'installation).
     * Android 6-10 : demande la permission de stockage.
     * Android 11+ : ouvre l'ecran "acces a tous les fichiers".
     */
    private void ensureStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (!Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "فعّل 'السماح بالوصول لكل الملفات' لـ MyRetro", Toast.LENGTH_LONG).show();
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
                }
            }
        } catch (Exception e) {
            setStatus("ما قدرتش نطلب صلاحية الملفات: " + e.getMessage());
        }
    }

    // ================================================================== taches reseau

    private static String friendly(Exception e) {
        if (e instanceof UnknownHostException) {
            return "ما قدرتش نوصل للسيرفر. تأكد من الإنترنت ومن الرابط";
        }
        if (e instanceof SSLException) {
            return "مشكل شهادة SSL: أندرويد 5.1 ما كيقبلش شهادة هاد السيرفر. جرب سيرفر آخر";
        }
        if (e instanceof SocketTimeoutException) {
            return "السيرفر ما جاوبش فـ الوقت. جرب مرة أخرى";
        }
        if (e instanceof ConnectException) {
            return "ما قدرتش نتصل بالسيرفر. تأكد من الرابط والمنفذ";
        }
        String m = e.getMessage();
        return m == null ? e.toString() : m;
    }

    /** Execute une tache WebDAV en arriere-plan, affiche le resultat, puis lance "after" (peut etre null). */
    private void runJob(String busy, final Job job, final Runnable after) {
        collect();
        final String url = val("url");
        final String user = val("user");
        final String pass = val("pass");
        if (url.isEmpty()) {
            setStatus("المزامنة السحابية مطفية: ما كاين حتى رابط WebDAV (الإعدادات)");
            if (after != null) {
                after.run();
            }
            return;
        }
        setStatus(busy);
        new Thread(() -> {
            String msg;
            try {
                WebDav dav = new WebDav(url, user, pass);
                msg = job.run(dav, m -> ui.post(() -> setStatus(m)));
            } catch (Exception e) {
                msg = "❌ " + friendly(e);
            }
            final String result = msg;
            ui.post(() -> {
                lastResult = result;
                setStatus(result);
                if (after != null) {
                    after.run();
                }
            });
        }).start();
    }

    /** Tache d'arriere-plan qui n'utilise pas WebDAV. */
    private void runPlain(String busy, final PlainJob job, final Runnable after) {
        setStatus(busy);
        new Thread(() -> {
            String msg;
            try {
                msg = job.run(m -> ui.post(() -> setStatus(m)));
            } catch (Exception e) {
                msg = "❌ " + friendly(e);
            }
            final String result = msg;
            ui.post(() -> {
                lastResult = result;
                setStatus(result);
                if (after != null) {
                    after.run();
                }
            });
        }).start();
    }

    private void testConnection() {
        collect();
        final String root = remoteRoot();
        runJob("جاري اختبار الاتصال...", (dav, p) -> dav.test(root), null);
    }

    private void syncSaves(Runnable after) {
        collect();
        final String root = remoteRoot();
        final File saves = new File(val("saves"));
        final File states = new File(val("states"));
        runJob("جاري مزامنة الحفظ...", (dav, p) -> {
            String a = dav.sync(saves, root, "saves");
            String b = dav.sync(states, root, "states");
            return "تمت المزامنة ✔  saves " + a + " | states " + b;
        }, after);
    }

    private void pullGames() {
        collect();
        final String root = remoteRoot();
        final File roms = new File(val("roms"));
        runJob("جاري تحميل الألعاب...", (dav, p) -> {
            int n = dav.pullAll(roms, root, "roms", p);
            return "✔ تم تحميل " + n + " لعبة جديدة من السحاب (المجلد: " + root + "/roms)";
        }, () -> scanGames(true));
    }

    private void downloadFromUrl(final String url) {
        collect();
        final File dir = new File(val("roms"));
        runPlain("جاري التحميل...", p -> {
            String name = Downloader.download(url, dir, p);
            return "✔ تم تحميل: " + name;
        }, () -> scanGames(true));
    }

    private void openCatalog() {
        collect();
        final String cat = val("catalog");
        if (cat.isEmpty()) {
            setStatus("حط رابط الكتالوج أولا");
            return;
        }
        setStatus("جاري جلب الكتالوج...");
        new Thread(() -> {
            String error = null;
            final List<String[]> items = new ArrayList<>();
            try {
                for (String[] e : parseCatalog(Downloader.fetchText(cat))) {
                    items.add(new String[]{e[1], e[2]});
                }
            } catch (Exception e) {
                error = "❌ " + friendly(e);
            }
            final String err = error;
            ui.post(() -> showCatalog(items, err));
        }).start();
    }

    private void showCatalog(List<String[]> items, String error) {
        if (catalogBox == null) {
            return;
        }
        catalogBox.removeAllViews();
        if (error != null) {
            setStatus(error);
            return;
        }
        if (items.isEmpty()) {
            setStatus("الكتالوج خاوي أو الصيغة غلط (كل سطر: الاسم|الرابط)");
            return;
        }
        setStatus("الكتالوج فيه " + items.size() + " لعبة. اضغط على لعبة باش تتحمل");
        for (final String[] item : items) {
            Button b = new Button(this);
            b.setText("⬇  " + item[0]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> downloadFromUrl(item[1]));
            catalogBox.addView(b);
        }
    }

    // ================================================================== ajout manuel d'un jeu

    private void pickGame() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "اختر لعبة"), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "ما لقيتش تطبيق باش نختار الملف", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        collect();
        final File dir = new File(val("roms"));
        setStatus("جاري نسخ اللعبة...");
        new Thread(() -> {
            String msg;
            try {
                String name = displayName(uri);
                if (name == null || name.trim().isEmpty()) {
                    name = "game_" + System.currentTimeMillis();
                }
                name = name.replace('/', '_').replace('\\', '_');
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) {
                    throw new IOException("ما قدرتش نقرا الملف");
                }
                OutputStream out = new FileOutputStream(new File(dir, name));
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                } finally {
                    out.close();
                    in.close();
                }
                msg = "✔ تمت إضافة: " + name;
            } catch (Exception e) {
                msg = "❌ " + friendly(e);
            }
            final String result = msg;
            ui.post(() -> {
                lastResult = result;
                scanGames(true);
            });
        }).start();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
            // on essaie le chemin de l'URI
        } finally {
            if (c != null) {
                c.close();
            }
        }
        String p = uri.getLastPathSegment();
        return p == null ? null : p.substring(p.lastIndexOf('/') + 1);
    }

    // ================================================================== liste des jeux

    private void scanGames(boolean keepLastResult) {
        if (gamesBox == null) {
            return;
        }
        collect();
        int n = loadGames();
        String prefix = keepLastResult && !lastResult.isEmpty() ? lastResult + "\n" : "";
        if (n == 0) {
            setStatus(prefix + "ما لقيت حتى لعبة فـ: " + val("roms"));
        } else {
            setStatus(prefix + "لقيت " + n + " لعبة. اضغط باش تلعب • ضغطة طويلة للمسح • ★ للمفضلة");
        }
    }

    /** Relit le dossier des jeux et reconstruit la liste. Retourne le nombre de jeux. */
    private int loadGames() {
        List<File> found = new ArrayList<>();
        walk(new File(val("roms")), 0, found);
        Collections.sort(found);
        allGames.clear();
        allGames.addAll(found);
        renderGames();
        return found.size();
    }

    private static String consoleOf(String core) {
        if (core == null) return "أخرى";
        switch (core) {
            case "fceumm": return "NES";
            case "gambatte": return "Game Boy";
            case "mgba": return "Game Boy Advance";
            case "snes9x2010": return "Super Nintendo";
            case "genesis_plus_gx": return "Mega Drive";
            case "pcsx_rearmed": return "PlayStation";
            default: return core;
        }
    }

    private static String gameTitle(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private void header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setPadding(0, dp(12), 0, dp(4));
        gamesBox.addView(tv);
    }

    /** Affiche les jeux : favoris d'abord, puis groupes par console, avec filtre de recherche. */
    private void renderGames() {
        if (gamesBox == null) {
            return;
        }
        gamesBox.removeAllViews();
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US);
        Set<String> favs = prefs.getStringSet("favs", new HashSet<String>());

        List<File> favList = new ArrayList<>();
        Map<String, List<File>> groups = new TreeMap<>();
        for (File f : allGames) {
            if (!q.isEmpty() && !f.getName().toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            if (favs.contains(f.getAbsolutePath())) {
                favList.add(f);
            } else {
                String label = consoleOf(coreFor(f.getName()));
                List<File> l = groups.get(label);
                if (l == null) {
                    l = new ArrayList<>();
                    groups.put(label, l);
                }
                l.add(f);
            }
        }
        if (!favList.isEmpty()) {
            header("★ المفضلة");
            for (File f : favList) {
                addGameRow(f, true);
            }
        }
        for (Map.Entry<String, List<File>> e : groups.entrySet()) {
            header(e.getKey());
            for (File f : e.getValue()) {
                addGameRow(f, false);
            }
        }
    }

    private void addGameRow(final File rom, boolean fav) {
        final String core = coreFor(rom.getName());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button play = new Button(this);
        play.setAllCaps(false);
        play.setText(gameTitle(rom));
        play.setOnClickListener(v -> syncSaves(() -> launch(rom, core)));
        play.setOnLongClickListener(v -> {
            confirmDelete(rom);
            return true;
        });
        row.addView(play, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button star = new Button(this);
        star.setText(fav ? "★" : "☆");
        star.setOnClickListener(v -> toggleFavorite(rom));
        row.addView(star, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        gamesBox.addView(row);
    }

    private void toggleFavorite(File rom) {
        Set<String> s = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));
        String key = rom.getAbsolutePath();
        if (!s.remove(key)) {
            s.add(key);
        }
        prefs.edit().putStringSet("favs", s).apply();
        renderGames();
    }

    private void confirmDelete(final File rom) {
        new AlertDialog.Builder(this)
                .setTitle("مسح اللعبة؟")
                .setMessage(rom.getName())
                .setPositiveButton("مسح", (d, w) -> {
                    if (rom.delete()) {
                        Set<String> s = new HashSet<>(prefs.getStringSet("favs", new HashSet<String>()));
                        s.remove(rom.getAbsolutePath());
                        prefs.edit().putStringSet("favs", s).apply();
                        allGames.remove(rom);
                        renderGames();
                        setStatus("تم مسح: " + rom.getName());
                    } else {
                        setStatus("ما قدرتش نمسح الملف");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
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

    // ================================================================== petit utilitaire

    /** TextWatcher qui n'agit qu'apres un changement de texte. */
    private static class SimpleWatcher implements TextWatcher {
        private final Runnable action;

        SimpleWatcher(Runnable action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            action.run();
        }
    }
}
