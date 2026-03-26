package org.MrZ.vscode_webview;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JS ↔ Java 桥接层
 * JS 端通过 AndroidBridge.invoke(callbackId, method, argsJson) 调用
 * Java 端通过 __fsaResolve / __fsaReject 回传结果
 */
public class FileSystemBridge {

    private static final String TAG = "FSBridge";

    private final Activity activity;
    private final WebView webView;

    // 当前等待中的回调 ID（一次只处理一个 picker）
    private String pendingCallbackId;
    private String pendingMethod;

    // Activity 请求码
    public static final int REQUEST_PICK_DIRECTORY = 1001;
    public static final int REQUEST_PICK_FILE = 1002;

    public FileSystemBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void invoke(String callbackId, String method, String argsJson) {
        Log.d(TAG, "invoke: " + method + " cb=" + callbackId + " args=" + argsJson);
        try {
            JSONObject args = new JSONObject(argsJson);

            switch (method) {
                case "pickDirectory":
                    pendingCallbackId = callbackId;
                    pendingMethod = method;
                    Intent dirIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    activity.startActivityForResult(dirIntent, REQUEST_PICK_DIRECTORY);
                    break;

                case "pickFile":
                    pendingCallbackId = callbackId;
                    pendingMethod = method;
                    Intent fileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
                    fileIntent.setType("*/*");
                    activity.startActivityForResult(fileIntent, REQUEST_PICK_FILE);
                    break;

                case "listDirectory":
                    listDirectory(callbackId, args.getString("uri"));
                    break;

                case "readFile":
                    readFile(callbackId, args.getString("uri"));
                    break;

                case "getChildEntry":
                    getChildEntry(callbackId, args.getString("uri"),
                            args.getString("name"), args.getString("type"));
                    break;

                case "writeFile":
                    writeFile(callbackId, args.getString("uri"), args.getString("data"));
                    break;

                case "createFile":
                    createEntry(callbackId, args.getString("uri"), args.getString("name"),
                            args.optString("mimeType", "application/octet-stream"));
                    break;

                case "createDirectory":
                    createEntry(callbackId, args.getString("uri"), args.getString("name"),
                            DocumentsContract.Document.MIME_TYPE_DIR);
                    break;

                case "deleteEntry":
                    deleteEntry(callbackId, args.getString("uri"));
                    break;

                default:
                    reject(callbackId, "Unknown method: " + method);
            }
        } catch (Exception e) {
            reject(callbackId, e.getMessage());
        }
    }

    /**
     * 文件/文件夹选择器返回后由 Activity 调用
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (pendingCallbackId == null) return;

        String cbId = pendingCallbackId;
        pendingCallbackId = null;

        if (resultCode != Activity.RESULT_OK || data == null) {
            reject(cbId, "User cancelled");
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            reject(cbId, "No URI returned");
            return;
        }

        try {
            // 获取持久化权限（应用重启后仍可访问）
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            activity.getContentResolver().takePersistableUriPermission(uri, flags);

            if (requestCode == REQUEST_PICK_DIRECTORY) {
                // ACTION_OPEN_DOCUMENT_TREE 返回的是 tree URI
                Uri treeUri = DocumentsContract.buildDocumentUriUsingTree(uri,
                        DocumentsContract.getTreeDocumentId(uri));
                String name = getDocumentName(treeUri);
                JSONObject result = new JSONObject();
                result.put("name", name);
                result.put("uri", uri.toString()); // 保留原始 tree URI
                resolve(cbId, result.toString());

            } else if (requestCode == REQUEST_PICK_FILE) {
                String name = getDocumentName(uri);
                JSONObject fileObj = new JSONObject();
                fileObj.put("name", name);
                fileObj.put("uri", uri.toString());
                JSONObject result = new JSONObject();
                JSONArray files = new JSONArray();
                files.put(fileObj);
                result.put("files", files);
                resolve(cbId, result.toString());
            }
        } catch (Exception e) {
            reject(cbId, e.getMessage());
        }
    }

    /**
     * 列出目录中的文件和子目录
     */
    private void listDirectory(final String callbackId, final String uriStr) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri treeUri = Uri.parse(uriStr);
                    // 提取原始 tree URI（去掉 /document/... 后缀）
                    String baseTreeUri = extractBaseTreeUri(uriStr);
                    Uri baseTree = Uri.parse(baseTreeUri);

