package com.skilla.valman;

import android.os.Handler;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Thin client for a private ValMan/Skilla Bot backend.
 * The API key must live on the server, never in the APK.
 */
public final class AiGateway {
    private AiGateway() {}

    public interface Callback {
        void onSuccess(String answer, String source);
        void onError(String message);
    }

    public static void ask(final String endpoint, final JSONObject payload, final Handler main, final Callback callback) {
        ask(endpoint,payload,"",main,callback);
    }

    public static void ask(final String endpoint, final JSONObject payload, final String token, final Handler main, final Callback callback) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL(endpoint);
                c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Accept", "application/json");
                if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }

                int code = c.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String raw = readAll(stream);
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + (raw.isEmpty() ? "" : ": " + raw));

                JSONObject out = new JSONObject(raw);
                String answer = out.optString("answer", "").trim();
                String source = out.optString("source", "Skilla AI").trim();
                if (answer.isEmpty()) throw new Exception("Risposta AI vuota");
                final String a = answer;
                final String s = source.isEmpty() ? "Skilla AI" : source;
                main.post(() -> callback.onSuccess(a, s));
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? "Connessione AI non disponibile" : e.getMessage();
                main.post(() -> callback.onError(msg));
            } finally {
                if (c != null) c.disconnect();
            }
        }, "SkillaAiGateway").start();
    }


    public interface AudioCallback {
        void onSuccess(File audioFile);
        void onError(String message);
    }

    public static void synthesize(final String endpoint, final String text, final File cacheDir, final Handler main, final AudioCallback callback) {
        synthesize(endpoint,text,cacheDir,"",main,callback);
    }

    public static void synthesize(final String endpoint, final String text, final File cacheDir, final String token, final Handler main, final AudioCallback callback) {
        new Thread(() -> {
            HttpURLConnection c = null;
            File out = null;
            try {
                URL url = new URL(endpoint);
                c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(12000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setRequestProperty("Accept", "audio/mpeg");
                if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                JSONObject p = new JSONObject(); p.put("text", text);
                byte[] body = p.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("TTS HTTP " + code);
                out = File.createTempFile("skilla_voice_", ".mp3", cacheDir);
                try (InputStream in = c.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                final File f = out; main.post(() -> callback.onSuccess(f));
            } catch (Exception e) {
                if (out != null) try { out.delete(); } catch (Exception ignored) {}
                final String msg = e.getMessage() == null ? "Voce neurale non disponibile" : e.getMessage();
                main.post(() -> callback.onError(msg));
            } finally { if (c != null) c.disconnect(); }
        }, "SkillaNeuralTts").start();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }
}
