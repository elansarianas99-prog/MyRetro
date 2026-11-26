package com.anas.myretro;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Telechargement d'un fichier ou d'un texte depuis une URL publique (http/https). */
public class Downloader {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static Request build(String url) throws IOException {
        HttpUrl u = HttpUrl.parse(url.trim());
        if (u == null) {
            throw new IOException("الرابط غير صالح (خاصو يبدا بـ https:// ولا http://)");
        }
        return new Request.Builder().url(u).build();
    }

    /** Telecharge un petit fichier texte (ex. le catalogue). */
    public static String fetchText(String url) throws IOException {
        Response r = CLIENT.newCall(build(url)).execute();
        try {
            if (!r.isSuccessful()) {
                throw new IOException("الرابط جاوب بالرمز " + r.code());
            }
            return r.body().string();
        } finally {
            r.close();
        }
    }

    private static String nameFromHeader(String contentDisposition) {
        if (contentDisposition == null) {
            return null;
        }
        int i = contentDisposition.toLowerCase().indexOf("filename=");
        if (i < 0) {
            return null;
        }
        String v = contentDisposition.substring(i + 9).trim();
        int semi = v.indexOf(';');
        if (semi >= 0) {
            v = v.substring(0, semi);
        }
        v = v.replace("\"", "").trim();
        return v.isEmpty() ? null : v;
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "game_" + System.currentTimeMillis();
        }
        return n;
    }

    /** Telecharge url dans dir et retourne le nom du fichier cree. */
    public static String download(String url, File dir, WebDav.Progress progress) throws IOException {
        Response r = CLIENT.newCall(build(url)).execute();
        try {
            if (!r.isSuccessful()) {
                throw new IOException("الرابط جاوب بالرمز " + r.code());
            }
            String name = nameFromHeader(r.header("Content-Disposition"));
            if (name == null) {
                List<String> segs = r.request().url().pathSegments();
                String last = segs.isEmpty() ? "" : segs.get(segs.size() - 1);
                name = URLDecoder.decode(last.replace("+", "%2B"), "UTF-8");
            }
            name = sanitize(name);

            if (!dir.exists()) {
                dir.mkdirs();
            }
            File dest = new File(dir, name);
            File tmp = new File(dir, name + ".part");
            InputStream in = r.body().byteStream();
            OutputStream out = new FileOutputStream(tmp);
            try {
                byte[] buf = new byte[8192];
                long total = 0;
                long lastMb = -1;
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    total += n;
                    long mb = total / (1024 * 1024);
                    if (progress != null && mb != lastMb) {
                        lastMb = mb;
                        progress.report("جاري تحميل " + name + " ... " + mb + " MB");
                    }
                }
            } finally {
                out.close();
                in.close();
            }
            if (dest.exists() && !dest.delete()) {
                throw new IOException("لا يمكن استبدال " + name);
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("لا يمكن حفظ " + name);
            }
            return name;
        } finally {
            r.close();
        }
    }
}
