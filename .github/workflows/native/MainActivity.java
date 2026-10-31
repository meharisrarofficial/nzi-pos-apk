package com.nzi.pos;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.DocumentsContract;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends BridgeActivity {
  private static final int REQ_FOLDER = 7421;
  private static final String FILE = "NZI_Live_Recovery.json";
  private static final String TMP = "NZI_Live_Recovery.json.tmp";
  private Uri treeUri = null;
  private OutputStream out = null;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    SharedPreferences sp = getSharedPreferences("nzi", MODE_PRIVATE);
    String s = sp.getString("tree", null);
    if (s != null) treeUri = Uri.parse(s);
    getBridge().getWebView().addJavascriptInterface(new NZIBridge(), "NZIStorage");
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == REQ_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
      try {
        Uri u = data.getData();
        getContentResolver().takePersistableUriPermission(u,
          Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        treeUri = u;
        getSharedPreferences("nzi", MODE_PRIVATE).edit().putString("tree", u.toString()).apply();
        getBridge().getWebView().post(() -> getBridge().getWebView()
          .evaluateJavascript("try{nziRcNotify();}catch(e){}", null));
      } catch (Exception e) { }
    }
  }

  private Uri parentDoc() {
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
  }

  private Uri findChild(String name) {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
    Cursor c = null;
    try {
      c = getContentResolver().query(children, new String[]{
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
      while (c != null && c.moveToNext()) {
        if (name.equals(c.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
      }
    } catch (Exception e) {
    } finally { if (c != null) c.close(); }
    return null;
  }

  private void deleteIfExists(String name) {
    try {
      Uri u = findChild(name);
      if (u != null) DocumentsContract.deleteDocument(getContentResolver(), u);
    } catch (Exception e) { }
  }

  public class NZIBridge {
    @JavascriptInterface
    public boolean chooseRecoveryFolder() {
      runOnUiThread(() -> {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
      });
      return true;
    }

    @JavascriptInterface
    public synchronized boolean beginRecoveryWrite() {
      try {
        if (treeUri == null) return false;
        if (out != null) { try { out.close(); } catch (Exception e) { } out = null; }
        deleteIfExists(TMP);
        Uri t = DocumentsContract.createDocument(getContentResolver(), parentDoc(), "application/octet-stream", TMP);
        if (t == null) return false;
        out = new BufferedOutputStream(getContentResolver().openOutputStream(t, "w"), 1 << 16);
        return true;
      } catch (Exception e) { out = null; return false; }
    }

    @JavascriptInterface
    public synchronized boolean appendRecoveryChunk(String s) {
      try {
        if (out == null) return false;
        out.write(s.getBytes("UTF-8"));
        return true;
      } catch (Exception e) { return false; }
    }

    @JavascriptInterface
    public synchronized boolean endRecoveryWrite() {
      try {
        if (out == null) return false;
        out.flush(); out.close(); out = null;
        deleteIfExists(FILE);
        Uri t = findChild(TMP);
        if (t == null) return false;
        Uri r = DocumentsContract.renameDocument(getContentResolver(), t, FILE);
        return r != null;
      } catch (Exception e) { out = null; return false; }
    }

    @JavascriptInterface
    public synchronized void abortRecoveryWrite() {
      try { if (out != null) out.close(); } catch (Exception e) { }
      out = null;
      if (treeUri != null) deleteIfExists(TMP);
    }

    @JavascriptInterface
    public boolean writeRecoveryFile(String json) {
      return beginRecoveryWrite() && appendRecoveryChunk(json) && endRecoveryWrite();
    }

    @JavascriptInterface
    public String readRecoveryFile() {
      try {
        if (treeUri == null) return "";
        Uri u = findChild(FILE);
        if (u == null) return "";
        InputStream in = getContentResolver().openInputStream(u);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[1 << 16]; int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
      } catch (Exception e) { return ""; }
    }

    @JavascriptInterface
    public void printPage() {
      runOnUiThread(() -> {
        try {
          WebView w = getBridge().getWebView();
          PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
          pm.print("NZI Document", w.createPrintDocumentAdapter("NZI Document"), new PrintAttributes.Builder().build());
        } catch (Exception e) { }
      });
    }
  }
}
