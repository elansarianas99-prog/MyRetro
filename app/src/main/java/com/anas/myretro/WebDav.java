package com.anas.myretro;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client WebDAV minimal : test de connexion, synchro bidirectionnelle, telechargement de dossier. */
public class WebDav {

    /** Pour afficher l'avancement dans l'interface. */
    public interface Progress {
        void report(String msg);
    }

    // Les dates HTTP ont une precision d'une seconde
    private static final long TOLERANCE_MS = 2000;

    private final HttpUrl root;
    private final String credential;
    private final OkHttpClient client;

    public WebDav(String url, String user, String pass) {
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("رابط WebDAV غير صالح (خاصو يبدا بـ https:// ولا http://)");
        }
        root = parsed;
        credential = Credentials.basic(user, pass);
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // ------------------------------------------------------------------ utilitaires

    private HttpUrl urlOf(String[] segments) {
        HttpUrl.Builder b = root.newBuilder();
        for (String s : segments) {
            b.addPathSegment(s);
        }
        return b.build();
    }

    private Request.Builder request(String[] segments) {
        return new Request.Builder()
                .url(urlOf(segments))
                .header("Authorization", credential);
    }

    private static String[] child(String[] base, String name) {
        String[] out = Arrays.copyOf(base, base.length + 1);
        out[base.length] = name;
        return out;
    }

    /** "MyRetro" + "saves" -> ["MyRetro", "saves"] ; accepte aussi "a/b" comme racine. */
    private static String[] folderOf(String remoteRoot, String sub) throws IOException {
        List<String> parts = new ArrayList<>();
        if (remoteRoot != null) {
            for (String s : remoteRoot.split("/")) {
                String t = s.trim();
                if (!t.isEmpty() && !t.equals(".") && !t.equals("..")) {
                    parts.add(t);
                }
            }
        }
        if (sub != null && !sub.isEmpty()) {
            parts.add(sub);
        }
        if (parts.isEmpty()) {
            throw new IOException("اسم المجلد على السحاب فارغ");
        }
        return parts.toArray(new String[0]);
    }

