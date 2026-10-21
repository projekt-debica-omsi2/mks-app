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
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.util.Locale;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilderFactory;

public class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION = 770;
    private static final String API = "http://rj.mks.debica.pl/AndroidService/SchedulesService.svc/";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private WebView web;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(9, 49, 37));
        getWindow().setNavigationBarColor(Color.rgb(7, 31, 26));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(245, 248, 245));
        setContentView(web);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setDefaultTextEncodingName("UTF-8");
        web.addJavascriptInterface(new Bridge(), "MksAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("file".equals(uri.getScheme())) return false;
                String scheme = uri.getScheme();
                if ("https".equals(scheme) || "http".equals(scheme)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    private void callJs(String name, JSONObject payload) {
        String js = "window." + name + " && window." + name + "(" + payload.toString() + ");";
        ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
    }
    private JSONObject error(String message) {
        JSONObject obj = new JSONObject();
        try { obj.put("ok", false); obj.put("error", message); } catch (Exception ignored) { }
        return obj;
    }

    public class Bridge {
        @JavascriptInterface public void refreshGps(String proxyBase) {
            if (!fetching.compareAndSet(false, true)) return;
            network.execute(() -> {
                try { callJs("onMksGps", loadGps(proxyBase)); }
                catch (Exception ex) { callJs("onMksGps", error("Nie można pobrać GPS: " + ex.getMessage())); }
                finally { fetching.set(false); }
            });
        }
        @JavascriptInterface public void getLocation() { ui.post(() -> requestLocation()); }
        @JavascriptInterface public void closeApp() { ui.post(() -> finish()); }
        @JavascriptInterface public void shareText(String content) {
            ui.post(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, content.length() > 20000 ? content.substring(0, 20000) : content);
                startActivity(Intent.createChooser(send, "Udostępnij tabliczkę"));
            });
        }
    }

    // Najpierw połączenie bezpośrednio z serwerem MKS.
    // Prywatny proxy jest OPCJONALNYM zapasowym źródłem, a nie wymogiem działania.
    private JSONObject loadGps(String proxyBase) throws Exception {
        String directError;
        try {
            return vehicles("");
        } catch (Exception ex) {
            directError = ex.getMessage();
        }

        String base = proxyBase == null ? "" : proxyBase.trim();
        if (base.isEmpty()) {
            throw new Exception("Serwer MKS (bezpośrednio): " + directError
                + ". Sprawdź internet albo spróbuj później. Rozkłady offline nadal działają.");
        }
        try {
            return vehicles(base);
        } catch (Exception ex) {
            throw new Exception("Serwer MKS (bezpośrednio): " + directError
                + "; zapasowe proxy: " + ex.getMessage());
        }
    }

    // Zapytania wykonuje natywny klient Android; przeglądarkowe ograniczenie CORS nie obowiązuje.
    // API oryginalnego myBus wymaga nagłówka Age obliczanego z kodu miasta DEBIC.
    private JSONObject vehicles(String proxy) throws Exception {
        boolean viaProxy = !proxy.isEmpty();
        String endpoint;
        if (viaProxy) {
            Uri base = Uri.parse(proxy);
            if (!("https".equalsIgnoreCase(base.getScheme()) || "http".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null) {
                throw new Exception("Podaj pełny URL proxy, np. https://twoja-strona.pl");
            }
            // Android / java.net.URL oczekuje hosta DNS w ASCII (IDN / polskie znaki).
            // Także adres e-dębica.pl konwertujemy na xn--e-dbica-c9a.pl.
            if (base.getQuery() != null || base.getFragment() != null) {
                throw new Exception("Podaj adres folderu proxy, bez parametrów i #.");
            }
            String host;
            try { host = IDN.toASCII(base.getHost(), IDN.USE_STD3_ASCII_RULES); }
            catch (Exception ex) { throw new Exception("Nieprawidłowa domena proxy."); }
            if (host.isEmpty()) throw new Exception("Nieprawidłowa domena proxy.");
            String scheme = base.getScheme().toLowerCase(Locale.ROOT);
            String port = base.getPort() < 0 ? "" : ":" + base.getPort();
            String path = base.getEncodedPath() == null ? "" : base.getEncodedPath().replaceAll("/+$", "");
            endpoint = scheme + "://" + host + port + path + "/mks-api-bridge.php?method=";
        } else endpoint = API;
        String ping = getXml(endpoint + "PingService", 403);
        Document pingXml = parseXml(ping);
        String ageText = pingXml.getDocumentElement().getTextContent().trim();
        int serverAge;
        try { serverAge = Integer.parseInt(ageText); }
        catch (Exception ignored) { throw new Exception("Nieprawidłowa odpowiedź PingService"); }
        if (serverAge < 0 || serverAge > 99999999) throw new Exception("Nieprawidłowy Age");
        String params = viaProxy ? "&" : "?";
        String path = "GetVehicles" + params + "cNbLst=&cIdLst=&cRouteLst=&cTrackLst=&cDirLst=&cKrsLst=";
        Document doc = parseXml(getXml(endpoint + path, 343 + serverAge));
        NodeList list = doc.getElementsByTagName("V");
        if (list.getLength() == 0) list = doc.getElementsByTagNameNS("*", "V");
        JSONArray items = new JSONArray();
        for (int i = 0; i < list.getLength(); i++) {
            Element item = (Element) list.item(i);
            double lat, lng;
            try {
                lat = Double.parseDouble(item.getAttribute("y").replace(',', '.'));
                lng = Double.parseDouble(item.getAttribute("x").replace(',', '.'));
            } catch (Exception ignored) { continue; }
            if (lat < 49.0 || lat > 51.0 || lng < 20.0 || lng > 23.0) continue;
            JSONObject obj = new JSONObject();
            String number = item.getAttribute("nb");
            String id = item.getAttribute("id");
            obj.put("id", !number.isEmpty() ? number : !id.isEmpty() ? id : String.valueOf(i));
            obj.put("number", number);
            obj.put("line", item.getAttribute("nr"));
            obj.put("direction", !item.getAttribute("op").isEmpty() ? item.getAttribute("op") : item.getAttribute("kr"));
            obj.put("variant", item.getAttribute("wt"));
            obj.put("lat", lat);
            obj.put("lng", lng);
            double delay = 0;
            try { delay = Double.parseDouble(item.getAttribute("o").replace(',', '.')); } catch (Exception ignored) { }
            obj.put("delaySeconds", delay);
            items.put(obj);
        }
        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("vehicles", items);
        result.put("source", viaProxy ? "zapasowe proxy" : "bezpośrednio z MKS");
        result.put("fetchedAt", System.currentTimeMillis());
        return result;
    }

    private String getXml(String address, int age) throws Exception {
        HttpURLConnection http = null;
        try {
            http = (HttpURLConnection) new URL(address).openConnection();
            http.setRequestMethod("GET");
            http.setRequestProperty("Age", String.valueOf(age));
            http.setRequestProperty("User-Agent", "myBusOnline");
            http.setRequestProperty("Accept", "application/xml,text/xml,*/*");
            http.setRequestProperty("Accept-Encoding", "identity");
            http.setConnectTimeout(6000);
            http.setReadTimeout(12000);
            int status = http.getResponseCode();
            if (status != 200) throw new Exception("HTTP " + status);
            try (InputStream stream = http.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (output.size() + n > 2_000_000) throw new Exception("Zbyt duża odpowiedź API");
                    output.write(buffer, 0, n);
                }
                byte[] bytes = output.toByteArray();
                if (bytes.length == 0) throw new Exception("Pusta odpowiedź API");
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { if (http != null) http.disconnect(); }
    }
    private Document parseXml(String value) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void requestLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
            return;
        }
        try {
            LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (manager == null) { callJs("onMksLocation", error("Brak usługi lokalizacji")); return; }
            Location recent = null;
            for (String provider : manager.getProviders(true)) {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && (recent == null || candidate.getTime() > recent.getTime())) recent = candidate;
            }
            if (recent != null) { deliverLocation(recent); return; }
            String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) {
                    manager.removeUpdates(this);
                    deliverLocation(location);
                }
                @Override public void onProviderDisabled(String p) { callJs("onMksLocation", error("Włącz lokalizację telefonu")); }
            };
            manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper());
            ui.postDelayed(() -> manager.removeUpdates(listener), 12000);
        } catch (Exception e) { callJs("onMksLocation", error("Nie można pobrać lokalizacji: " + e.getMessage())); }
    }
    private void deliverLocation(Location loc) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("ok", true);
            obj.put("lat", loc.getLatitude()); obj.put("lng", loc.getLongitude());
            obj.put("accuracy", loc.getAccuracy()); obj.put("time", loc.getTime());
        } catch (Exception ignored) { }
        callJs("onMksLocation", obj);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == LOCATION_PERMISSION) {
            boolean approved = false;
            for (int item : grants) if (item == PackageManager.PERMISSION_GRANTED) approved = true;
            if (approved) requestLocation();
            else callJs("onMksLocation", error("Nie przyznano dostępu do lokalizacji"));
        }
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("window.mksBack && window.mksBack()", null);
    }
    @Override protected void onDestroy() {
        network.shutdownNow();
        if (web != null) { web.removeJavascriptInterface("MksAndroid"); web.destroy(); web = null; }
        super.onDestroy();
    }
}
