package ua.budivelnyk.ua;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.googlecode.tesseract.android.TessBaseAPI;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.content.SharedPreferences;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;
import android.content.pm.PackageManager;

public class MainActivity extends Activity {
    private static final int PICK=1401;
    private static final int BACKUP_EXPORT=1402;
    private static final int BACKUP_IMPORT=1403;
    private static final int SERIES_IMPORT=1404;
    private SeriesCatalogManager seriesCatalogManager;
    private String pendingBackupJson="{}";
    private AndroidStorage androidStorage;
    private StringBuilder backupJsonBuilder=new StringBuilder();
    private int nextBackupChunk=0;
    private ValueCallback<Uri[]> callback;
    private WebView webView;
    private DownloadManager downloadManager;
    private long updateDownloadId=-1;
    private BroadcastReceiver updateReceiver;

    private File folder(String category) {
        File root=getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if(root==null) root=getFilesDir();
        File dir=new File(root,"drawings".equals(category)?"Креслення":"Фото_виробів");
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }
    private File pdfFolder() {
        File root=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if(root==null) root=getFilesDir();
        File dir=new File(root,"BUTDETAL_PDF");
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }
    private static byte[] decodeData(String data) {
        int comma=data.indexOf(',');
        return Base64.decode(comma>=0?data.substring(comma+1):data,Base64.DEFAULT);
    }
    private static byte[] bytes(File f)throws Exception {
        try(FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192]; int n;
            while((n=in.read(b))>0) out.write(b,0,n);
            return out.toByteArray();
        }
    }
    private static String read(File f)throws Exception {
        return new String(bytes(f),StandardCharsets.UTF_8);
    }
    private static String safeName(String n) {
        String s=n==null?"file":n.replaceAll("[^a-zA-Z0-9а-яА-ЯіІїЇєЄ._-]","_");
        return s.length()>100?s.substring(0,100):s;
    }

    private File ensureTessdata() throws Exception {
        File base=new File(getFilesDir(),"tesseract");
        File data=new File(base,"tessdata");
        if(!data.exists()&&!data.mkdirs()) throw new IOException("Не вдалося створити папку tessdata");
        for(String lang:new String[]{"ukr","rus","eng"}) {
            File target=new File(data,lang+".traineddata");
            if(!target.exists() || target.length()<100000) {
                try(InputStream in=getAssets().open("tessdata/"+lang+".traineddata");
                    OutputStream out=new FileOutputStream(target)) {
                    byte[] b=new byte[8192]; int n;
                    while((n=in.read(b))>0) out.write(b,0,n);
                }
            }
            if(!target.exists() || target.length()<100000)
                throw new IOException("Мовний файл пошкоджений: "+lang);
        }
        return base;
    }

    private String recognizeBitmap(Bitmap bitmap) throws Exception {
        File base=ensureTessdata();
        TessBaseAPI api=new TessBaseAPI();
        boolean initialized=false;
        try {
            initialized=api.init(base.getAbsolutePath(),"ukr+rus+eng");
            if(!initialized) throw new IOException("Tesseract не зміг завантажити ukr/rus/eng");
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
            api.setImage(bitmap);
            String result=api.getUTF8Text();
            return result==null?"":result.trim();
        } finally {
            if(initialized) { api.clear(); api.end(); }
        }
    }

    private static final String CREDENTIAL_ALIAS = "butdetal_credential_key_v1";
    private SecretKey credentialKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(CREDENTIAL_ALIAS)) return ((KeyStore.SecretKeyEntry)ks.getEntry(CREDENTIAL_ALIAS,null)).getSecretKey();
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(CREDENTIAL_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }
    private String encryptCredential(String plain) throws Exception {
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,credentialKey());
        byte[] enc=c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv=c.getIV(), all=new byte[iv.length+enc.length]; System.arraycopy(iv,0,all,0,iv.length);System.arraycopy(enc,0,all,iv.length,enc.length);
        return Base64.encodeToString(all,Base64.NO_WRAP);
    }
    private String decryptCredential(String stored) throws Exception {
        byte[] all=Base64.decode(stored,Base64.DEFAULT); if(all.length<13) return "";
        byte[] iv=Arrays.copyOfRange(all,0,12), enc=Arrays.copyOfRange(all,12,all.length);
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,credentialKey(),new GCMParameterSpec(128,iv));
        return new String(c.doFinal(enc),StandardCharsets.UTF_8);
    }

    private void notifySeriesImport(JSONObject result) {
        if (webView == null) return;
        webView.post(() -> webView.evaluateJavascript(
            "window.onSeriesImportResult && window.onSeriesImportResult(" + result.toString() + ")", null));
    }

    public class AndroidStorage {
        @JavascriptInterface public String chooseSeriesArchive() {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                runOnUiThread(() -> startActivityForResult(intent, SERIES_IMPORT));
                return "PICKER_OPENED";
            } catch (Exception e) { return "Помилка відкриття вибору ZIP: " + e.getMessage(); }
        }
        @JavascriptInterface public String listSeriesSyncFolders() {
            try { return seriesCatalogManager.listSyncFolders().toString(); } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public String exportSeriesFolderZipBase64(String folderName) {
            try { return seriesCatalogManager.exportFolderZipBase64(folderName); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String mergeSeriesFolderZipBase64(String folderName, String base64) {
            try { return seriesCatalogManager.mergeFolderZipBase64(folderName, base64).toString(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String createSeriesSyncZip(String folderName) {
            try { return seriesCatalogManager.createSyncZip(folderName).toString(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String readSeriesSyncZipChunkBase64(String path, long offset, int length) {
            try { return seriesCatalogManager.readSyncZipChunkBase64(path, offset, length); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String beginSeriesSyncReceive() {
            try { return seriesCatalogManager.beginSyncReceive(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String appendSeriesSyncReceiveChunk(String path, String base64) {
            try { return seriesCatalogManager.appendSyncReceiveChunk(path, base64); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String finishSeriesSyncReceive(String folderName, String path) {
            try { return seriesCatalogManager.finishSyncReceive(folderName, path).toString(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String deleteSeriesSyncTempFile(String path) {
            try { return seriesCatalogManager.deleteSyncTempFile(path); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String exportSeriesCatalogZipBase64() {
            try { return seriesCatalogManager.exportCatalogZipBase64(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String mergeSeriesCatalogZipBase64(String base64) {
            try { return seriesCatalogManager.mergeCatalogZipBase64(base64).toString(); }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String listSeriesCatalog(String query) {
            try { return seriesCatalogManager.listSeries(query).toString(); }
            catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public String getKnownSeriesSyncFolders() {
            try { return seriesCatalogManager.getKnownSyncFolders().toString(); } catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public String setKnownSeriesSyncFolders(String json) {
            try { return seriesCatalogManager.setKnownSyncFolders(json) ? "OK" : "ERROR:Не вдалося зберегти стан синхронізації"; }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String deleteSeriesSyncFolder(String name) {
            try { seriesCatalogManager.deleteSyncFolder(name); return "OK"; }
            catch (Exception e) { return "ERROR:" + e.getMessage(); }
        }
        @JavascriptInterface public String clearSeriesCatalog() {
            try { seriesCatalogManager.clearCatalog(); return "OK"; }
            catch (Exception e) { return "Помилка очищення каталогу серій: " + e.getMessage(); }
        }
        @JavascriptInterface public String listSeriesPages(String pdfRelativePath) {
            try { return seriesCatalogManager.listPages(pdfRelativePath).toString(); }
            catch (Exception e) { return "[]"; }
        }
        @JavascriptInterface public String readSeriesPageBase64(String relativePath) {
            try {
                File f = seriesCatalogManager.resolve(relativePath);
                if (f == null || !f.isFile() || f.length() > 20L * 1024L * 1024L) return "";
                return Base64.encodeToString(bytes(f), Base64.NO_WRAP);
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String recognizeSeriesPdf(String relativePath) {
            File source = seriesCatalogManager.resolve(relativePath);
            if (source == null || !source.isFile()) return "PDF не знайдено";
            StringBuilder recognized = new StringBuilder();
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                int count = Math.min(renderer.getPageCount(), 40);
                for (int i = 0; i < count; i++) {
                    PdfRenderer.Page page = renderer.openPage(i);
                    Bitmap bitmap = null;
                    try {
                        float scale = Math.min(2.0f, 2200.0f / Math.max(page.getWidth(), page.getHeight()));
                        int width = Math.max(1, (int)(page.getWidth() * scale));
                        int height = Math.max(1, (int)(page.getHeight() * scale));
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(android.graphics.Color.WHITE);
                        Matrix renderMatrix = new Matrix();
                        renderMatrix.setScale(scale, scale);
                        page.render(bitmap, null, renderMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        recognized.append("\n--- Сторінка ").append(i + 1).append(" ---\n");
                        recognized.append(recognizeBitmap(bitmap)).append("\n");
                    } finally {
                        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                        page.close();
                    }
                }
                if (renderer.getPageCount() > count) recognized.append("\\nOCR обробив перші ").append(count).append(" сторінок із ").append(renderer.getPageCount()).append(".");
                String rel = relativePath.substring(0, relativePath.length() - 4) + ".txt";
                File root = new File(seriesCatalogManager.getBaseDir(), "Каталог");
                File target = new File(root, "Текст_для_пошуку/" + rel);
                if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)) return "Небезпечний шлях для текстового файлу";
                File parent = target.getParentFile();
                if (!parent.exists() && !parent.mkdirs()) return "Не вдалося створити папку OCR";
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(recognized.toString().getBytes(StandardCharsets.UTF_8));
                }
                return "OCR завершено: " + count + " сторінок. Текст додано до пошуку.";
            } catch (Exception e) { return "Помилка OCR серії: " + e.getMessage(); }
        }
        @JavascriptInterface public int getSeriesPdfPageCount(String relativePath) {
            try {
                File f = seriesCatalogManager.resolve(relativePath);
                if (f == null || !f.isFile()) return -1;
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(pfd)) {
                    return renderer.getPageCount();
                }
            } catch (Exception e) { return -1; }
        }
        @JavascriptInterface public String renderSeriesPdfPage(String relativePath, int pageIndex) {
            try {
                File f = seriesCatalogManager.resolve(relativePath);
                if (f == null || !f.isFile()) return "";
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer renderer = new PdfRenderer(pfd)) {
                    if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) return "";
                    try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                        final int maxWidth = 1500;
                        float scale = Math.min(1.0f, maxWidth / (float) Math.max(1, page.getWidth()));
                        int width = Math.max(1, Math.round(page.getWidth() * scale));
                        int height = Math.max(1, Math.round(page.getHeight() * scale));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        bitmap.recycle();
                        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                    }
                }
            } catch (Exception e) { return ""; }
        }
        @JavascriptInterface public String openSeriesPdf(String relativePath) {
            try {
                File f = seriesCatalogManager.resolve(relativePath);
                if (f == null || !f.isFile()) return "Файл не знайдено";
                Uri uri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".provider", f);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/pdf");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(intent));
                return "OK";
            } catch (Exception e) { return "Не вдалося відкрити PDF: " + e.getMessage(); }
        }
        @JavascriptInterface public String seriesCatalogPath() {
            return seriesCatalogManager.getBaseDir().getAbsolutePath();
        }
        @JavascriptInterface public String saveCredential(String email,String password) {
            try { JSONObject o=new JSONObject();o.put("email",email);o.put("password",password);
                boolean saved=getSharedPreferences("butdetal_secure_v1",MODE_PRIVATE).edit().putString("credential",encryptCredential(o.toString())).commit();return saved?"OK":"Не вдалося записати пароль у пам’ять телефону";
            } catch(Exception e){return "Помилка захищеного збереження: "+e.getMessage();}
        }
        @JavascriptInterface public String getCredential() {
            try {String v=getSharedPreferences("butdetal_secure_v1",MODE_PRIVATE).getString("credential",null);return v==null?"":decryptCredential(v);}
            catch(Exception e){return "";}
        }
        @JavascriptInterface public String clearCredential() {
            boolean cleared=getSharedPreferences("butdetal_secure_v1",MODE_PRIVATE).edit().remove("credential").commit();return cleared?"OK":"Не вдалося видалити збережений пароль";
        }
        @JavascriptInterface public String saveDocument(String name,String mime,String base64) {
            try {
                File root=getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(root==null)root=new File(getFilesDir(),"Documents");
                File dir=new File(root,"BUTDETAL_Експорт");if(!dir.exists()&&!dir.mkdirs())throw new IOException("Не вдалося створити папку експорту");
                File f=new File(dir,safeName(name));try(FileOutputStream out=new FileOutputStream(f)){out.write(Base64.decode(base64,Base64.DEFAULT));}
                return "OK";
            }catch(Exception e){return "Помилка експорту: "+e.getMessage();}
        }
        @JavascriptInterface public String saveImage(String c,String name,String data,String title,String notes,String date) {
            try {
                byte[] raw=decodeData(data);
                String safe=safeName(name);
                File dir=folder(c),f=new File(dir,safe);
                try(FileOutputStream out=new FileOutputStream(f)){out.write(raw);}
                JSONObject meta=new JSONObject();
                meta.put("title",title);meta.put("notes",notes);meta.put("date",date);meta.put("fileName",safe);
                try(FileOutputStream out=new FileOutputStream(new File(dir,safe+".json"))){out.write(meta.toString().getBytes(StandardCharsets.UTF_8));}
                return "OK";
            } catch(Exception e){return "Помилка збереження: "+e.getMessage();}
        }
        @JavascriptInterface public String listImages(String c) {
            JSONArray arr=new JSONArray(); File[] files=folder(c).listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
            if(files==null)return arr.toString();
            Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            for(File f:files)try{
                File mf=new File(f.getParentFile(),f.getName()+".json");
                JSONObject o=mf.exists()?new JSONObject(read(mf)):new JSONObject();
                o.put("fileName",f.getName());
                o.put("dataUrl","data:image/jpeg;base64,"+Base64.encodeToString(bytes(f),Base64.NO_WRAP));
                arr.put(o);
            }catch(Exception ignored){}
            return arr.toString();
        }
        @JavascriptInterface public String deleteImage(String c,String n) {
            try{
                String safe=safeName(n);File dir=folder(c),f=new File(dir,safe),m=new File(dir,safe+".json");
                if(f.exists()&&!f.delete())return "Не вдалося видалити";
                if(m.exists())m.delete();return "OK";
            }catch(Exception e){return "Помилка: "+e.getMessage();}
        }
        @JavascriptInterface public String recognizeImage(String data,String languages) {
            Bitmap bitmap=null;
            try {
                byte[] raw=decodeData(data);
                bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length);
                if(bitmap==null)return "Помилка OCR: не вдалося декодувати зображення.";
                String text=recognizeBitmap(bitmap);
                return text.isEmpty()?"Текст не знайдено. Перевір якість та контрастність фото.":text;
            } catch(UnsatisfiedLinkError e){return "OCR не запустився: не завантажилася нативна бібліотека Tesseract ("+e.getMessage()+").";}
              catch(Exception e){return "Помилка OCR: "+e.getMessage();}
            finally {if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();}
        }
        @JavascriptInterface public String recognizePdf(String data,String languages) {
            File tmp=null; ParcelFileDescriptor pfd=null; PdfRenderer renderer=null;
            StringBuilder result=new StringBuilder();
            try {
                byte[] raw=decodeData(data);
                tmp=File.createTempFile("butdetal_ocr_",".pdf",getCacheDir());
                try(FileOutputStream out=new FileOutputStream(tmp)){out.write(raw);}
                pfd=ParcelFileDescriptor.open(tmp,ParcelFileDescriptor.MODE_READ_ONLY);
                renderer=new PdfRenderer(pfd);
                int count=Math.min(renderer.getPageCount(),40);
                if(count==0)return "OCR: PDF не містить сторінок.";
                for(int i=0;i<count;i++){
                    PdfRenderer.Page page=renderer.openPage(i);
                    Bitmap bmp=null;
                    try{
                        float scale=Math.min(2.0f,2200.0f/Math.max(page.getWidth(),page.getHeight()));
                        int w=Math.max(1,(int)(page.getWidth()*scale));
                        int h=Math.max(1,(int)(page.getHeight()*scale));
                        bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(android.graphics.Color.WHITE);
                        page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        String text=recognizeBitmap(bmp);
                        result.append("\\n--- Сторінка ").append(i+1).append(" ---\\n");
                        result.append(text.isEmpty()?"Текст не знайдено на цій сторінці.":text).append("\\n");
                    } finally {
                        if(bmp!=null&&!bmp.isRecycled())bmp.recycle();
                        page.close();
                    }
                }
                if(renderer.getPageCount()>count)result.append("\\nOCR: оброблено перші ").append(count).append(" сторінок із ").append(renderer.getPageCount()).append(".");
                return result.toString().trim();
            } catch(UnsatisfiedLinkError e){return "OCR не запустився: не завантажилася нативна бібліотека Tesseract ("+e.getMessage()+").";}
              catch(Exception e){return "Помилка OCR PDF: "+e.getMessage();}
            finally {
                try{if(renderer!=null)renderer.close();}catch(Exception ignored){}
                try{if(pfd!=null)pfd.close();}catch(Exception ignored){}
                if(tmp!=null)tmp.delete();
            }
        }
        @JavascriptInterface public String savePdf(String name,String data,String title,String notes) {
            try{
                String safe=safeName(name);
                if(!safe.toLowerCase(Locale.ROOT).endsWith(".pdf"))safe+=".pdf";
                File f=new File(pdfFolder(),System.currentTimeMillis()+"_"+safe);
                try(FileOutputStream out=new FileOutputStream(f)){out.write(decodeData(data));}
                JSONObject meta=new JSONObject();meta.put("fileName",f.getName());meta.put("title",title);meta.put("notes",notes);
                meta.put("date",new SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(new Date()));
                try(FileOutputStream out=new FileOutputStream(new File(pdfFolder(),f.getName()+".json"))){out.write(meta.toString().getBytes(StandardCharsets.UTF_8));}
                return "OK";
            }catch(Exception e){return "Помилка збереження PDF: "+e.getMessage();}
        }
        @JavascriptInterface public String listPdfs() {
            JSONArray arr=new JSONArray();File[] fs=pdfFolder().listFiles((d,n)->n.toLowerCase(Locale.ROOT).endsWith(".pdf"));
            if(fs==null)return arr.toString();Arrays.sort(fs,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            for(File f:fs)try{
                File mf=new File(pdfFolder(),f.getName()+".json");JSONObject o=mf.exists()?new JSONObject(read(mf)):new JSONObject();
                o.put("fileName",f.getName());o.put("size",f.length());arr.put(o);
            }catch(Exception ignored){}
            return arr.toString();
        }
        @JavascriptInterface public String readPdfBase64(String name) {
            try { File f=new File(pdfFolder(),safeName(name)); if(!f.exists())return ""; return Base64.encodeToString(bytes(f),Base64.NO_WRAP); }
            catch(Exception e){ return ""; }
        }
        @JavascriptInterface public String importPdf(String name,String base64,String title,String notes,String date) {
            try {
                String safe=safeName(name); if(!safe.toLowerCase(Locale.ROOT).endsWith(".pdf"))safe+=".pdf";
                File f=new File(pdfFolder(),safe); if(f.exists())return "EXISTS";
                try(FileOutputStream out=new FileOutputStream(f)){out.write(Base64.decode(base64,Base64.DEFAULT));}
                JSONObject meta=new JSONObject();meta.put("fileName",safe);meta.put("title",title);meta.put("notes",notes);meta.put("date",date);
                try(FileOutputStream out=new FileOutputStream(new File(pdfFolder(),safe+".json"))){out.write(meta.toString().getBytes(StandardCharsets.UTF_8));}
                return "OK";
            } catch(Exception e){return "Помилка імпорту PDF: "+e.getMessage();}
        }
        @JavascriptInterface public String deletePdf(String name) {
            try{String n=safeName(name);File f=new File(pdfFolder(),n),m=new File(pdfFolder(),n+".json");
                if(f.exists()&&!f.delete())return "Не вдалося видалити PDF";if(m.exists())m.delete();return "OK";
            }catch(Exception e){return "Помилка: "+e.getMessage();}
        }
        @JavascriptInterface public String openPdf(String name) {
            try{
                File f=new File(pdfFolder(),safeName(name));
                if(!f.exists())return "Файл не знайдено.";
                Uri uri=FileProvider.getUriForFile(MainActivity.this,getPackageName()+".provider",f);
                Intent i=new Intent(Intent.ACTION_VIEW);i.setDataAndType(uri,"application/pdf");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(i);return "OK";
            }catch(Exception e){return "Не вдалося відкрити PDF: "+e.getMessage();}
        }
        @JavascriptInterface public String beginBackup() {
            backupJsonBuilder.setLength(0); nextBackupChunk=0; return "OK";
        }
        @JavascriptInterface public String appendBackupChunk(int index, String chunk) {
            if(index!=nextBackupChunk) return "Помилка порядку частин резервної копії";
            if(chunk==null || chunk.length()>60000) return "Завелика частина резервної копії";
            backupJsonBuilder.append(chunk); nextBackupChunk++; return "OK";
        }
        @JavascriptInterface public String finishBackup() {
            try {
                pendingBackupJson=backupJsonBuilder.length()==0?"{}":backupJsonBuilder.toString();
                new JSONObject(pendingBackupJson);
                Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE,"BUTDETAL-backup-"+new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date())+".zip");
                startActivityForResult(i,BACKUP_EXPORT); return "BACKUP_EXPORT_STARTED";
            } catch(Exception e) { return "Помилка створення резервної копії: "+e.getMessage(); }
        }
        @JavascriptInterface public String exportBackup(String appData) {
            try {
                pendingBackupJson = appData == null ? "{}" : appData;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                i.putExtra(Intent.EXTRA_TITLE, "BUTDETAL-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".zip");
                startActivityForResult(i, BACKUP_EXPORT);
                return "BACKUP_EXPORT_STARTED";
            } catch(Exception e) { return "Помилка створення резервної копії: " + e.getMessage(); }
        }
        @JavascriptInterface public String importBackup() {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/zip");
                startActivityForResult(i, BACKUP_IMPORT);
                return "BACKUP_IMPORT_STARTED";
            } catch(Exception e) { return "Помилка вибору резервної копії: " + e.getMessage(); }
        }
        private void writeBackup(Uri uri, String appData) throws Exception {
            try(OutputStream raw=getContentResolver().openOutputStream(uri); ZipOutputStream zip=new ZipOutputStream(raw)) {
                putZipText(zip, "app-data.json", appData == null ? "{}" : appData);
                addDirectoryToZip(zip, folder("drawings"), "drawings/");
                addDirectoryToZip(zip, folder("products"), "products/");
                addDirectoryToZip(zip, pdfFolder(), "pdf/");
            }
        }
        private void putZipText(ZipOutputStream zip, String name, String value) throws Exception {
            zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        private void addDirectoryToZip(ZipOutputStream zip, File dir, String prefix) throws Exception {
            File[] files=dir.listFiles(); if(files==null)return;
            for(File f:files) if(f.isFile() && !f.getName().contains("..")) {
                zip.putNextEntry(new ZipEntry(prefix + safeName(f.getName())));
                try(InputStream in=new FileInputStream(f)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)zip.write(b,0,n);}
                zip.closeEntry();
            }
        }
        private JSONObject readBackup(Uri uri) throws Exception {
            JSONObject appData=new JSONObject(); boolean found=false;
            try(InputStream raw=getContentResolver().openInputStream(uri); ZipInputStream zip=new ZipInputStream(raw)) {
                ZipEntry e; byte[] buf=new byte[8192];
                while((e=zip.getNextEntry())!=null) {
                    String name=e.getName();
                    if(e.isDirectory() || name.startsWith("/") || name.contains("..") || name.contains("\\")) { zip.closeEntry(); continue; }
                    if(name.equals("app-data.json")) {
                        ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=zip.read(buf))>0)out.write(buf,0,n);
                        appData=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8)); found=true;
                    } else {
                        File target=null; String fileName=null;
                        if(name.startsWith("drawings/")){target=folder("drawings");fileName=name.substring(9);}
                        else if(name.startsWith("products/")){target=folder("products");fileName=name.substring(9);}
                        else if(name.startsWith("pdf/")){target=pdfFolder();fileName=name.substring(4);}
                        if(target!=null && fileName!=null && !fileName.isEmpty() && fileName.equals(safeName(fileName))) {
                            File outFile=new File(target,fileName);
                            try(FileOutputStream out=new FileOutputStream(outFile)){int n;while((n=zip.read(buf))>0)out.write(buf,0,n);}
                        }
                    }
                    zip.closeEntry();
                }
            }
            if(!found) throw new IOException("У ZIP немає даних BUTDETAL або файл пошкоджений");
            return appData;
        }

        @JavascriptInterface public String getAppVersion() {
            try { PackageInfo p=getPackageManager().getPackageInfo(getPackageName(),0); return p.versionName==null?"0":p.versionName; }
            catch(Exception e){ return "0"; }
        }
        @JavascriptInterface public String downloadAndInstall(String url) {
            try {
                if(url==null || !url.startsWith("https://github.com/") || !url.toLowerCase(Locale.ROOT).endsWith(".apk")) return "Неприпустиме посилання на APK оновлення.";
                if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
                    getSharedPreferences("butdetal_update_state",MODE_PRIVATE).edit().putString("pending_url",url).apply();
                    Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));
                    settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settings);
                    return "ДОЗВІЛ_ПОТРІБЕН";
                }
                if(downloadManager==null) downloadManager=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                File target=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"BUTDETAL-update.apk");
                if(target.exists() && !target.delete()) throw new IOException("Не вдалося очистити попередній APK");
                DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
                request.setTitle("Оновлення BUTDETAL");
                request.setDescription("Завантаження APK. Після завершення відкриється встановлення.");
                request.setMimeType("application/vnd.android.package-archive");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setAllowedOverMetered(true); request.setAllowedOverRoaming(false);
                request.setDestinationInExternalFilesDir(MainActivity.this,Environment.DIRECTORY_DOWNLOADS,"BUTDETAL-update.apk");
                updateDownloadId=downloadManager.enqueue(request);
                getSharedPreferences("butdetal_update_state",MODE_PRIVATE).edit().putLong("download_id",updateDownloadId).remove("pending_url").apply();
                return "ЗАВАНТАЖЕННЯ_ПОЧАТО";
            } catch(Exception e) { return "Помилка оновлення: "+e.getMessage(); }
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        webView=new WebView(this);webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onShowFileChooser(WebView v,ValueCallback<Uri[]> cb,FileChooserParams p){
                if(callback!=null)callback.onReceiveValue(null);callback=cb;
                try{startActivityForResult(p.createIntent(),PICK);}
                catch(Exception e){callback=null;cb.onReceiveValue(null);return false;}return true;
            }
        });
        WebSettings s=webView.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);s.setAllowContentAccess(true);
        seriesCatalogManager = new SeriesCatalogManager(this);
        androidStorage=new AndroidStorage();
        webView.addJavascriptInterface(androidStorage,"AndroidStorage");
        webView.setBackgroundColor(0xFF0B1420);setContentView(webView);
        folder("drawings");folder("products");pdfFolder();
        downloadManager=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
        updateDownloadId=getSharedPreferences("butdetal_update_state",MODE_PRIVATE).getLong("download_id",-1);
        updateReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long id=intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1);
                if(id!=updateDownloadId) return;
                DownloadManager.Query q=new DownloadManager.Query().setFilterById(id);
                try(android.database.Cursor c=downloadManager.query(q)){
                    if(c!=null && c.moveToFirst()){
                        int status=c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        if(status==DownloadManager.STATUS_SUCCESSFUL) installDownloadedUpdate();
                        else if(webView!=null) webView.evaluateJavascript("window.onNativeUpdateResult&&window.onNativeUpdateResult('Помилка завантаження APK')",null);
                    }
                }catch(Exception e){ if(webView!=null)webView.evaluateJavascript("window.onNativeUpdateResult&&window.onNativeUpdateResult('Помилка перевірки завантаження')",null); }
            }
        };
        IntentFilter filter=new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if(Build.VERSION.SDK_INT>=33) registerReceiver(updateReceiver,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(updateReceiver,filter);
        webView.loadUrl("file:///android_asset/index.html");
    }
    private void installDownloadedUpdate(){
        try{
            File f=new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),"BUTDETAL-update.apk");
            if(!f.exists() || f.length()<100000) throw new IOException("Завантажений APK відсутній або пошкоджений");
            PackageInfo apkInfo=getPackageManager().getPackageArchiveInfo(f.getAbsolutePath(),0);
            if(apkInfo==null || !getPackageName().equals(apkInfo.packageName)) throw new IOException("APK не належить BUTDETAL або файл пошкоджений");
            getSharedPreferences("butdetal_update_state",MODE_PRIVATE).edit().remove("download_id").apply();
            updateDownloadId=-1;
            Uri uri=FileProvider.getUriForFile(this,getPackageName()+".provider",f);
            Intent i=new Intent(Intent.ACTION_VIEW); i.setDataAndType(uri,"application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i);
        }catch(Exception e){ if(webView!=null)webView.evaluateJavascript("window.onNativeUpdateResult&&window.onNativeUpdateResult("+JSONObject.quote("Не вдалося відкрити інсталятор: "+e.getMessage())+")",null); }
    }
    @Override protected void onResume() {
        super.onResume();
        if(Build.VERSION.SDK_INT>=26 && androidStorage!=null && getPackageManager().canRequestPackageInstalls()) {
            android.content.SharedPreferences prefs=getSharedPreferences("butdetal_update_state",MODE_PRIVATE);
            String pending=prefs.getString("pending_url",null);
            if(pending!=null && !pending.isEmpty()) {
                prefs.edit().remove("pending_url").apply();
                String result=androidStorage.downloadAndInstall(pending);
                if(webView!=null) webView.post(() -> webView.evaluateJavascript("window.onNativeUpdateResult&&window.onNativeUpdateResult("+JSONObject.quote(result.equals("ЗАВАНТАЖЕННЯ_ПОЧАТО")?"Дозвіл надано. Завантажую оновлення…":result)+")",null));
            }
        }
    }
    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(updateReceiver!=null){try{unregisterReceiver(updateReceiver);}catch(Exception ignored){}}
        super.onDestroy();
    }
    @Override protected void onActivityResult(int r,int c,Intent d) {
        super.onActivityResult(r,c,d);
        if(r==BACKUP_EXPORT) {
            if(c==RESULT_OK && d!=null && d.getData()!=null) {
                try { androidStorage.writeBackup(d.getData(), pendingBackupJson); notifyBackup("Експорт резервної копії завершено.", true); }
                catch(Exception e) { notifyBackup("Помилка створення копії: "+e.getMessage(), false); }
            } else notifyBackup("Створення резервної копії скасовано.", false);
            return;
        }
        if(r==BACKUP_IMPORT) {
            if(c==RESULT_OK && d!=null && d.getData()!=null) {
                try { JSONObject data=androidStorage.readBackup(d.getData()); notifyRestore(data); }
                catch(Exception e) { notifyBackup("Не вдалося відновити копію: "+e.getMessage(), false); }
            } else notifyBackup("Відновлення скасовано.", false);
            return;
        }
        if (r == SERIES_IMPORT) {
            if (c == RESULT_OK && d != null && d.getData() != null) {
                final Uri archiveUri = d.getData();
                try { getContentResolver().takePersistableUriPermission(archiveUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                new Thread(() -> {
                    JSONObject result;
                    try { result = seriesCatalogManager.importZip(archiveUri); }
                    catch (Exception e) {
                        result = new JSONObject();
                        try { result.put("ok", false); result.put("message", "Помилка імпорту: " + e.getMessage()); } catch (Exception ignored) {}
                    }
                    notifySeriesImport(result);
                }).start();
            } else {
                JSONObject result = new JSONObject();
                try { result.put("ok", false); result.put("message", "Імпорт скасовано"); } catch (Exception ignored) {}
                notifySeriesImport(result);
            }
            return;
        }
        if(r==PICK&&callback!=null){
            Uri[] u=null;
            if(c==RESULT_OK&&d!=null){
                if(d.getClipData()!=null){ClipData clip=d.getClipData();u=new Uri[clip.getItemCount()];
                    for(int i=0;i<clip.getItemCount();i++)u[i]=clip.getItemAt(i).getUri();}
                else if(d.getData()!=null)u=new Uri[]{d.getData()};
            }
            callback.onReceiveValue(u);callback=null;
        }
    }
    private void notifyBackup(String message, boolean success) {
        if(webView!=null) webView.post(()->webView.evaluateJavascript("window.onNativeBackupResult&&window.onNativeBackupResult("+JSONObject.quote(message)+","+success+")",null));
    }
    private void notifyRestore(JSONObject data) {
        if(webView!=null) webView.post(()->webView.evaluateJavascript("window.onNativeBackupRestore&&window.onNativeBackupRestore("+JSONObject.quote(data.toString())+")",null));
    }
}
