package com.anas.myretro;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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

    /** Extensions de jeux reconnues (doivent correspondre a MainActivity.coreFor). */
    private static final String[] ROM_EXT = {
            ".nes", ".fds", ".gb", ".gbc", ".gba", ".sfc", ".smc",
            ".md", ".gen", ".smd", ".cue", ".chd", ".pbp"
    };

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

    private static boolean isRom(String name) {
        String l = name.toLowerCase(Locale.US);
        for (String e : ROM_EXT) {
            if (l.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    /** Extrait d'un zip uniquement les fichiers de jeux, a plat dans dir (pas de sous-dossiers). */
    private static List<String> unzipRoms(File zip, File dir) throws IOException {
        List<String> out = new ArrayList<>();
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String entryName = e.getName();
                int cut = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
                String base = entryName.substring(cut + 1);
                if (base.isEmpty() || !isRom(base)) {
                    continue;
                }
                base = sanitize(base);
                OutputStream o = new FileOutputStream(new File(dir, base));
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        o.write(buf, 0, n);
                    }
                } finally {
                    o.close();
                }
                out.add(base);
            }
        } finally {
            zin.close();
        }
        return out;
    }

    /**
     * Telecharge url dans dir. Si c'est un zip contenant des jeux, les jeux sont extraits
     * et le zip est supprime. Retourne le nom (ou les noms) des fichiers de jeu obtenus.
     */
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

            if (name.toLowerCase(Locale.US).endsWith(".zip")) {
                List<String> roms = unzipRoms(dest, dir);
                if (!roms.isEmpty()) {
                    dest.delete();
                    StringBuilder sb = new StringBuilder();
                    for (String s : roms) {
                        if (sb.length() > 0) {
                            sb.append(", ");
                        }
                        sb.append(s);
                    }
                    return sb.toString();
                }
                return name + " (zip: ما لقيت حتى لعبة داخلو)";
            }
            return name;
        } finally {
            r.close();
        }
    }
}
