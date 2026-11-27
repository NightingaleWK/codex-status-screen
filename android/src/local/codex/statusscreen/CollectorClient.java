package local.codex.statusscreen;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class CollectorClient {
    static String endpoint(String input) throws Exception {
        String address = input.trim();
        if (!address.contains("://")) address = "https://" + address;
        URI uri = new URI(address);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null ||
                !(uri.getPath().isEmpty() || "/".equals(uri.getPath())) ||
                (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IOException("设备地址应为 HTTPS 主机地址");
        }
        int port = uri.getPort() == -1 ? 8767 : uri.getPort();
        return new URI("https", null, uri.getHost(), port, null, null, null).toString();
    }

    static HostPair pair(String address, String code, String fingerprint) throws Exception {
        if (!code.matches("[0-9]{8}")) throw new IOException("请输入电脑上显示的 8 位配对码");
        String endpoint = endpoint(address);
        String pin = fingerprint.replaceAll("[ :\\-]", "").toLowerCase(Locale.ROOT);
        if (!pin.matches("(?:[a-f0-9]{16}|[a-f0-9]{64})")) {
            throw new IOException("请输入电脑配对页上的校验指纹");
        }
        PinnedConnection result = request(endpoint, pin, "/v1/pair", null, new JSONObject().put("code", code));
        JSONObject device = result.data.getJSONObject("device");
        String token = result.data.getString("token");
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw new IOException("采集器返回了无效的配对凭据");
        return new HostPair(device.getString("id"), device.getString("name"), endpoint, result.pin, token);
    }

    static JSONObject status(String endpoint, String pin, String token, String expectedId) throws Exception {
        JSONObject data = request(endpoint, pin, "/v1/status", token, null).data;
        if (!expectedId.equals(data.getJSONObject("device").getString("id"))) {
            throw new IOException("设备身份已变更，请重新配对");
        }
        return data;
    }

    private static PinnedConnection request(String endpoint, String pin, String path,
                                            String token, JSONObject body) throws Exception {
        PinnedTrust trust = new PinnedTrust(pin);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {trust}, null);
        HttpsURLConnection connection = (HttpsURLConnection) new URI(endpoint + path).toURL().openConnection(Proxy.NO_PROXY);
        connection.setSSLSocketFactory(context.getSocketFactory());
        // The fingerprint authenticates the certificate when a LAN IP or DNS-SD address changes.
        connection.setHostnameVerifier((name, session) -> trust.observedPin != null);
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(4000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "close");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(data.length);
                try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(data); }
            }
            int status = connection.getResponseCode();
            JSONObject result = readJson(status == 200 ? connection.getInputStream() : connection.getErrorStream());
            if (status != 200) throw new IOException(result.optString("error", "采集器连接失败"));
            if (result.optInt("version") != 1 || trust.observedPin == null) throw new IOException("设备协议版本不匹配");
            return new PinnedConnection(result, trust.observedPin);
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject readJson(InputStream input) throws Exception {
        if (input == null) return new JSONObject();
        try (InputStream stream = input; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int size;
            while ((size = stream.read(block)) != -1) {
                buffer.write(block, 0, size);
                if (buffer.size() > 1024 * 1024) throw new IOException("采集器数据过大");
            }
            return new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    static final class HostPair {
        final String id, name, endpoint, pin, token;
        HostPair(String id, String name, String endpoint, String pin, String token) {
            this.id = id;
            this.name = name;
            this.endpoint = endpoint;
            this.pin = pin;
            this.token = token;
        }
    }

    private static final class PinnedConnection {
        final JSONObject data;
        final String pin;
        PinnedConnection(JSONObject data, String pin) { this.data = data; this.pin = pin; }
    }

    private static final class PinnedTrust implements X509TrustManager {
        private final String expectedPin;
        String observedPin;
        PinnedTrust(String expectedPin) { this.expectedPin = expectedPin; }
        public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
            throw new CertificateException("Unexpected client certificate");
        }
        public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
            try {
                if (chain == null || chain.length == 0) throw new CertificateException("No certificate");
                chain[0].checkValidity();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                StringBuilder hex = new StringBuilder(64);
                for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
                String actual = hex.toString();
                if (!actual.startsWith(expectedPin)) throw new CertificateException("校验指纹不匹配");
                observedPin = actual;
            } catch (CertificateException error) {
                throw error;
            } catch (Exception error) {
                throw new CertificateException(error);
            }
        }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
