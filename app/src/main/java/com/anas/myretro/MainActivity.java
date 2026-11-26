package com.anas.myretro;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import javax.net.ssl.SSLException;

public class MainActivity extends Activity {

    private static final String RA_PKG = "com.retroarch";
    private static final String RA_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture";
    private static final int REQ_PICK = 11;

    /** Tache reseau executee hors du thread UI. */
    private interface Job {
        String run(WebDav dav, WebDav.Progress progress) throws Exception;
    }

    private SharedPreferences prefs;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private TextView status;
    private LinearLayout gamesBox;
    private LinearLayout catalogBox;
    private EditText search;
    private final List<File> allGames = new ArrayList<>();
    private boolean syncOnResume = false;
    private String lastResult = "";
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ interface

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
        title.setText("MyRetro  v3.3");
        title.setTextSize(24);
        root.addView(title);

        section(root, "السحاب (WebDAV)");
        addField(root, "رابط WebDAV (مثلا https://.../remote.php/dav/files/اسمك)", "url", "", false);
        addField(root, "اسم المستخدم", "user", "", false);
        addField(root, "كلمة السر", "pass", "", true);
        addField(root, "المجلد على السحاب", "remote", "MyRetro", false);
        addButton(root, "اختبار الاتصال", v -> testConnection());

        section(root, "المجلدات فـ التيليفون");
        addField(root, "مجلد الألعاب (ROMs)", "roms", sd + "/ROMs", false);
        addField(root, "مجلد saves ديال RetroArch", "saves", sd + "/RetroArch/saves", false);
        addField(root, "مجلد states ديال RetroArch", "states", sd + "/RetroArch/states", false);
        addField(root, "ملف retroarch.cfg", "cfg",
                sd + "/Android/data/" + RA_PKG + "/files/retroarch.cfg", false);
        addButton(root, "حفظ الإعدادات", v -> {
            collect();
            setStatus("تم حفظ الإعدادات");
        });
        addButton(root, "منح صلاحية الوصول للملفات", v -> ensureStorageAccess());

        section(root, "الألعاب");
        addButton(root, "تحميل الألعاب من السحاب (مجلد roms)", v -> pullGames());
        addButton(root, "إضافة لعبة من التيليفون", v -> pickGame());
        addButton(root, "عرض الألعاب", v -> scanGames(false));
        addButton(root, "مزامنة الحفظ الآن", v -> syncSaves(null));

        section(root, "تحميل ألعاب من الإنترنت");
        addField(root, "رابط مباشر لملف اللعبة", "dlurl", "", false);
        addButton(root, "تحميل من الرابط", v -> {
            collect();
            String u = val("dlurl");
            if (u.isEmpty()) {
                setStatus("حط رابط اللعبة أولا");
            } else {
                downloadFromUrl(u);
            }
        });
        addField(root, "رابط الكتالوج (ملف نصي، كل سطر: الاسم|الرابط)", "catalog", "", false);
        addButton(root, "فتح الكتالوج", v -> openCatalog());
        catalogBox = new LinearLayout(this);
        catalogBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalogBox);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("🔍 بحث عن لعبة");
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderGames();
            }
        });
        root.addView(search);

        gamesBox = new LinearLayout(this);
        gamesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gamesBox);

        setContentView(scroll);
        ensureStorageAccess();
    }

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

    @Override
    protected void onPause() {
        super.onPause();
        // Sauvegarde automatique des reglages a chaque sortie de l'ecran
        collect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (syncOnResume) {
            // On revient de RetroArch : on envoie les nouveaux saves
            syncOnResume = false;
            syncSaves(null);
        }
        // La liste des jeux se rafraichit toute seule (apres telechargement, permission, retour de RetroArch...)
        loadGames();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void section(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setPadding(0, dp(16), 0, dp(4));
        parent.addView(tv);
    }

    private void addButton(LinearLayout parent, String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
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

    private String remoteRoot() {
        String r = val("remote");
        return r.isEmpty() ? "MyRetro" : r;
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    // ------------------------------------------------------------------ taches reseau

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

    /** Execute une tache reseau en arriere-plan, affiche le resultat, puis lance "after" (peut etre null). */
    private void runJob(String busy, final Job job, final Runnable after) {
        collect();
        final String url = val("url");
        final String user = val("user");
        final String pass = val("pass");
        if (url.isEmpty()) {
            setStatus("حط رابط WebDAV أولا");
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

    private void testConnection() {
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

    // ------------------------------------------------------------------ telechargement depuis Internet

    private interface PlainJob {
        String run(WebDav.Progress progress) throws Exception;
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
                String txt = Downloader.fetchText(cat);
                for (String raw : txt.split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    int i = line.indexOf('|');
                    if (i <= 0) {
                        continue;
                    }
                    items.add(new String[]{line.substring(0, i).trim(), line.substring(i + 1).trim()});
                }
            } catch (Exception e) {
                error = "❌ " + friendly(e);
            }
            final String err = error;
            ui.post(() -> showCatalog(items, err));
        }).start();
    }

    private void showCatalog(List<String[]> items, String error) {
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

    // ------------------------------------------------------------------ ajout manuel d une partie

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

    // ------------------------------------------------------------------ liste et lancement des jeux

    private void scanGames(boolean keepLastResult) {
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
}
