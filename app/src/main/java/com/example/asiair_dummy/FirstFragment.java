package com.example.asiair_dummy;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
                Boolean fineLocationGranted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                if (fineLocationGranted != null && fineLocationGranted) {
                    startHotspot();
                } else {
                    log("Tilladelse afvist. Kan ikke starte hotspot.");
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
                    log("Port 4350: Hybrid (Web + TCP)");
                    log("Port 8080: Web kun");

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

            log("Forbindelse på 4350 fra: " + clientIp);
            showToast("Ny klient: " + clientIp);
            updateStatus("KLIENT: " + clientIp);

            byte[] buffer = new byte[4096];
            // Vi venter på første data for at se om det er en browser
            int bytesRead = in.read(buffer);
            if (bytesRead == -1) return;

            String received = new String(buffer, 0, bytesRead);
            
            if (received.startsWith("GET ") || received.startsWith("POST ")) {
                // DET ER EN BROWSER! Send korrekt HTTP svar.
                log("HTTP forespørgsel detekteret på 4350.");
                String body = "<html><body style='font-family:sans-serif; text-align:center;'>" +
                        "<h1>Asiair Dummy - Port 4350</h1>" +
                        "<p style='color:green; font-size:24px;'>FORBINDELSE VIRKER!</p>" +
                        "<p>Serveren modtog din browser-forespørgsel korrekt.</p>" +
                        "</body></html>";
                
                String header = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: " + body.getBytes().length + "\r\n" +
                        "Connection: close\r\n\r\n";
                
                out.write(header.getBytes());
                out.write(body.getBytes());
                out.flush();
                log("HTTP svar sendt til " + clientIp);
            } else {
                // DET ER EN RÅ TCP KLIENT (Terminal/Asiair App)
                log("Rå TCP data fra " + clientIp + ": " + received.trim());
                String ack = "ASIAIR_ACK: Modtaget '" + received.trim() + "'\n";
                out.write(ack.getBytes());
                out.flush();

                while (isServerRunning && (bytesRead = in.read(buffer)) != -1) {
                    String data = new String(buffer, 0, bytesRead);
                    log("MODTAGET: " + data.trim());
                    String response = "ASIAIR_ACK: " + data.trim() + "\n";
                    out.write(response.getBytes());
                    out.flush();
                }
            }
        } catch (Exception e) {
            log("Forbindelse lukket: " + clientIp);
        } finally {
            if (hotspotReservation != null) {
                updateStatus("Aktiv: " + hotspotReservation.getWifiConfiguration().SSID);
            }
        }
    }

    private void runHttpServer() {
        try {
            httpServerSocket = new ServerSocket(8080);
            while (isServerRunning) {
                try (Socket clientSocket = httpServerSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    OutputStream out = clientSocket.getOutputStream();
                    if (in.readLine() != null) {
                        String clientIp = clientSocket.getInetAddress().getHostAddress();
                        log("HTTP besøg på 8080 fra: " + clientIp);
                        String body = "<html><body><h1>Asiair Dummy Port 8080</h1><p>Test OK!</p></body></html>";
                        String response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: " + body.getBytes().length + "\r\n" +
                                "Connection: close\r\n\r\n" + body;
                        out.write(response.getBytes());
                        out.flush();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (isServerRunning) log("HTTP Serverfejl: " + e.getMessage());
        }
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