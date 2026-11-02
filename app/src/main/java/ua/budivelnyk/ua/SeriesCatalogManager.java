package ua.budivelnyk.ua;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Offline ZIP importer and searchable index for construction series. */
public final class SeriesCatalogManager {
    private final Context context;
    private final File baseDir;
    private static final long MAX_TOTAL_BYTES = 1800L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100000;

    public SeriesCatalogManager(Context context) {
        this.context = context.getApplicationContext();
        File docs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (docs == null) docs = new File(context.getFilesDir(), "Documents");
        baseDir = new File(docs, "BUTDETAL_Series");
    }

    public File getBaseDir() { return baseDir; }

    public JSONObject importZip(Uri uri) throws Exception {
        if (!baseDir.exists() && !baseDir.mkdirs()) throw new IOException("Не вдалося створити каталог серій");
        File staging = new File(baseDir, "_import_" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Не вдалося створити тимчасову папку імпорту");
        int entries = 0, pdfs = 0, images = 0, texts = 0;
        long total = 0;
        boolean success = false;
        ContentResolver resolver = context.getContentResolver();
        try (InputStream raw = resolver.openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
            if (raw == null) throw new IOException("Не вдалося відкрити ZIP-файл");
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Забагато файлів в архіві");
                String name = entry.getName().replace((char)92,'/');
                if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains("/..")) {
                    zip.closeEntry(); continue;
                }
                if (entry.isDirectory()) {
                    File dir = new File(staging, name);
                    if (!dir.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) throw new IOException("Небезпечний шлях у ZIP");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Не вдалося створити папку");
                    zip.closeEntry(); continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                boolean pdf = lower.endsWith(".pdf");
                boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
                boolean text = lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv");
                if (!(pdf || image || text)) { zip.closeEntry(); continue; }
                File out = new File(staging, name);
                if (!out.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) throw new IOException("Небезпечний шлях у ZIP");
                File parent = out.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не вдалося створити вкладену папку");
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                    int n;
                    while ((n = zip.read(buffer)) != -1) {
                        total += n;
                        if (total > MAX_TOTAL_BYTES) throw new IOException("Розмір розпакованих даних перевищує 1,8 ГБ");
                        os.write(buffer, 0, n);
                    }
                }
                if (pdf) pdfs++; else if (image) images++; else texts++;
                zip.closeEntry();
            }
            if (pdfs == 0) throw new IOException("У ZIP не знайдено PDF-креслень");
            File catalogRoot = findCatalogRoot(staging);
            if (catalogRoot == null) catalogRoot = staging;
            File destination = new File(baseDir, "Каталог");
            if (destination.exists()) deleteTree(destination);
            File sourceRoot = catalogRoot;
            if (!sourceRoot.renameTo(destination)) {
                copyTree(sourceRoot, destination);
                deleteTree(staging);
            } else if (sourceRoot.equals(staging)) {
                // The staging directory itself was moved; no cleanup needed.
            } else {
                deleteTree(staging);
            }
            success = true;
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("pdfCount", pdfs);
            result.put("imageCount", images);
            result.put("textCount", texts);
            result.put("entryCount", entries);
            result.put("bytes", total);
            result.put("catalogRoot", destination.getAbsolutePath());
            result.put("message", "Імпорт завершено");
            return result;
        } finally {
            if (!success) deleteTree(staging);
        }
    }

    private File findCatalogRoot(File dir) {
        if (new File(dir, "catalog.json").isFile()) return dir;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children) if (child.isDirectory()) {
            File found = findCatalogRoot(child);
            if (found != null) return found;
        }
        return null;
    }

    private void copyTree(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) throw new IOException("Не вдалося скопіювати каталог");
            File[] files = source.listFiles();
            if (files != null) for (File f : files) copyTree(f, new File(target, f.getName()));
        } else {
            try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
                byte[] b = new byte[32768]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
        }
    }

    public JSONArray listSeries(String query) throws Exception {
        JSONArray out = new JSONArray();
        if (!baseDir.exists()) return out;
        File root = new File(baseDir, "Каталог");
        if (!root.exists()) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        collectPdfs(root, root, q, out);
        return out;
    }

    private void collectPdfs(File root, File dir, String query, JSONArray out) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            if (f.isDirectory()) { collectPdfs(root, f, query, out); continue; }
            if (!f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();
            String category = f.getParentFile().equals(root) ? "Інші серії" : f.getParentFile().getName();
            String title = f.getName().substring(0, f.getName().length()-4);
            String hay = (title + " " + category + " " + rel).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !hay.contains(query)) continue;
            JSONObject item = new JSONObject();
            item.put("title", title); item.put("category", category); item.put("pdf", rel);
            item.put("size", f.length()); item.put("modified", f.lastModified());
            out.put(item);
        }
    }

    public JSONArray listPages(String pdfRelativePath) throws Exception {
        JSONArray out = new JSONArray();
        File root = new File(baseDir, "Каталог");
        File pdf = safeResolve(root, pdfRelativePath);
        if (pdf == null || !pdf.isFile()) return out;
        String category = pdf.getParentFile().equals(root) ? "" : pdf.getParentFile().getName();
        String title = pdf.getName().substring(0, pdf.getName().length()-4);
        File images = new File(root, "Креслення_JPG");
        if (!category.isEmpty()) images = new File(images, category);
        images = new File(images, title);
        File[] pages = images.listFiles((d,n) -> {
            String x=n.toLowerCase(Locale.ROOT);
            return x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png");
        });
        if (pages == null) return out;
        Arrays.sort(pages, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File page : pages) {
            JSONObject item = new JSONObject();
            item.put("name", page.getName());
            item.put("path", root.toURI().relativize(page.toURI()).getPath());
            out.put(item);
        }
        return out;
    }

    public File resolve(String relativePath) {
        return safeResolve(new File(baseDir, "Каталог"), relativePath);
    }

    private File safeResolve(File root, String relativePath) {
        try {
            if (relativePath == null || relativePath.startsWith("/") || relativePath.contains("..")) return null;
            File f = new File(root, relativePath);
            return f.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) ? f : null;
        } catch (Exception e) { return null; }
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        file.delete();
    }
}