    private static boolean safeName(String name) {
        return !name.isEmpty() && !name.equals("..") && !name.equals(".")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private static String localName(String tag) {
        int i = tag.indexOf(':');
        return i >= 0 ? tag.substring(i + 1) : tag;
    }

    // ------------------------------------------------------------------ operations WebDAV

    /** Cree les dossiers distants un par un (MKCOL). Un dossier existant renvoie 405 : on ignore. */
    private void mkdirs(String[] segments) throws IOException {
        for (int i = 1; i <= segments.length; i++) {
            String[] part = Arrays.copyOf(segments, i);
            Response resp = client.newCall(request(part).method("MKCOL", null).build()).execute();
            int code = resp.code();
            resp.close();
            if (code == 401) {
                throw new IOException("اسم المستخدم أو كلمة السر غير صحيحين (401)");
            }
            if (code == 403) {
                throw new IOException("ما عندكش الصلاحية فهاد المجلد (403)");
            }
        }
    }

    /** Liste les fichiers d'un dossier distant : nom -> date de modification (ms). */
    private Map<String, Long> list(String[] segments) throws IOException {
        String body = "<?xml version=\"1.0\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>";
        Request r = request(segments)
                .header("Depth", "1")
                .method("PROPFIND", RequestBody.create(MediaType.parse("application/xml"), body))
                .build();
        Response resp = client.newCall(r).execute();
        try {
            int code = resp.code();
            if (code == 404) {
                return new HashMap<>();
            }
            if (code == 401) {
                throw new IOException("اسم المستخدم أو كلمة السر غير صحيحين (401)");
            }
            if (code != 207) {
                throw new IOException("الرابط ما بانش بحال رابط WebDAV (الجواب " + code
                        + "). تأكد من الرابط");
            }
            return parse(resp.body().string(), segments[segments.length - 1]);
        } finally {
            resp.close();
        }
    }

    private Map<String, Long> parse(String xml, String selfName) throws IOException {
        Map<String, Long> out = new HashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            String href = null;
            String lastModified = null;
            boolean collection = false;
            StringBuilder text = new StringBuilder();
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    String sn = localName(p.getName());
                    if (sn.equals("response")) {
                        href = null;
                        lastModified = null;
                        collection = false;
                    } else if (sn.equals("collection")) {
                        collection = true;
                    }
                    text.setLength(0);
                } else if (ev == XmlPullParser.TEXT) {
                    text.append(p.getText());
                } else if (ev == XmlPullParser.END_TAG) {
                    String n = localName(p.getName());
                    if (n.equals("href")) {
                        href = text.toString().trim();
                    } else if (n.equals("getlastmodified")) {
                        lastModified = text.toString().trim();
                    } else if (n.equals("response") && href != null && lastModified != null
                            && !href.endsWith("/") && !collection) {
                        String name = href.substring(href.lastIndexOf('/') + 1);
                        name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                        // certains serveurs renvoient le dossier lui-meme sans "/" final
                        if (!name.equals(selfName)) {
                            try {
                                out.put(name, fmt.parse(lastModified).getTime());
                            } catch (Exception ignored) {
                                // date illisible : on ignore ce fichier
                            }
                        }
                    }
                    text.setLength(0);
                }
                ev = p.next();
            }
        } catch (Exception e) {
            throw new IOException("XML: " + e.getMessage());
        }
        return out;
    }

    private void put(String[] segments, File f) throws IOException {
        Request r = request(segments)
                .put(RequestBody.create(MediaType.parse("application/octet-stream"), f))
                .build();
        Response resp = client.newCall(r).execute();
        int code = resp.code();
        resp.close();
        if (code < 200 || code >= 300) {
            throw new IOException("رفع " + f.getName() + " فشل (" + code + ")");
        }
    }

    private void get(String[] segments, File dest) throws IOException {
        Response resp = client.newCall(request(segments).get().build()).execute();
        try {
            if (!resp.isSuccessful()) {
                throw new IOException("تحميل " + dest.getName() + " فشل (" + resp.code() + ")");
            }
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(dest.getPath() + ".part");
            InputStream in = resp.body().byteStream();
            OutputStream out = new FileOutputStream(tmp);
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
            if (dest.exists() && !dest.delete()) {
                throw new IOException("لا يمكن استبدال " + dest.getName());
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("لا يمكن حفظ " + dest.getName());
            }
        } finally {
            resp.close();
        }
    }

    // ------------------------------------------------------------------ API publique

    /** Verifie l'URL, l'identifiant et le mot de passe, et cree le dossier racine si besoin. */
    public String test(String remoteRoot) throws IOException {
        String[] folder = folderOf(remoteRoot, null);
        mkdirs(folder);
        Map<String, Long> content = list(folder);
        return "✔ الاتصال شغال. المجلد '" + remoteRoot + "' واجد على السحاب (" + content.size() + " ملف)";
    }

    /**
     * Synchronise localDir avec <racine>/<sub> sur le serveur.
     * Le fichier le plus recent gagne. Retourne un resume "↑n ↓m".
     */
    public String sync(File localDir, String remoteRoot, String sub) throws IOException {
        if (!localDir.exists()) {
            localDir.mkdirs();
        }
        String[] folder = folderOf(remoteRoot, sub);
        mkdirs(folder);
        Map<String, Long> remote = list(folder);

        int up = 0;
        int down = 0;
        List<File> uploaded = new ArrayList<>();

        File[] files = localDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile() || f.getName().endsWith(".part") || !safeName(f.getName())) {
                    continue;
                }
                Long r = remote.get(f.getName());
                if (r == null || f.lastModified() > r + TOLERANCE_MS) {
                    put(child(folder, f.getName()), f);
                    uploaded.add(f);
                    up++;
                }
            }
        }

        for (Map.Entry<String, Long> e : remote.entrySet()) {
            if (!safeName(e.getKey())) {
                continue;
            }
            File f = new File(localDir, e.getKey());
            if (!f.exists() || e.getValue() > f.lastModified() + TOLERANCE_MS) {
                get(child(folder, e.getKey()), f);
                f.setLastModified(e.getValue());
                down++;
            }
        }

        // Aligne la date locale sur celle du serveur pour eviter des transferts inutiles
        if (!uploaded.isEmpty()) {
            Map<String, Long> after = list(folder);
            for (File f : uploaded) {
                Long t = after.get(f.getName());
                if (t != null) {
                    f.setLastModified(t);
                }
            }
        }
        return "↑" + up + " ↓" + down;
    }

    /** Telecharge (sans jamais rien envoyer) les fichiers de <racine>/<sub> vers localDir. */
    public int pullAll(File localDir, String remoteRoot, String sub, Progress progress) throws IOException {
        if (!localDir.exists()) {
            localDir.mkdirs();
        }
        String[] folder = folderOf(remoteRoot, sub);
        mkdirs(folder);
        Map<String, Long> remote = new TreeMap<>(list(folder));

        int total = remote.size();
        int index = 0;
        int count = 0;
        for (Map.Entry<String, Long> e : remote.entrySet()) {
            index++;
            if (!safeName(e.getKey()) || e.getKey().endsWith(".part")) {
                continue;
            }
            File f = new File(localDir, e.getKey());
            if (!f.exists() || e.getValue() > f.lastModified() + TOLERANCE_MS) {
                if (progress != null) {
                    progress.report("جاري تحميل " + e.getKey() + " (" + index + "/" + total + ")");
                }
                get(child(folder, e.getKey()), f);
                f.setLastModified(e.getValue());
                count++;
            }
        }
        return count;
    }
}