                    // 获取当前目录的 document ID
                    String parentDocId;
                    if (uriStr.contains("/document/")) {
                        parentDocId = DocumentsContract.getDocumentId(treeUri);
                    } else {
                        parentDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    }
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(baseTree, parentDocId);

                    JSONArray entries = new JSONArray();
                    Cursor cursor = activity.getContentResolver().query(childrenUri,
                            new String[]{
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                            }, null, null, null);

                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            String name = cursor.getString(0);
                            String mimeType = cursor.getString(1);
                            String docId = cursor.getString(2);

                            boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);
                            Uri childUri = DocumentsContract.buildDocumentUriUsingTree(baseTree, docId);

                            JSONObject entry = new JSONObject();
                            entry.put("name", name);
                            entry.put("type", isDir ? "directory" : "file");
                            // 目录 URI 基于原始 tree URI 拼接，确保只有一个 /document/ 段
                            entry.put("uri", isDir ?
                                    baseTreeUri + "/document/" + Uri.encode(docId)
                                    : childUri.toString());
                            entries.put(entry);
                        }
                        cursor.close();
                    }

                    JSONObject result = new JSONObject();
                    result.put("entries", entries);
                    resolve(callbackId, result.toString());

                } catch (Exception e) {
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 读取文件内容，以 base64 返回
     */
    private void readFile(final String callbackId, final String uriStr) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = Uri.parse(uriStr);
                    InputStream is = activity.getContentResolver().openInputStream(uri);
                    if (is == null) {
                        reject(callbackId, "Cannot open file");
                        return;
                    }

                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int len;
                    while ((len = is.read(chunk)) != -1) {
                        buffer.write(chunk, 0, len);
                    }
                    is.close();

                    String base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
                    String name = getDocumentName(uri);
                    String mimeType = activity.getContentResolver().getType(uri);

                    JSONObject result = new JSONObject();
                    result.put("data", base64);
                    result.put("name", name);
                    result.put("mimeType", mimeType != null ? mimeType : "application/octet-stream");
                    result.put("size", buffer.size());
                    result.put("lastModified", System.currentTimeMillis());
                    resolve(callbackId, result.toString());

                } catch (Exception e) {
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 获取目录下指定名称的子条目
     */
    private void getChildEntry(final String callbackId, final String parentUriStr,
                               final String name, final String type) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri treeUri = Uri.parse(parentUriStr);
                    String baseTreeUri = extractBaseTreeUri(parentUriStr);
                    Uri baseTree = Uri.parse(baseTreeUri);

                    String parentDocId;
                    if (parentUriStr.contains("/document/")) {
                        parentDocId = DocumentsContract.getDocumentId(treeUri);
                    } else {
                        parentDocId = DocumentsContract.getTreeDocumentId(treeUri);
                    }
                    Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(baseTree, parentDocId);

                    Cursor cursor = activity.getContentResolver().query(childrenUri,
                            new String[]{
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                            }, null, null, null);

                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            String entryName = cursor.getString(0);
                            String mimeType = cursor.getString(1);
                            String docId = cursor.getString(2);

                            if (entryName.equals(name)) {
                                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);

                                // 类型校验：如果调用方指定了 file 或 directory，检查是否匹配
                                if ("file".equals(type) && isDir) {
                                    cursor.close();
                                    JSONObject error = new JSONObject();
                                    error.put("error", name + " is a directory, not a file");
                                    resolve(callbackId, error.toString());
                                    return;
                                }
                                if ("directory".equals(type) && !isDir) {
                                    cursor.close();
                                    JSONObject error = new JSONObject();
                                    error.put("error", name + " is a file, not a directory");
                                    resolve(callbackId, error.toString());
                                    return;
                                }

                                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(baseTree, docId);

                                JSONObject result = new JSONObject();
                                result.put("name", entryName);
                                result.put("uri", isDir ?
                                        baseTreeUri + "/document/" + Uri.encode(docId)
                                        : childUri.toString());
                                cursor.close();
                                resolve(callbackId, result.toString());
                                return;
                            }
                        }
                        cursor.close();
                    }

                    JSONObject error = new JSONObject();
                    error.put("error", "Not found: " + name);
                    resolve(callbackId, error.toString());

                } catch (Exception e) {
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 写入文件内容（base64 编码数据）
     */
    private void writeFile(final String callbackId, final String uriStr, final String base64Data) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = Uri.parse(uriStr);
                    byte[] bytes = Base64.decode(base64Data, Base64.DEFAULT);
                    Log.d(TAG, "writeFile: uri=" + uriStr + " size=" + bytes.length);

                    // "wt" = write + truncate（覆盖原有内容）
                    OutputStream os = activity.getContentResolver().openOutputStream(uri, "wt");
                    if (os == null) {
                        reject(callbackId, "Cannot open file for writing");
                        return;
                    }

                    os.write(bytes);
                    os.flush();
                    os.close();

                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    result.put("bytesWritten", bytes.length);
                    resolve(callbackId, result.toString());

                } catch (Exception e) {
                    Log.e(TAG, "writeFile error: " + e.getMessage());
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 在目录下创建文件或子目录
     * mimeType 为 MIME_TYPE_DIR 时创建目录，否则创建文件
     */
    private void createEntry(final String callbackId, final String parentUriStr,
                             final String name, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri parentUri = Uri.parse(parentUriStr);
                    String baseTreeUri = extractBaseTreeUri(parentUriStr);
                    Uri baseTree = Uri.parse(baseTreeUri);

                    String parentDocId;
                    if (parentUriStr.contains("/document/")) {
                        parentDocId = DocumentsContract.getDocumentId(parentUri);
                    } else {
                        parentDocId = DocumentsContract.getTreeDocumentId(parentUri);
                    }
                    Uri parentDocUri = DocumentsContract.buildDocumentUriUsingTree(baseTree, parentDocId);
                    Log.d(TAG, "createEntry: parent=" + parentUriStr + " name=" + name + " mime=" + mimeType);

                    Uri newUri = DocumentsContract.createDocument(
                            activity.getContentResolver(), parentDocUri, mimeType, name);

                    if (newUri == null) {
                        reject(callbackId, "Failed to create: " + name);
                        return;
                    }

                    String newDocId = DocumentsContract.getDocumentId(newUri);
                    boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType);

                    JSONObject result = new JSONObject();
                    result.put("name", name);
                    result.put("uri", isDir ?
                            baseTreeUri + "/document/" + Uri.encode(newDocId)
                            : DocumentsContract.buildDocumentUriUsingTree(baseTree, newDocId).toString());
                    resolve(callbackId, result.toString());

                } catch (Exception e) {
                    Log.e(TAG, "createEntry error: " + e.getMessage());
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 删除文件或目录
     */
    private void deleteEntry(final String callbackId, final String uriStr) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = Uri.parse(uriStr);
                    Log.d(TAG, "deleteEntry: uri=" + uriStr);

                    boolean deleted = DocumentsContract.deleteDocument(
                            activity.getContentResolver(), uri);

                    if (!deleted) {
                        reject(callbackId, "Failed to delete");
                        return;
                    }

                    JSONObject result = new JSONObject();
                    result.put("ok", true);
                    resolve(callbackId, result.toString());

                } catch (Exception e) {
                    Log.e(TAG, "deleteEntry error: " + e.getMessage());
                    reject(callbackId, e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 从 URI 中提取原始 tree URI（去掉 /document/... 后缀）
     * 例如 "content://...documents/tree/primary%3AFolder/document/primary%3AFolder%2Fsub"
     * → "content://...documents/tree/primary%3AFolder"
     */
    private String extractBaseTreeUri(String uriStr) {
        int docIdx = uriStr.indexOf("/document/");
        if (docIdx > 0) {
            return uriStr.substring(0, docIdx);
        }
        return uriStr;
    }

    /**
     * 从 URI 获取文档显示名称
     */
    private String getDocumentName(Uri uri) {
        String name = "unknown";
        try {
            Cursor cursor = activity.getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
                cursor.close();
            }
        } catch (Exception e) {
            // 回退：从 URI 路径中提取
            String path = uri.getLastPathSegment();
            if (path != null) {
                int slash = path.lastIndexOf('/');
                name = slash >= 0 ? path.substring(slash + 1) : path;
                int colon = name.lastIndexOf(':');
                if (colon >= 0) name = name.substring(colon + 1);
            }
        }
        return name;
    }

    // --- JS 回调工具方法 ---

    private void resolve(final String callbackId, final String json) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String js = "window.__fsaResolve('" + callbackId + "','" +
                        escapeForJs(json) + "')";
                Log.d(TAG, "resolve(" + callbackId + "): " + json);
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private void reject(final String callbackId, final String error) {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String js = "window.__fsaReject('" + callbackId + "','" +
                        escapeForJs(error) + "')";
                Log.d(TAG, "reject(" + callbackId + "): " + error);
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private String escapeForJs(String s) {
        if (s == null) return "''";
        return s.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
