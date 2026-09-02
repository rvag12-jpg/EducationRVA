package es.iesvirgendelacaridad.corrector;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FileUtil {
    private static final long MAX_FILE_BYTES = 35L * 1024L * 1024L;

    private FileUtil() {}

    public static String displayName(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "archivo" : p;
    }

    public static long size(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {}
        return -1L;
    }

    public static String mime(Context context, Uri uri) {
        String mime = context.getContentResolver().getType(uri);
        if (mime != null && !mime.trim().isEmpty()) return mime;
        String n = displayName(context, uri).toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".txt")) return "text/plain";
        if (n.endsWith(".md")) return "text/markdown";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    public static JSONObject toJsonFile(Context context, Uri uri) throws IOException, JSONException {
        byte[] bytes = readBytes(context, uri);
        JSONObject obj = new JSONObject();
        obj.put("name", displayName(context, uri));
        obj.put("mime", mime(context, uri));
        obj.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return obj;
    }

    public static byte[] readBytes(Context context, Uri uri) throws IOException {
        long declared = size(context, uri);
        if (declared > MAX_FILE_BYTES) {
            throw new IOException("El archivo " + displayName(context, uri) + " supera 35 MB.");
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream(declared > 0 && declared < Integer.MAX_VALUE ? (int) declared : 65536)) {
            if (in == null) throw new IOException("No se puede abrir " + displayName(context, uri));
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > MAX_FILE_BYTES) throw new IOException("El archivo supera 35 MB.");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static String sha256(Context context, Uri uri) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("No se puede abrir el archivo.");
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
