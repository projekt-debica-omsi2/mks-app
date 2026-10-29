package pl.debica.mks.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Natywny transport danych MKS: działa poza WebView, więc nie wymaga CORS. */
public class MainActivity extends Activity {
    private static final String DIRECT_API = "http://rj.mks.debica.pl/AndroidService/SchedulesService.svc/";
    private static final int LOCATION_CODE = 87;
    private static final int CITY_TOKEN_SUM = 343; // DEBIC: 68+69+66+73+67
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean gpsPending = new AtomicBoolean(false);
    private WebView web;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 55, 42));
        getWindow().setNavigationBarColor(Color.rgb(9, 38, 31));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(243, 247, 243));
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setDefaultTextEncodingName("UTF-8");
        web.addJavascriptInterface(new JsBridge(), "MksAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    return !"/android_asset/index.html".equals(uri.getPath());
                }
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    private void deliver(String callback, JSONObject data) {
        String javascript = "window." + callback + " && window." + callback + "(" + data.toString() + ");";
        main.post(() -> { if (web != null) web.evaluateJavascript(javascript, null); });
    }
    private JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("error", message); } catch (Exception ignored) { }
        return result;
    }

    public class JsBridge {
        @JavascriptInterface public void refreshGps(String optionalProxy) {
            if (!gpsPending.compareAndSet(false, true)) return;
            executor.execute(() -> {
                try { deliver("onMksGps", loadVehicles(optionalProxy)); }
                catch (Exception ex) { deliver("onMksGps", failure(ex.getMessage())); }
                finally { gpsPending.set(false); }
            });
        }
        @JavascriptInterface public void testGps() {
            executor.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    String body = httpGet(DIRECT_API + "PingService", CITY_TOKEN_SUM + 60);
                    Document document = GpsXml.parse(body);
                    String value = document.getDocumentElement().getTextContent().trim();
                    int serverAge = Integer.parseInt(value);
                    if (serverAge < 0 || serverAge > 99_999_999) throw new Exception("Nieprawidłowa wartość Age");
                    result.put("ok", true);
                    result.put("message", "Serwer odpowiada na PingService. Dostępność pozycji autobusów sprawdzana jest osobno.");
                    result.put("serverTime", System.currentTimeMillis());
                } catch (Exception ex) {
                    try {
                        result.put("ok", false);
                        result.put("message", "Test PingService nieudany: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                    } catch (Exception ignored) { }
                }
                deliver("onMksGpsTest", result);
            });
        }
        @JavascriptInterface public void getLocation() { main.post(() -> requestLocation()); }
        @JavascriptInterface public void shareText(String value) {
            main.post(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("text/plain");
                    intent.putExtra(Intent.EXTRA_TEXT, value.length() > 20000 ? value.substring(0, 20000) : value);
                    startActivity(Intent.createChooser(intent, "Udostępnij rozkład"));
                } catch (Exception ignored) { }
            });
        }
        @JavascriptInterface public void closeApp() { main.post(() -> finish()); }
    }

    private JSONObject loadVehicles(String proxy) throws Exception {
        String directError;
        try { return vehiclesFrom(DIRECT_API, false); }
        catch (Exception ex) { directError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(); }
        String fallback = proxy == null ? "" : proxy.trim();
        if (fallback.isEmpty()) {
            throw new Exception("GPS MKS niedostępny: " + directError + ". Rozkłady offline działają dalej.");
        }
        try { return vehiclesFrom(proxyUrl(fallback), true); }
        catch (Exception ex) {
            throw new Exception("Serwer MKS: " + directError + "; zapasowe proxy: " + ex.getMessage());
        }
    }

    private String proxyUrl(String value) throws Exception {
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
            throw new Exception("Proxy wymaga adresu http:// lub https://");
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new Exception("Nieprawidłowy adres proxy (podaj folder bez parametrów)");
        String host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES);
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String path = uri.getEncodedPath() == null ? "" : uri.getEncodedPath().replaceAll("/+$", "");
        return scheme.toLowerCase(Locale.ROOT) + "://" + host + port + path + "/mks-api-bridge.php?method=";
    }

    private JSONObject vehiclesFrom(String endpoint, boolean viaProxy) throws Exception {
        String ping = httpGet(endpoint + "PingService", CITY_TOKEN_SUM + 60);
        Document doc = GpsXml.parse(ping);
        int serverAge;
        try { serverAge = Integer.parseInt(doc.getDocumentElement().getTextContent().trim()); }
        catch (Exception ex) { throw new Exception("Błędna odpowiedź PingService"); }
        if (serverAge < 0 || serverAge > 99_999_999) throw new Exception("Nieprawidłowy Age");
        String suffix = "GetVehicles" + (viaProxy ? "&" : "?")
                + "cNbLst=&cIdLst=&cRouteLst=&cTrackLst=&cDirLst=&cKrsLst=";
        Document response = GpsXml.parse(httpGet(endpoint + suffix, CITY_TOKEN_SUM + serverAge));
        NodeList nodes = response.getElementsByTagName("V");
        if (nodes.getLength() == 0) nodes = response.getElementsByTagNameNS("*", "V");
        JSONArray array = new JSONArray();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element node = (Element) nodes.item(i);
            double lat;
            double lng;
            try {
                lat = Double.parseDouble(node.getAttribute("y").replace(',', '.'));
                lng = Double.parseDouble(node.getAttribute("x").replace(',', '.'));
            } catch (Exception ignored) { continue; }
            if (lat < 49.0 || lat > 51.0 || lng < 20.0 || lng > 23.0) continue;
            JSONObject bus = new JSONObject();
            String number = node.getAttribute("nb");
            String id = node.getAttribute("id");
            String direction = node.getAttribute("op");
            if (direction.isEmpty()) direction = node.getAttribute("kr");
            bus.put("id", !number.isEmpty() ? number : !id.isEmpty() ? id : String.valueOf(i));
            bus.put("number", number);
            bus.put("line", node.getAttribute("nr"));
            bus.put("direction", direction);
            bus.put("variant", node.getAttribute("wt"));
            bus.put("lat", lat);
            bus.put("lng", lng);
            String delayRaw = node.getAttribute("o").trim();
            if (delayRaw.isEmpty()) bus.put("delaySeconds", JSONObject.NULL);
            else {
                try { bus.put("delaySeconds", Double.parseDouble(delayRaw.replace(',', '.'))); }
                catch (Exception ignored) { bus.put("delaySeconds", JSONObject.NULL); }
            }
            array.put(bus);
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("vehicles", array);
        result.put("source", viaProxy ? "proxy zapasowe" : "bezpośrednio z MKS");
        result.put("fetchedAt", System.currentTimeMillis());
        return result;
    }

    private String httpGet(String address, int age) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(address).openConnection();
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(12000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Age", String.valueOf(age));
            conn.setRequestProperty("User-Agent", "myBusOnline");
            conn.setRequestProperty("Accept", "application/xml, text/xml, */*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int status = conn.getResponseCode();
            if (status != 200) throw new Exception("API MKS zwróciło HTTP " + status + " (" + new URL(address).getPath() + ")");
            try (InputStream stream = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (out.size() + count > 2_000_000) throw new Exception("Odpowiedź GPS jest zbyt duża");
                    out.write(buffer, 0, count);
                }
                if (out.size() == 0) throw new Exception("Pusta odpowiedź serwera GPS");
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally { if (conn != null) conn.disconnect(); }
    }
    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_CODE);
            return;
        }
        try {
            LocationManager mgr = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (mgr == null) { deliver("onMksLocation", failure("Brak usługi lokalizacji")); return; }
            Location last = null;
            for (String provider : mgr.getProviders(true)) {
                Location loc = mgr.getLastKnownLocation(provider);
                if (loc != null && (last == null || loc.getTime() > last.getTime())) last = loc;
            }
            if (last != null && System.currentTimeMillis() - last.getTime() < 120000) {
                emitLocation(last);
                return;
            }
            String provider;
            if (mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) provider = LocationManager.NETWORK_PROVIDER;
            else if (mgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) provider = LocationManager.GPS_PROVIDER;
            else { deliver("onMksLocation", failure("Włącz lokalizację w telefonie")); return; }
            final AtomicBoolean locationPending = new AtomicBoolean(true);
            final LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    mgr.removeUpdates(this);
                    if (locationPending.compareAndSet(true, false)) emitLocation(location);
                }
                @Override public void onProviderDisabled(String disabled) {
                    mgr.removeUpdates(this);
                    if (locationPending.compareAndSet(true, false))
                        deliver("onMksLocation", failure("Włącz lokalizację w telefonie"));
                }
            };
            mgr.requestLocationUpdates(provider, 1000, 0, listener, Looper.getMainLooper());
            main.postDelayed(() -> {
                mgr.removeUpdates(listener);
                if (locationPending.compareAndSet(true, false))
                    deliver("onMksLocation", failure("Brak ustalenia położenia telefonu przez 15 s. Włącz GPS, wyjdź na zewnątrz i spróbuj ponownie."));
            }, 15000);
        } catch (Exception ex) { deliver("onMksLocation", failure(ex.getMessage())); }
    }
    private void emitLocation(Location loc) {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", true);
            json.put("lat", loc.getLatitude());
            json.put("lng", loc.getLongitude());
            json.put("accuracy", loc.getAccuracy());
            json.put("time", loc.getTime());
        } catch (Exception ignored) { }
        deliver("onMksLocation", json);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_CODE) return;
        boolean granted = false;
        for (int code : grantResults) if (code == PackageManager.PERMISSION_GRANTED) granted = true;
        if (granted) requestLocation();
        else deliver("onMksLocation", failure("Nie przyznano dostępu do lokalizacji"));
    }
    @Override public void onBackPressed() {
        if (web != null) web.evaluateJavascript("window.mksBack && window.mksBack()", null);
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        executor.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("MksAndroid"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
