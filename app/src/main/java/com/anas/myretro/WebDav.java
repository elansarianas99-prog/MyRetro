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
import java.util.concurrent.TimeUnit;

import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Client WebDAV minimal : synchronisation bidirectionnelle d'un dossier. */
public class WebDav {
    // Les dates HTTP ont une precision d'une seconde
    private static final long TOLERANCE_MS = 2000;

    private final HttpUrl root;
    private final String credential;
    private final OkHttpClient client;

    public WebDav(String url, String user, String pass) {
        HttpUrl parsed = HttpUrl.parse(url.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("رابط WebDAV غير صالح");
        }
        root = parsed;
        credential = Credentials.basic(user, pass);
        client = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

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

    /** Cree les dossiers distants un par un (MKCOL). Un dossier existant renvoie 405 : on ignore. */
    private void mkdirs(String[] segments) throws IOException {
        for (int i = 1; i <= segments.length; i++) {
            String[] part = Arrays.copyOf(segments, i);
            Response resp = client.newCall(request(part).method("MKCOL", null).build()).execute();
            int code = resp.code();
            resp.close();
            if (code == 401) {
                throw new IOException("اسم المستخدم أو كلمة السر غير صحيحين");
            }
        }
    }

    /** Liste les fichiers d'un dossier distant : nom -> date de modification (ms). */
    private Map<String, Long> list(String[] segments) throws IOException {
        String body = "<?xml version=\"1.0\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:getlastmodified/></d:prop></d:propfind>";
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
                throw new IOException("اسم المستخدم أو كلمة السر غير صحيحين");
            }
            if (code != 207) {
                throw new IOException("PROPFIND " + code);
            }
            return parse(resp.body().string());
        } finally {
            resp.close();
        }
    }

    private static String localName(String tag) {
        int i = tag.indexOf(':');
        return i >= 0 ? tag.substring(i + 1) : tag;
    }

    private Map<String, Long> parse(String xml) throws IOException {
        Map<String, Long> out = new HashMap<>();
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(new StringReader(xml));
            String href = null;
            String lastModified = null;
            StringBuilder text = new StringBuilder();
            int ev = p.getEventType();
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (localName(p.getName()).equals("response")) {
                        href = null;
                        lastModified = null;
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
                            && !href.endsWith("/")) {
                        String name = href.substring(href.lastIndexOf('/') + 1);
                        name = URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                        try {
                            out.put(name, fmt.parse(lastModified).getTime());
                        } catch (Exception ignored) {
                            // date illisible : on ignore ce fichier
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
            throw new IOException("PUT " + f.getName() + " -> " + code);
        }
    }

    private void get(String[] segments, File dest) throws IOException {
        Response resp = client.newCall(request(segments).get().build()).execute();
        try {
            if (!resp.isSuccessful()) {
                throw new IOException("GET " + dest.getName() + " -> " + resp.code());
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
                throw new IOException("rename " + dest.getName());
            }
        } finally {
            resp.close();
        }
    }

    private static boolean safeName(String name) {
        return !name.isEmpty() && !name.equals("..") && !name.equals(".")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /**
     * Synchronise localDir avec <racine>/<sub> sur le serveur.
     * Le fichier le plus recent gagne. Retourne un resume "↑n ↓m".
     */
    public String sync(File localDir, String remoteRoot, String sub) throws IOException {
        if (!localDir.exists()) {
            localDir.mkdirs();
        }
        String[] folder = new String[]{remoteRoot, sub};
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
}
