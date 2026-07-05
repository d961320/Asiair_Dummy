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
                    log("Gå til http://" + ip + ":4350 for at se billeder");

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

            log("Forbindelse fra: " + clientIp);
            updateStatus("KLIENT: " + clientIp);

            byte[] buffer = new byte[8192];
            int bytesRead = in.read(buffer);
            if (bytesRead == -1) return;

            String received = new String(buffer, 0, bytesRead);
            
            if (received.startsWith("GET /img?id=")) {
                // SERVER ET BILLEDE
                String idStr = received.split("id=")[1].split(" ")[0];
                long id = Long.parseLong(idStr);
                serveImage(id, out);
            } else if (received.startsWith("GET ")) {
                // VIS GALLERI
                log("Sender billedgalleri til " + clientIp);
                String body = generateGalleryHtml();
                
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.getBytes("UTF-8").length + "\r\n" +
                        "Connection: close\r\n\r\n";
                
                out.write(header.getBytes("UTF-8"));
                out.write(body.getBytes("UTF-8"));
                out.flush();
            } else {
                // RÅ TCP
                log("Rå TCP fra " + clientIp);
                out.write("VELKOMMEN TIL ASIAIR DUMMY\n".getBytes());
                while (isServerRunning && (bytesRead = in.read(buffer)) != -1) {
                    out.write(("ACK: " + new String(buffer, 0, bytesRead)).getBytes());
                }
            }
        } catch (Exception e) {
            log("Forbindelse lukket: " + clientIp + " (" + e.getMessage() + ")");
        } finally {
            if (hotspotReservation != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (binding != null) updateStatus("Aktiv: " + hotspotReservation.getWifiConfiguration().SSID);
                });
            }
        }
    }

    private String generateGalleryHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        html.append("<style>body{font-family:sans-serif;text-align:center;background:#f0f0f0;margin:0;padding:20px;}");
        html.append(".gallery{display:flex;flex-wrap:wrap;justify-content:center;gap:15px;}");
        html.append(".img-container{background:white;padding:10px;border-radius:8px;box-shadow:0 2px 5px rgba(0,0,0,0.1);width:150px;}");
        html.append("img{max-width:100%;height:auto;border-radius:4px;}");
        html.append("h1{color:#d32f2f;}</style></head><body>");
        html.append("<h1>Mobil Billeder</h1><div class='gallery'>");

        List<ImageInfo> images = getImagesFromPhone();
        if (images.isEmpty()) {
            html.append("<p>Ingen billeder fundet eller mangler tilladelse.</p>");
        } else {
            for (ImageInfo img : images) {
                html.append("<div class='img-container'>");
                html.append("<img src='/img?id=").append(img.id).append("' />");
                html.append("<p style='font-size:10px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'>").append(img.name).append("</p>");
                html.append("</div>");
            }
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private void serveImage(long id, OutputStream out) {
        Uri contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
        try (InputStream imageStream = requireContext().getContentResolver().openInputStream(contentUri)) {
            if (imageStream == null) return;
            
            // Vi gætter på det er en JPEG
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(header.getBytes());

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = imageStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (Exception e) {
            log("Kunne ikke sende billede " + id + ": " + e.getMessage());
        }
    }

    private List<ImageInfo> getImagesFromPhone() {
        List<ImageInfo> list = new ArrayList<>();
        String[] projection = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME};
        
        try (Cursor cursor = requireContext().getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")) {
            
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                
                while (cursor.moveToNext() && list.size() < 24) { // Begræns til 24 billeder
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    list.add(new ImageInfo(id, name));
                }
            }
        } catch (Exception e) {
            log("Cursor fejl: " + e.getMessage());
        }
        return list;
    }

    private static class ImageInfo {
        long id;
        String name;
        ImageInfo(long id, String name) { this.id = id; this.name = name; }
    }

    private void runHttpServer() {
        try {
            httpServerSocket = new ServerSocket(8080);
            while (isServerRunning) {
                try (Socket clientSocket = httpServerSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    OutputStream out = clientSocket.getOutputStream();
                    if (in.readLine() != null) {
                        String body = "<html><body><h1>Asiair Dummy 8080</h1><p>Brug port 4350 for billeder.</p></body></html>";
                        String response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
                        out.write(response.getBytes());
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void stopServers() {
        isServerRunning = false;
        try {
            if (tcpServerSocket != null) tcpServerSocket.close();
            if (httpServerSocket != null) httpServerSocket.close();
        } catch (Exception ignored) {}
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