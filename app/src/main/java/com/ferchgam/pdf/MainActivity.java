package com.ferchgam.pdf;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * FerchGam PDF
 * © FerchGam. Todos los derechos reservados.
 * La app muestra la interfaz (assets/index.html) dentro de un WebView
 * y le da funciones del teléfono: elegir archivos y guardar en Descargas.
 */
public class MainActivity extends Activity {

    private static final int ELEGIR_ARCHIVO = 1001;
    private WebView web;
    private ValueCallback<Uri[]> archivoCallback;
    private Uri ultimoUri;
    private String ultimoMime;
    private Uri pdfPendiente;
    private boolean paginaLista = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.addJavascriptInterface(new Puente(), "FGAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                paginaLista = true;
                if (pdfPendiente != null) { enviarPdfALaPagina(pdfPendiente); pdfPendiente = null; }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (archivoCallback != null) archivoCallback.onReceiveValue(null);
                archivoCallback = cb;
                boolean imagen = false;
                for (String a : params.getAcceptTypes()) if (a != null && a.startsWith("image")) imagen = true;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(imagen ? "image/*" : "application/pdf");
                try {
                    startActivityForResult(Intent.createChooser(i, imagen ? "Elegir imagen" : "Elegir PDF"), ELEGIR_ARCHIVO);
                } catch (Exception e) {
                    archivoCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");

        recibirIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        recibirIntent(intent);
    }

    /** Cuando el usuario elige "Abrir con FerchGam PDF" desde otra app. */
    private void recibirIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        if (paginaLista) enviarPdfALaPagina(intent.getData());
        else pdfPendiente = intent.getData();
    }

    private void enviarPdfALaPagina(Uri uri) {
        new Thread(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                final String b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
                final String nombre = nombreDe(uri).replace("\\", "").replace("'", "");
                runOnUiThread(() -> web.evaluateJavascript(
                        "window.FGOpenPdf && window.FGOpenPdf('" + nombre + "','" + b64 + "')", null));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "No se pudo abrir el PDF", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String nombreDe(Uri uri) {
        String nombre = "documento.pdf";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0 && c.getString(i) != null) nombre = c.getString(i);
            }
        } catch (Exception ignored) { }
        return nombre;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ELEGIR_ARCHIVO && archivoCallback != null) {
            Uri[] r = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) r = new Uri[]{data.getData()};
            archivoCallback.onReceiveValue(r);
            archivoCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Funciones que la página puede usar: window.FGAndroid.* */
    private class Puente {
        @JavascriptInterface
        public String saveFile(String nombre, String base64, String mime) {
            try {
                byte[] datos = Base64.decode(base64, Base64.DEFAULT);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FerchGam PDF");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "sin acceso a Descargas";
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null) return "sin acceso a Descargas";
                    os.write(datos);
                }
                ultimoUri = uri;
                ultimoMime = mime;
                return "ok";
            } catch (Exception e) {
                return String.valueOf(e.getMessage());
            }
        }

        @JavascriptInterface
        public void openLast() {
            runOnUiThread(() -> {
                if (ultimoUri == null) return;
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(ultimoUri, ultimoMime);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Abrir con")); }
                catch (Exception e) { Toast.makeText(MainActivity.this, "No hay una app para abrir este archivo", Toast.LENGTH_LONG).show(); }
            });
        }

        @JavascriptInterface
        public void shareLast() {
            runOnUiThread(() -> {
                if (ultimoUri == null) return;
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType(ultimoMime);
                i.putExtra(Intent.EXTRA_STREAM, ultimoUri);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivity(Intent.createChooser(i, "Compartir")); } catch (Exception ignored) { }
            });
        }
    }
}
