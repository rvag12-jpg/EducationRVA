package es.iesvirgendelacaridad.corrector;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class ApiClient {
    private ApiClient() {}

    public static JSONObject post(String endpoint, String token, JSONObject payload) throws IOException, JSONException {
        URL url = new URL(endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(30_000);
        c.setReadTimeout(600_000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Accept", "application/json");
        if (token != null && !token.trim().isEmpty()) c.setRequestProperty("X-Corrector-Token", token.trim());

        byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream out = c.getOutputStream()) {
            out.write(data);
        }

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();

        JSONObject response;
        try {
            response = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (JSONException e) {
            throw new IOException("Respuesta no válida del servidor (HTTP " + code + ").");
        }
        if (code < 200 || code >= 300 || !response.optBoolean("ok", false)) {
            throw new IOException(response.optString("error", "Error HTTP " + code));
        }
        JSONObject result = response.optJSONObject("result");
        if (result == null) throw new IOException("El servidor no devolvió un resultado.");
        return result;
    }

    public static JSONObject getHealth(String endpoint, String token) throws IOException, JSONException {
        URL url = new URL(endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(8_000);
        c.setReadTimeout(8_000);
        c.setRequestProperty("Accept", "application/json");
        if (token != null && !token.trim().isEmpty()) c.setRequestProperty("X-Corrector-Token", token.trim());
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();
        if (code < 200 || code >= 300) throw new IOException("Servidor no accesible: HTTP " + code);
        return new JSONObject(body);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
