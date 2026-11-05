package ua.budivelnyk.ua;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    public void clearCatalog() throws IOException {
        File catalog = new File(baseDir, "Каталог");
        if (catalog.exists()) {
            deleteTree(catalog);
            if (catalog.exists()) throw new IOException("Не вдалося повністю видалити каталог серій");
        }
    }

    public JSONObject importZip(Uri uri) throws Exception {
        if (!baseDir.exists() && !baseDir.mkdirs()) throw new IOException("Не вдалося створити каталог серій");
        File staging = new File(baseDir, "_import_" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Не вдалося створити тимчасову папку імпорту");
        File archive = File.createTempFile("butdetal-series-", ".zip", context.getCacheDir());
        int entries = 0, pdfs = 0, images = 0, texts = 0;
        long total = 0;
        boolean success = false;
        try {
            ContentResolver resolver = context.getContentResolver();
            try (InputStream raw = resolver.openInputStream(uri);
                 OutputStream copy = new BufferedOutputStream(new FileOutputStream(archive))) {
                if (raw == null) throw new IOException("Не вдалося відкрити ZIP-файл");
                byte[] b = new byte[32768];
                int n;
                while ((n = raw.read(b)) != -1) copy.write(b, 0, n);
            }
            // Read through the ZIP central directory rather than a streaming inflater.
            // This avoids truncated ZLIB stream errors when a provider returns a non-seekable stream.
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive, java.nio.charset.Charset.forName("CP866"))) {
                Enumeration<? extends ZipEntry> it = zip.entries();
                byte[] buffer = new byte[32768];
                while (it.hasMoreElements()) {
                    ZipEntry entry = it.nextElement();
                    if (++entries > MAX_ENTRIES) throw new IOException("Забагато файлів в архіві");
                    String name = entry.getName().replace((char)92, '/');
                    if (name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains("/..")) continue;
                    File out = new File(staging, name);
                    if (!out.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) {
                        throw new IOException("Небезпечний шлях у ZIP");
                    }
                    if (entry.isDirectory()) {
                        if (!out.exists() && !out.mkdirs()) throw new IOException("Не вдалося створити папку");
                        continue;
                    }
                    String lower = name.toLowerCase(Locale.ROOT);
                    boolean pdf = lower.endsWith(".pdf");
                    boolean image = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
                    boolean text = lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv");
                    if (!(pdf || image || text)) continue;
                    File parent = out.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не вдалося створити вкладену папку");
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            total += n;
                            if (total > MAX_TOTAL_BYTES) throw new IOException("Розмір розпакованих даних перевищує 1,8 ГБ");
                            os.write(buffer, 0, n);
                        }
                    }
                    if (pdf) pdfs++; else if (image) images++; else texts++;
                }
            }
            if (pdfs == 0) throw new IOException("У ZIP не знайдено PDF-креслень");
            File catalogRoot = findCatalogRoot(staging);
            if (catalogRoot == null) catalogRoot = staging;
            File destination = new File(baseDir, "Каталог");
            File backup = new File(baseDir, "_Каталог_backup_" + System.currentTimeMillis());
            boolean hadOld = destination.exists();
            boolean movedOld = false;
            boolean installed = false;
            try {
                // Keep the previous catalog intact until the new one is fully installed.
                if (hadOld) {
                    if (!destination.renameTo(backup)) {
                        copyTree(destination, backup);
                        deleteTree(destination);
                    }
                    movedOld = true;
                }
                if (!catalogRoot.renameTo(destination)) {
                    copyTree(catalogRoot, destination);
                }
                installed = destination.isDirectory();
                if (!installed) throw new IOException("Не вдалося встановити новий каталог");
                if (movedOld) deleteTree(backup);
            } catch (Exception installError) {
                // Roll back to the previous catalog if installation failed.
                if (destination.exists()) deleteTree(destination);
                if (movedOld && backup.exists()) {
                    if (!backup.renameTo(destination)) copyTree(backup, destination);
                }
                throw installError;
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
            deleteTree(staging);
            if (archive.exists()) archive.delete();
            if (!success) { /* retain no partial catalog from failed import */ }
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


    /** List top-level series folders for folder-by-folder cloud synchronization. */
    public JSONArray listSyncFolders() throws Exception {
        JSONArray out = new JSONArray();
        File root = new File(baseDir, "Каталог");
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return out;
        Arrays.sort(dirs, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File dir : dirs) {
            if (dir.getName().startsWith("_")) continue;
            JSONObject item = new JSONObject();
            item.put("name", dir.getName());
            item.put("modified", newestModified(dir));
            item.put("bytes", treeBytes(dir));
            out.put(item);
        }
        return out;
    }

    /** Save prior sync state to distinguish remote deletions from new local folders. */
    public JSONArray getKnownSyncFolders() {
        try {
            JSONArray parsed=new JSONArray(context.getSharedPreferences("butdetal_series_sync",Context.MODE_PRIVATE).getString("known_folders","[]")),clean=new JSONArray();
            Set<String> seen=new HashSet<>();
            for(int i=0;i<parsed.length();i++){String n=parsed.optString(i,"").trim();if(!n.isEmpty()&&!n.equals(".")&&!n.equals("..")&&!n.contains("/")&&!n.contains("\\")&&!n.startsWith("_")&&seen.add(n))clean.put(n);}
            return clean;
        }catch(Exception e){return new JSONArray();}
    }
    public boolean setKnownSyncFolders(String json) {
        try {
            JSONArray incoming=new JSONArray(json==null?"[]":json),clean=new JSONArray();Set<String> seen=new HashSet<>();
            for(int i=0;i<incoming.length();i++){String n=incoming.optString(i,"").trim();if(!n.isEmpty()&&!n.equals(".")&&!n.equals("..")&&!n.contains("/")&&!n.contains("\\")&&!n.startsWith("_")&&seen.add(n))clean.put(n);}
            return context.getSharedPreferences("butdetal_series_sync",Context.MODE_PRIVATE).edit().putString("known_folders",clean.toString()).commit();
        }catch(Exception e){return false;}
    }
    public boolean deleteSyncFolder(String name) throws IOException {
        if(name==null||name.trim().isEmpty()||name.contains("/")||name.contains("\\")||name.equals("..")||name.startsWith("_"))throw new IOException("Некоректна назва папки серії");
        File root=new File(baseDir,"Каталог"),folder=safeResolve(root,name);
        if(folder==null)throw new IOException("Небезпечний шлях до папки серії");
        if(folder.exists()){deleteTree(folder);if(folder.exists())throw new IOException("Не вдалося видалити папку серії");}
        return true;
    }

    private long newestModified(File dir) {
        long newest = dir.lastModified();
        File[] children = dir.listFiles();
        if (children != null) for (File child : children)
            newest = Math.max(newest, child.isDirectory() ? newestModified(child) : child.lastModified());
        return newest;
    }

    private long treeBytes(File dir) {
        long sum = 0;
        File[] children = dir.listFiles();
        if (children != null) for (File child : children)
            sum += child.isDirectory() ? treeBytes(child) : child.length();
        return sum;
    }

    public String exportFolderZipBase64(String folderName) throws Exception {
        File root = new File(baseDir, "Каталог");
        File folder = safeResolve(root, folderName);
        if (folder == null || !folder.isDirectory() || folderName.contains("/"))
            throw new IOException("Папку серії не знайдено");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(bytes))) {
            zipTree(folder, folder, zip);
        }
        byte[] raw = bytes.toByteArray();
        if (raw.length > 60L * 1024L * 1024L)
            throw new IOException("Окрема папка серії перевищує 60 МБ; її потрібно передавати файлами.");
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    /** Stream a folder ZIP to cache and expose bounded Base64 reads for WebView sync. */
    public JSONObject createSyncZip(String folderName) throws Exception {
        File root = new File(baseDir, "Каталог");
        File folder = safeResolve(root, folderName);
        if (folder == null || !folder.isDirectory() || folderName.contains("/") || folderName.contains("\\"))
            throw new IOException("Папку серії не знайдено");
        File archive = File.createTempFile("butdetal-sync-", ".zip", context.getCacheDir());
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(archive)))) {
            zipTree(folder, folder, zip);
        }
        JSONObject result = new JSONObject();
        result.put("path", archive.getAbsolutePath());
        result.put("size", archive.length());
        return result;
    }

    public String readSyncZipChunkBase64(String path, long offset, int length) throws Exception {
        File archive = checkedCacheFile(path);
        if (offset < 0 || length < 0 || length > 8 * 1024 * 1024 || offset > archive.length())
            throw new IOException("Некоректний діапазон частини ZIP");
        int wanted = (int)Math.min((long)length, archive.length() - offset);
        byte[] bytes = new byte[wanted];
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            input.seek(offset);
            input.readFully(bytes);
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    public String beginSyncReceive() throws Exception {
        File archive = File.createTempFile("butdetal-sync-in-", ".zip", context.getCacheDir());
        return archive.getAbsolutePath();
    }

    public String appendSyncReceiveChunk(String path, String base64) throws Exception {
        File archive = checkedCacheFile(path);
        byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Частина ZIP завелика");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(archive, true))) {
            out.write(bytes);
        }
        return "OK";
    }

    public JSONObject finishSyncReceive(String folderName, String path) throws Exception {
        File archive = checkedCacheFile(path);
        return mergeFolderZipFile(folderName, archive);
    }

    public String deleteSyncTempFile(String path) throws Exception {
        File file = checkedCacheFile(path);
        return file.delete() || !file.exists() ? "OK" : "ERROR:Не вдалося видалити тимчасовий файл синхронізації";
    }



    private File checkedCacheFile(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) throw new IOException("Не задано тимчасовий файл синхронізації");
        File file = new File(path).getCanonicalFile();
        File cache = context.getCacheDir().getCanonicalFile();
        if (!file.getPath().startsWith(cache.getPath() + File.separator) || !file.isFile())
            throw new IOException("Некоректний тимчасовий файл синхронізації");
        return file;
    }

    public JSONObject mergeFolderZipBase64(String folderName, String base64) throws Exception {
        if (folderName == null || folderName.trim().isEmpty() || folderName.contains("/") || folderName.contains("\\") || folderName.equals(".."))
            throw new IOException("Некоректна назва папки серії");
        File archive = File.createTempFile("butdetal-series-folder-", ".zip", context.getCacheDir());
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) {
            byte[] raw = Base64.decode(base64, Base64.DEFAULT);
            out.write(raw);
        }
        return mergeFolderZipFile(folderName, archive);
    }

    public JSONObject mergeFolderZipFile(String folderName, File archive) throws Exception {
        if (folderName == null || folderName.trim().isEmpty() || folderName.contains("/") || folderName.contains("\\") || folderName.equals(".."))
            throw new IOException("Некоректна назва папки серії");
        File root = new File(baseDir, "Каталог");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Не вдалося створити каталог серій");
        File targetRoot = new File(root, folderName);
        File staging = new File(root, "_sync_" + System.currentTimeMillis());
        if (!staging.mkdirs()) throw new IOException("Не вдалося створити тимчасову папку синхронізації");
        int added = 0, conflicts = 0; long total = 0; int entries = 0;
        try {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive, java.nio.charset.Charset.forName("CP866"))) {
                Enumeration<? extends ZipEntry> it = zip.entries(); byte[] buffer = new byte[32768];
                while (it.hasMoreElements()) {
                    ZipEntry entry = it.nextElement();
                    if (++entries > MAX_ENTRIES) throw new IOException("Забагато файлів у серії");
                    String name = entry.getName().replace((char)92, '/');
                    if (entry.isDirectory() || name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains("/..")) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!(lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv"))) continue;
                    File dest = new File(staging, name);
                    if (!dest.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator)) throw new IOException("Небезпечний шлях у ZIP");
                    File parent = dest.getParentFile(); if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не вдалося створити вкладену папку");
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(entry)); OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                        int n; while ((n = in.read(buffer)) != -1) { total += n; if (total > MAX_TOTAL_BYTES) throw new IOException("Серія завелика"); out.write(buffer, 0, n); }
                    }
                }
            }
            if (!targetRoot.exists() && !targetRoot.mkdirs()) throw new IOException("Не вдалося створити папку серії");
            File[] incoming = staging.listFiles();
            if (incoming != null) for (File f : incoming) {
                File dest = new File(targetRoot, f.getName());
                if (dest.exists()) {
                    if (f.isDirectory()) { mergeDirectory(f, dest); continue; }
                    if (Arrays.equals(readBytes(dest), readBytes(f))) continue;
                    String nm=f.getName(); int dot=nm.lastIndexOf('.'); String stem=dot>0?nm.substring(0,dot):nm, ext=dot>0?nm.substring(dot):"";
                    if (hasIdenticalConflictCopy(targetRoot, stem, ext, f)) continue;
                    dest=new File(targetRoot,stem+"__cloud_conflict_"+System.currentTimeMillis()+ext); conflicts++;
                }
                if (f.isDirectory()) copyTree(f,dest);
                else try (InputStream in=new FileInputStream(f); OutputStream out=new FileOutputStream(dest)) { byte[] b=new byte[32768]; int n; while((n=in.read(b))!=-1)out.write(b,0,n); }
                added++;
            }
        } finally { deleteTree(staging); if (archive.exists()) archive.delete(); }
        JSONObject result=new JSONObject(); result.put("added",added); result.put("conflicts",conflicts); result.put("entries",entries); return result;
    }

    /** Avoid generating a new conflict copy on every sync for identical incoming bytes. */
    private boolean hasIdenticalConflictCopy(File directory, String stem, String ext, File incoming) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) return false;
        String prefix = stem + "__cloud_conflict_";
        for (File candidate : files) {
            String name = candidate.getName();
            if (candidate.isFile() && name.startsWith(prefix) && name.endsWith(ext)
                    && Arrays.equals(readBytes(candidate), readBytes(incoming))) return true;
        }
        return false;
    }

    private void mergeDirectory(File source, File target) throws IOException {
        if (!target.exists() && !target.mkdirs()) throw new IOException("Не вдалося створити папку");
        File[] children=source.listFiles(); if(children==null)return;
        for(File child:children) {
            File dest=new File(target,child.getName());
            if(child.isDirectory()) mergeDirectory(child,dest);
            else if(!dest.exists()) try(InputStream in=new FileInputStream(child);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            else if(!Arrays.equals(readBytes(dest),readBytes(child))) {
                String nm=child.getName();int dot=nm.lastIndexOf('.');String stem=dot>0?nm.substring(0,dot):nm,ext=dot>0?nm.substring(dot):"";
                if (hasIdenticalConflictCopy(target, stem, ext, child)) continue;
                File conflict=new File(target,stem+"__cloud_conflict_"+System.currentTimeMillis()+ext);
                try(InputStream in=new FileInputStream(child);OutputStream out=new FileOutputStream(conflict)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            }
        }
    }

    /** Export the current catalog as a bounded Base64 ZIP for authenticated cloud sync. */
    public String exportCatalogZipBase64() throws Exception {
        File root = new File(baseDir, "Каталог");
        if (!root.isDirectory()) return "";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(bytes))) { zipTree(root, root, zip); }
        byte[] raw = bytes.toByteArray();
        if (raw.length > 60L * 1024L * 1024L) throw new IOException("Архів серій більший за ліміт синхронізації 60 МБ.");
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private void zipTree(File root, File current, ZipOutputStream zip) throws IOException {
        File[] children = current.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        byte[] buffer = new byte[32768];
        for (File child : children) {
            if (child.getName().startsWith("_")) continue;
            if (child.isDirectory()) { zipTree(root, child, zip); continue; }
            String rel = root.toURI().relativize(child.toURI()).getPath();
            zip.putNextEntry(new ZipEntry(rel));
            try (InputStream in = new BufferedInputStream(new FileInputStream(child))) {
                int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
            }
            zip.closeEntry();
        }
    }

    /** Add cloud catalog files without clearing local files; divergent same-name files are retained as conflict copies. */
    public JSONObject mergeCatalogZipBase64(String base64) throws Exception {
        if (base64 == null || base64.isEmpty()) {
            JSONObject empty = new JSONObject(); empty.put("added", 0); empty.put("conflicts", 0); return empty;
        }
        if (base64.length() > 85L * 1024L * 1024L) throw new IOException("Отриманий ZIP перевищує ліміт 60 МБ.");
        byte[] raw = Base64.decode(base64, Base64.DEFAULT);
        File root = new File(baseDir, "Каталог");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Не вдалося створити каталог серій.");
        File archive = File.createTempFile("butdetal-series-sync-", ".zip", context.getCacheDir());
        int added = 0, conflicts = 0; long total = 0; int entries = 0;
        try {
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(archive))) { out.write(raw); }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive, java.nio.charset.Charset.forName("CP866"))) {
                Enumeration<? extends ZipEntry> it = zip.entries(); byte[] buffer = new byte[32768];
                while (it.hasMoreElements()) {
                    ZipEntry entry = it.nextElement();
                    if (++entries > MAX_ENTRIES) throw new IOException("Забагато файлів у синхронізації.");
                    String name = entry.getName().replace((char)92, '/');
                    if (entry.isDirectory() || name.startsWith("/") || name.contains("../") || name.equals("..") || name.contains("/..")) continue;
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!(lower.endsWith(".pdf") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".txt") || lower.endsWith(".json") || lower.endsWith(".csv"))) continue;
                    File dest = new File(root, name);
                    if (!dest.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) throw new IOException("Небезпечний шлях у ZIP.");
                    File parent = dest.getParentFile();
                    if (!parent.exists() && !parent.mkdirs()) throw new IOException("Не вдалося створити папку.");
                    ByteArrayOutputStream fileData = new ByteArrayOutputStream();
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(entry))) {
                        int n; while ((n = in.read(buffer)) != -1) { total += n; if (total > MAX_TOTAL_BYTES) throw new IOException("Дані перевищують 1,8 ГБ."); fileData.write(buffer, 0, n); }
                    }
                    byte[] incoming = fileData.toByteArray();
                    if (dest.exists()) {
                        if (Arrays.equals(readBytes(dest), incoming)) continue;
                        String base = dest.getName(); int dot = base.lastIndexOf('.');
                        String stem = dot > 0 ? base.substring(0, dot) : base, ext = dot > 0 ? base.substring(dot) : "";
                        File conflict = new File(parent, stem + "__cloud_conflict_" + System.currentTimeMillis() + ext);
                        int suffix = 2; while (conflict.exists()) conflict = new File(parent, stem + "__cloud_conflict_" + (System.currentTimeMillis() + suffix++) + ext);
                        dest = conflict; conflicts++;
                    }
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) { out.write(incoming); }
                    added++;
                }
            }
        } finally { if (archive.exists()) archive.delete(); }
        JSONObject result = new JSONObject(); result.put("added", added); result.put("conflicts", conflicts); result.put("entries", entries); return result;
    }

    private byte[] readBytes(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file)); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
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
            String textPath = rel.substring(0, rel.length()-4) + ".txt";
            File extractedText = new File(root, "Текст_для_пошуку/" + textPath);
            String extracted = "";
            if (!query.isEmpty() && extractedText.isFile() && extractedText.length() < 2L*1024L*1024L) {
                try (InputStream in = new FileInputStream(extractedText); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                    byte[] b = new byte[8192]; int n; int read = 0;
                    while ((n = in.read(b)) != -1 && read < 512*1024) { data.write(b,0,n); read += n; }
                    extracted = data.toString("UTF-8").toLowerCase(Locale.ROOT);
                } catch (Exception ignored) {}
            }
            String hay = (title + " " + category + " " + rel).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !hay.contains(query) && !extracted.contains(query)) continue;
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
        String imageRelative = "Креслення_JPG/" + pdfRelativePath.substring(0, pdfRelativePath.length()-4);
        File images = safeResolve(root, imageRelative);
        if (images == null) return out;
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
