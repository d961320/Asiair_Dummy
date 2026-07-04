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
    private final ExecutorService serverExecutor = Executors.newFixedThreadPool(2);
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

        // SSID og kodeord er nu på 4 karakterer
        String targetSsid = "ASIA";
        String targetPassword = "1234";

        log("Prøver at konfigurere: " + targetSsid + " (Kode: " + targetPassword + ")");
        log("Bemærk: 4-cifret kode kan blive afvist af systemet (kræver normalt 8).");
        
        // Forsøg at sætte statisk konfiguration (Reflection)
        trySetStaticConfig(wifiManager, targetSsid, targetPassword);

        log("Anmoder systemet om hotspot...");
        updateStatus("Status: Anmoder...");
        try {
            wifiManager.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                    super.onStarted(reservation);
                    hotspotReservation = reservation;
                    
                    String ssid = reservation.getWifiConfiguration().SSID;
                    String pwd = reservation.getWifiConfiguration().preSharedKey;

                    log("HOTSPOT STARTET!");
                    log("Forbind til SSID: " + ssid);
                    log("Kodeord: " + pwd);
                    
                    if (!ssid.contains(targetSsid)) {
                        log("Info: Android har valgt sit eget SSID/Kode pga. sikkerhed.");
                    }
                    
                    String ip = getHotspotIpAddress();
                    log("IP-adresse: " + ip);
                    log("TCP Server på port 4350");

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
                    log("Hotspot fejlede. Kode: " + reason);
                    updateStatus("Status: Fejl (" + reason + ")");
                    hotspotReservation = null;
                    updateButtonText("Start ASIA Hotspot");
                }
            }, new Handler(Looper.getMainLooper()));
        } catch (SecurityException e) {
            log("Sikkerhedsfejl: " + e.getMessage());
            updateStatus("Status: Tilladelsesfejl");
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
            // Metoden findes sandsynligvis ikke eller er blokeret
        }
    }

    private void stopHotspot() {
        if (hotspotReservation != null) {
            log("Stopper Hotspot...");
            hotspotReservation.close();
            hotspotReservation = null;
            updateStatus("Status: Idle");
            updateButtonText("Start ASIA Hotspot");
            stopServers();
        }
    }

    private void startServers() {
        if (isServerRunning) return;
        isServerRunning = true;
        
        // Start TCP Server (Asiair Dummy)
        serverExecutor.execute(this::runTcpServer);
        
        // Start HTTP Server (Web Test)
        serverExecutor.execute(this::runHttpServer);
    }

    private void runTcpServer() {
        try {
            tcpServerSocket = new ServerSocket(4350);
            while (isServerRunning) {
                try (Socket clientSocket = tcpServerSocket.accept()) {
                    log("Enhed forbundet: " + clientSocket.getInetAddress());
                    InputStream in = clientSocket.getInputStream();
                    OutputStream out = clientSocket.getOutputStream();
                    
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        String data = new String(buffer, 0, bytesRead);
                        log("MODTAGET: " + data.trim());
                        
                        // Svar tilbage (Back and forth)
                        String response = "ASIAIR_ACK: OK\n";
                        out.write(response.getBytes());
                        out.flush();
                        log("SENDT: " + response.trim());
                    }
                } catch (Exception e) {
                    if (isServerRunning) log("TCP Klientfejl: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (isServerRunning) log("TCP Serverfejl: " + e.getMessage());
        }
    }

    private void runHttpServer() {
        try {
            httpServerSocket = new ServerSocket(8080);
            while (isServerRunning) {
                try (Socket clientSocket = httpServerSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                    OutputStream out = clientSocket.getOutputStream();
                    
                    String requestLine = in.readLine();
                    if (requestLine != null) {
                        log("HTTP MODTAGET: " + requestLine);
                        
                        String responseBody = "<html><body><h1>Asiair Dummy</h1><p>Test OK - 4 tegn SSID/Kode.</p></body></html>";
                        String response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Content-Length: " + responseBody.getBytes().length + "\r\n" +
                                "Connection: close\r\n\r\n" +
                                responseBody;
                        
                        out.write(response.getBytes());
                        out.flush();
                    }
                } catch (Exception e) {
                    if (isServerRunning) log("HTTP Klientfejl: " + e.getMessage());
                }
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
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().contains("wlan") || intf.getName().contains("ap")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
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