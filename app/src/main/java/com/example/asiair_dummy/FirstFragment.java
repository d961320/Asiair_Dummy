package com.example.asiair_dummy;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.navigation.fragment.NavHostFragment;

import com.example.asiair_dummy.databinding.FragmentFirstBinding;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FirstFragment extends Fragment {

    private FragmentFirstBinding binding;
    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private volatile boolean isServerRunning = false;
    private ServerSocket tcpServerSocket;
    private ServerSocket httpServerSocket;

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean allGranted = true;
                for (Boolean granted : result.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    startHotspot();
                } else {
                    log("Tilladelser afvist. Kan ikke starte hotspot eller vise billeder.");
                }
            });

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        binding = FragmentFirstBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        binding.buttonFirst.setOnClickListener(v ->
                NavHostFragment.findNavController(FirstFragment.this)
                        .navigate(R.id.action_FirstFragment_to_SecondFragment)
        );

        binding.buttonStartHotspot.setText("Start ASIA Hotspot");
        binding.buttonStartHotspot.setOnClickListener(v -> {
            if (hotspotReservation == null) {
                checkPermissionsAndStartHotspot();
            } else {
                stopHotspot();
            }
        });
    }

    private void checkPermissionsAndStartHotspot() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(requireContext(), p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            startHotspot();
        } else {
            requestPermissionLauncher.launch(permissions.toArray(new String[0]));
        }
    }

    private void startHotspot() {
        WifiManager wifiManager = (WifiManager) requireContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null) {
            log("WifiManager er null");
            return;
        }

        String targetSsid = "ASIA";
        String targetPassword = "1234";

        log("Konfigurerer: " + targetSsid + " / 1234");
        trySetStaticConfig(wifiManager, targetSsid, targetPassword);

        log("Anmoder om hotspot...");
        updateStatus("Status: Starter...");
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    hotspotReservation = reservation;
                    
                    String ssid = reservation.getWifiConfiguration().SSID;
                    String pwd = reservation.getWifiConfiguration().preSharedKey;

                    log("HOTSPOT AKTIV!");
                    log("SSID: " + ssid);
                    log("KODE: " + pwd);
                    
                    String ip = getHotspotIpAddress();
                    log("IP: " + ip);
                    log("Gå til http://" + ip + ":4350");

                    updateStatus("Aktiv: " + ssid);
                    updateButtonText("Stop Hotspot");
                    startServers();
                }

                @Override
                public void onStopped() {
                    super.onStopped();
                    log("Hotspot stoppet.");
                    hotspotReservation = null;
                    updateStatus("Status: Idle");
                    updateButtonText("Start ASIA Hotspot");
                    stopServers();
                }

                @Override
                public void onFailed(int reason) {
                    super.onFailed(reason);
                    log("Fejl: " + reason);
                    updateStatus("Fejl: " + reason);
                    hotspotReservation = null;
                    updateButtonText("Start ASIA Hotspot");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            log("Sikkerhedsfejl: " + e.getMessage());
        }
    }

    private void trySetStaticConfig(WifiManager wifiManager, String ssid, String password) {
        try {
            Method method = wifiManager.getClass().getMethod("setWifiApConfiguration", WifiConfiguration.class);
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = ssid;
            config.preSharedKey = password;
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            method.invoke(wifiManager, config);
        } catch (Exception ignored) {
        }
    }

    private void stopHotspot() {
        if (hotspotReservation != null) {
            hotspotReservation.close();
            hotspotReservation = null;
        }
        stopServers();
        updateStatus("Status: Idle");
        updateButtonText("Start ASIA Hotspot");
    }

    private void startServers() {
        if (isServerRunning) return;
        isServerRunning = true;
        serverExecutor.execute(this::runTcpServer);
        serverExecutor.execute(this::runHttpServer);
    }

    private void runTcpServer() {
        try {
            tcpServerSocket = new ServerSocket(4350);
            while (isServerRunning) {
                Socket clientSocket = tcpServerSocket.accept();
                serverExecutor.execute(() -> handleTcpClient(clientSocket));
            }
        } catch (Exception e) {
            if (isServerRunning) log("TCP Serverfejl: " + e.getMessage());
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        String clientIp = clientSocket.getInetAddress().getHostAddress();
        try (Socket s = clientSocket;
             InputStream in = s.getInputStream();
             OutputStream out = s.getOutputStream()) {

            s.setSoTimeout(5000);
            byte[] buffer = new byte[8192];
            int bytesRead = in.read(buffer);
            if (bytesRead <= 0) return;

            String request = new String(buffer, 0, bytesRead);
            
            if (request.contains("GET /img?id=")) {
                // SERVER ET BILLEDE
                String idStr = request.split("id=")[1].split(" ")[0];
                long id = Long.parseLong(idStr);
                serveImage(id, out);
            } else if (request.contains("GET ")) {
                // VIS INTERFACE
                log("Sender interface til " + clientIp);
                sendMainHtml(out, clientIp);
            } else {
                // RÅ TCP
                log("Rå TCP fra " + clientIp + ": " + request.trim());
                out.write("VELKOMMEN TIL ASIAIR DUMMY\n".getBytes());
                out.write(("ACK: " + request).getBytes());
                while (isServerRunning && (bytesRead = in.read(buffer)) != -1) {
                    out.write(("ACK: " + new String(buffer, 0, bytesRead)).getBytes());
                }
            }
        } catch (Exception e) {
            log("Forbindelse lukket: " + clientIp);
        } finally {
            if (hotspotReservation != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (binding != null) updateStatus("Aktiv: " + hotspotReservation.getWifiConfiguration().SSID);
                });
            }
        }
    }

    private void sendMainHtml(OutputStream out, String clientIp) throws Exception {
        String body = generateMainHtml(clientIp);
        byte[] bodyBytes = body.getBytes("UTF-8");
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(bodyBytes);
        out.flush();
    }

    private String generateMainHtml(String clientIp) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<style>");
        html.append("body{font-family:sans-serif;text-align:center;background:#121212;color:white;margin:0;padding:20px;}");
        html.append(".box{background:#1e1e1e;padding:40px;border-radius:15px;display:inline-block;border:2px solid #d32f2f;margin-top:100px;box-shadow:0 10px 30px rgba(0,0,0,0.5);}");
        html.append("h1{color:#d32f2f;margin-bottom:10px;font-size:32px;}");
        html.append("p{font-size:18px;margin:15px 0;}");
        html.append(".btn{background:#d32f2f;color:white;padding:20px 40px;border:none;border-radius:10px;font-size:20px;cursor:pointer;margin-top:30px;font-weight:bold;transition:0.3s;}");
        html.append(".btn:hover{background:#b71c1c; transform:scale(1.05);}");
        html.append(".gallery{display:none;flex-wrap:wrap;justify-content:center;gap:15px;padding:20px;}");
        html.append(".img-card{background:#222;padding:5px;border-radius:8px;width:140px;height:140px;overflow:hidden;cursor:pointer;transition:0.2s;border:1px solid #444;}");
        html.append(".img-card:hover{border:2px solid #d32f2f;}");
        html.append(".img-card img{width:100%;height:100%;object-fit:cover;border-radius:5px;}");
        html.append("#fullscreen{display:none;position:fixed;top:0;left:0;width:100%;height:100%;background:rgba(0,0,0,0.98);z-index:9999;justify-content:center;align-items:center;}");
        html.append("#fullscreen img{max-width:100%;max-height:100%;object-fit:contain;cursor:pointer;}");
        html.append("</style>");
        html.append("<script>");
        html.append("function showGallery(){document.getElementById('landing').style.display='none';document.getElementById('gallery').style.display='flex';}");
        html.append("function openFull(id){const f=document.getElementById('fullscreen');const i=document.getElementById('full-img');i.src='/img?id='+id;f.style.display='flex';}");
        html.append("function closeFull(){document.getElementById('fullscreen').style.display='none';}");
        html.append("</script>");
        html.append("</head><body>");

        // Landing Page
        html.append("<div id='landing'><div class='box'><h1>ASIAIR DUMMY</h1>");
        html.append("<p style='color:#4caf50;font-weight:bold;font-size:24px;'>FORBINDELSE ER OPRETTET MED SUCCESS!</p>");
        html.append("<p>Din enhed er forbundet korrekt (IP: <b>").append(clientIp).append("</b>)</p>");
        html.append("<button class='btn' onclick='showGallery()'>SE BILLEDER PÅ MOBILEN</button></div></div>");

        // Galleri (Skjult startvisning)
        html.append("<div id='gallery' class='gallery'>");
        List<Long> images = getImagesIds();
        if (images.isEmpty()) {
            html.append("<p>Ingen billeder fundet. Sørg for at give appen tilladelse til billeder.</p>");
        } else {
            for (Long id : images) {
                html.append("<div class='img-card' onclick='openFull(\"").append(id).append("\")'>");
                html.append("<img src='/img?id=").append(id).append("' />");
                html.append("</div>");
            }
        }
        html.append("</div>");

        // Fuldskærmsvisning
        html.append("<div id='fullscreen' onclick='closeFull()'><img id='full-img' src='' /></div>");

        html.append("</body></html>");
        return html.toString();
    }

    private void serveImage(long id, OutputStream out) {
        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        try (InputStream imageStream = requireContext().getContentResolver().openInputStream(contentUri)) {
            if (imageStream == null) return;
            String header = "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nConnection: close\r\n\r\n";
            out.write(header.getBytes());
            byte[] buffer = new byte[16384];
            int bytesRead;
            while ((bytesRead = imageStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (Exception e) {
            log("Fejl ved afsendelse af billede " + id);
        }
    }

    private List<Long> getImagesIds() {
        List<Long> list = new ArrayList<>();
        String[] projection = {MediaStore.Images.Media._ID};
        try (Cursor cursor = requireContext().getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                while (cursor.moveToNext() && list.size() < 50) {
                    list.add(cursor.getLong(idCol));
                }
            }
        } catch (Exception e) {
            log("Galleri fejl: " + e.getMessage());
        }
        return list;
    }

    private void runHttpServer() {
        try {
            httpServerSocket = new ServerSocket(8080);
            while (isServerRunning) {
                try (Socket clientSocket = httpServerSocket.accept()) {
                    clientSocket.setSoTimeout(5000);
                    InputStream in = clientSocket.getInputStream();
                    OutputStream out = clientSocket.getOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead = in.read(buffer);
                    if (bytesRead > 0) {
                        String request = new String(buffer, 0, bytesRead);
                        String clientIp = clientSocket.getInetAddress().getHostAddress();
                        if (request.contains("GET /img?id=")) {
                            String idStr = request.split("id=")[1].split(" ")[0];
                            serveImage(Long.parseLong(idStr), out);
                        } else if (request.contains("GET ")) {
                            sendMainHtml(out, clientIp);
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void stopServers() {
        isServerRunning = false;
        try { if (tcpServerSocket != null) tcpServerSocket.close(); } catch (Exception ignored) {}
        try { if (httpServerSocket != null) httpServerSocket.close(); } catch (Exception ignored) {}
    }

    private String getHotspotIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (intf.getName().contains("wlan") || intf.getName().contains("ap")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr.getHostAddress().indexOf(':') < 0) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.43.1";
    }

    private void log(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (binding != null) {
                binding.textviewLog.append(message + "\n");
                binding.textviewLog.post(() -> {
                    View parent = (View) binding.textviewLog.getParent();
                    if (parent instanceof ScrollView) {
                        ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (getContext() != null) {
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateStatus(String status) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (binding != null) {
                binding.textviewStatus.setText(status);
            }
        });
    }

    private void updateButtonText(String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (binding != null) {
                binding.buttonStartHotspot.setText(text);
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopHotspot();
        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopHotspot();
    }
}