package com.scosche.SDK24.example;

import android.os.Build;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.scosche.sdk24.example.R;
import com.scosche.sdk24.*;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.IOException;
import java.io.FileWriter;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements RhythmSDKScanningCallback, RhythmSDKDeviceCallback, RhythmSDKFitFileCallback, ScannedDeviceFragment.OnListFragmentInteractionListener {

    private ScoscheSDK24 sdk;
    private String fileName;
    private byte[] data;
    private boolean isRhythm24;
    private BluetoothGatt bleGatt;
    private String currentSubjectId = "";
    private int currentSessionId = 1;
    private static final UUID HR_SERVICE_UUID     = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG_UUID  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final ArrayList<long[]> bpmBuffer = new ArrayList<>();
    private final List<Double> rrBuffer = Collections.synchronizedList(new ArrayList<>());
    private List<Double> sessionRrBuffer = Collections.synchronizedList(new ArrayList<>());
    private final List<double[]> rrTimestampBuffer = Collections.synchronizedList(new ArrayList<>());
    private String currentActivity = "rest";
    public ScoscheSDK24 getSdk() {
        return sdk;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.d("MainActivity", "onCreate: Baslatildi");
        checkPermissions();
        sdk = new ScoscheSDK24(this);
        Log.d("MainActivity", "onCreate: SDK baslatildi");
        try {
            Fragment fragment = ScannedDeviceFragment.class.getDeclaredConstructor().newInstance();
            getSupportFragmentManager().beginTransaction().replace(R.id.flContent, fragment, "ScannedDeviceFragment").commit();
            Log.d("MainActivity", "onCreate: ScannedDeviceFragment yuklendi");
        } catch (Exception e) {
            Log.e("MainActivity", "Fragment olusturulamadi: " + e.getMessage());
        }
        sdk.startScan(this);
    }

    private FileWriter csvWriter;

    public void setCsvWriter(FileWriter writer) {
        this.csvWriter = writer;
    }

    private void checkPermissions() {
        Log.d("MainActivity", "checkPermissions: Izinler kontrol ediliyor");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 2);
            }
        }
    }

    @Override
    public void deviceFound(RhythmDevice device) {
        Log.d("MainActivity", "deviceFound: Cihaz bulundu: " + device.getName());
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment f = fragmentManager.findFragmentByTag("ScannedDeviceFragment");
        if (f != null && device.getName() != null) {
            ((ScannedDeviceFragment) f).handleBluetoothDevice(device);
        }
    }

    @Override
    public void deviceConnected(RhythmDevice rhythmDevice) {
        try {
            switch (rhythmDevice.deviceModel) {
                case RHYTHM_24:
                    Fragment rhythm24Fragment = Rhythm24Fragment.class.getDeclaredConstructor().newInstance();
                    getSupportFragmentManager().beginTransaction().replace(R.id.flContent, rhythm24Fragment, "Rhythm24Fragment").commit();
                    isRhythm24 = true;
                    sdk.updateSportMode(255);
                    rrBuffer.clear();
                    rrTimestampBuffer.clear();
                    Log.d("HRV", "HRV modu aktif edildi");
                    runOnUiThread(() -> {
                        Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                        if (f != null) ((Rhythm24Fragment) f).startStabilizationCountdown();
                    });
                    try {
                        java.lang.reflect.Field deviceField = rhythmDevice.getClass().getDeclaredField("device");
                        deviceField.setAccessible(true);
                        android.bluetooth.BluetoothDevice btDevice = (android.bluetooth.BluetoothDevice) deviceField.get(rhythmDevice);
                        // [FIX] btDevice null kontrolü eklendi
                        if (btDevice != null) {
                            Log.d("MAC", "Cihaz MAC: " + btDevice.getAddress());
                            connectNativeBle(btDevice);
                        } else {
                            Log.e("MAC", "btDevice null, MAC alinamadi");
                        }
                    } catch (Exception ex) {
                        Log.e("MAC", "MAC alinamadi: " + ex.getMessage());
                    }
                    break;
                case RHYTHM_E:
                case RHYTHM_19:
                    Fragment rhythmPlusFragment = RhythmPlusFragment.class.getDeclaredConstructor().newInstance();
                    getSupportFragmentManager().beginTransaction().replace(R.id.flContent, rhythmPlusFragment, "RhythmPlusFragment").commit();
                    isRhythm24 = false;
                    break;
                default:
                    Toast.makeText(this, "Invalid device type.", Toast.LENGTH_LONG).show();
                    break;
            }
        } catch (Exception e) {
            Log.e("MainActivity", "deviceConnected hatasi: " + e.getMessage());
        }
    }

    @Override
    public void updateHeartRate(String heartRate) {
        int bpmValue;
        try {
            bpmValue = Integer.parseInt(heartRate);
        } catch (NumberFormatException e) {
            Log.w("BPM", "Gecersiz BPM degeri: " + heartRate);
            return;
        };
        if (isBpmNoise(bpmValue)) return;
        long timestamp = System.currentTimeMillis();
        bpmBuffer.add(new long[]{timestamp, bpmValue});
        if (csvWriter != null) {
            try {
                csvWriter.write(currentSubjectId + "," + currentSessionId + "," + currentActivity + "," + timestamp + "," + bpmValue + "," + "\n");
                csvWriter.flush();
            } catch (IOException e) {
                Log.e("CSV", "Yazma hatasi: " + e.getMessage());
            }
        }
        if (bpmBuffer.size() >= 2) {
            long elapsed = bpmBuffer.get(bpmBuffer.size() - 1)[0] - bpmBuffer.get(0)[0];
            if (elapsed >= 20000) {
                Log.d("BPM_WINDOW", "20 saniyelik pencere tamamlandi. Veri sayisi: " + bpmBuffer.size());
                bpmBuffer.clear();
            }
        }
        Log.d("BPM_BUFFER", timestamp + " -> " + bpmValue + " BPM | Tampon: " + bpmBuffer.size());
        runOnUiThread(() -> {
            Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
            if (f != null) {
                ((Rhythm24Fragment) f).updateHeartRate(heartRate);
            }
        });
    }

    private boolean isBpmNoise(int newBpm) {
        return newBpm < 30 || newBpm > 220;
    }

    private double getClosestRr(long timestamp, int bpm) {
        double closest = 0;
        long minDiff = Long.MAX_VALUE;
        int closestIndex = -1;
        synchronized (rrTimestampBuffer) {
            for (int i = 0; i < rrTimestampBuffer.size(); i++) {
                double[] entry = rrTimestampBuffer.get(i);
                if (entry == null) continue;
                long diff = Math.abs((long) entry[0] - timestamp);
                if (diff < minDiff && diff < 500) {
                    double rrBpm = 60000.0 / entry[1];
                    if (Math.abs(rrBpm - bpm) > 20) continue;
                    minDiff = diff;
                    closest = entry[1];
                    closestIndex = i;
                }
            }
            if (closestIndex >= 0) {
                rrTimestampBuffer.remove(closestIndex);
            }
        }
        return closest;
    }

    private ArrayList<Double> filterRrNoise(List<Double> rr) {
        ArrayList<Double> filtered = new ArrayList<>();
        int window = 5;
        double threshold = 0.20; // %20 sapma — Yekta Hoca eşik=20 demişti

        for (int i = 0; i < rr.size(); i++) {
            int start = Math.max(0, i - window / 2);
            int end = Math.min(rr.size(), i + window / 2 + 1);
            double sum = 0;
            int count = 0;
            for (int j = start; j < end; j++) {
                if (j != i) { sum += rr.get(j); count++; }
            }
            double localAvg = count > 0 ? sum / count : rr.get(i);
            double deviation = Math.abs(rr.get(i) - localAvg) / localAvg;

            if (deviation <= threshold) {
                filtered.add(rr.get(i));
            } else {
                Log.d("RR_FILTER", "Noise atildi: " +
                        String.format(Locale.getDefault(), "%.1f", rr.get(i)) + "ms" +
                        " | localAvg=" + String.format(Locale.getDefault(), "%.1f", localAvg) + "ms" +
                        " | sapma=%" + String.format(Locale.getDefault(), "%.1f", deviation * 100));
            }
        }
        Log.d("RR_FILTER", "Orijinal: " + rr.size() +
                " → Filtrelenmis: " + filtered.size());
        return filtered;
    }

    private float cosineSimilarity(float[] vectorA, float[] vectorB) {
        float dotProduct = 0;
        float normA = 0;
        float normB = 0;
        for (int i = 0; i < vectorA.length; i++) {
            dotProduct += vectorA[i] * vectorB[i];
            normA += vectorA[i] * vectorA[i];
            normB += vectorB[i] * vectorB[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dotProduct / (float)(Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Override
    public void monitorStateInvalid() {
        runOnUiThread(() -> {
            if (isRhythm24) {
                Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                if (f != null) ((Rhythm24Fragment) f).updateHeartRate("???");
            } else {
                Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
                if (f != null) ((RhythmPlusFragment) f).updateHeartRate("???");
            }
        });
    }

    @Override
    public void updateBatteryLevel(int batteryLevel) {
        runOnUiThread(() -> {
            if (isRhythm24) {
                Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                if (f != null) ((Rhythm24Fragment) f).updateBattery(batteryLevel);
            } else {
                Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
                if (f != null) ((RhythmPlusFragment) f).updateBattery(batteryLevel);
            }
        });
    }

    @Override
    @SuppressWarnings("unused")
    public void updateZone(int zone) {}

    @SuppressWarnings("unused")
    public void onSignalQuality(int quality) {
        Log.d("SIGNAL", "Sinyal kalitesi: " + quality);
    }

    @Override
    public void updateSportMode(int sportMode) {
        runOnUiThread(() -> {
            Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
            if (f != null) ((Rhythm24Fragment) f).updateSportMode(sportMode);
        });
    }

    @Override
    public void updateFirmwareVersion(String value) {
        runOnUiThread(() -> {
            Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
            if (f != null) ((RhythmPlusFragment) f).updateFirmwareVersion(value);
        });
    }

    @Override
    public void error(ErrorType errorType) {
        Toast.makeText(this, "Error scanning: " + errorType, Toast.LENGTH_LONG).show();
    }

    @Override
    public void deviceLost(RhythmDevice device) {
        rrBuffer.clear();
        rrTimestampBuffer.clear();
        bpmBuffer.clear();
        Log.d("BLE", "Cihaz kayboldu, buffer'lar temizlendi");
        Fragment f = getSupportFragmentManager().findFragmentByTag("ScannedDeviceFragment");
        if (f != null) ((ScannedDeviceFragment) f).removeDevice(device);
    }

    // [FIX] bleGatt referansını tutan ve düzgün kapatan yardımcı metot
    private void closeBle() {
        if (bleGatt == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        bleGatt.close();
        bleGatt = null;
    }

    @Override
    public void fitFilesFound(List<FitFileContent.FitFileInfo> files) {
        Fragment f = getSupportFragmentManager().findFragmentByTag("FitFilesFragment");
        if (f != null) ((FitFilesFragment) f).displayFitFiles(files);
    }

    @Override
    public void fitFileDownloadComplete(byte[] fitFileData, String downloadedFileName) {
        this.fileName = downloadedFileName;
        this.data = fitFileData;
        saveFile();
    }

    @Override
    public void fitFileDeleteComplete(String fileName) {
        getSdk().clearFiles();
        runOnUiThread(() -> Toast.makeText(getApplicationContext(), "File deleted: " + fileName, Toast.LENGTH_LONG).show());
        getSdk().getFitFiles();
    }

    @Override
    public void downloadProgressUpdate(int percent, FitFileContent.FitFileInfo file) {
        runOnUiThread(() -> {
            Fragment f = getSupportFragmentManager().findFragmentByTag("FitFilesFragment");
            if (f != null) ((FitFilesFragment) f).getAdapter().test(percent);
        });
    }

    private void saveFile() {
        String filePath = getFilesDir() + "/" + fileName;
        try {
            FileOutputStream outputStream = new FileOutputStream(filePath);
            outputStream.write(data);
            outputStream.close();
            Log.d("FIT_FILE", "Dosya kaydedildi: " + filePath);
        } catch (Exception e) {
            Log.e("FIT_FILE", "Kayit hatasi: " + e.getMessage());
        }
        Toast.makeText(getApplicationContext(), "File downloaded: " + fileName, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 23 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveFile();
        }
    }

    private void connectNativeBle(android.bluetooth.BluetoothDevice btDevice) {
        closeBle();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e("BLE", "BLUETOOTH_CONNECT izni yok");
                return;
            }
        }
        bleGatt = btDevice.connectGatt(this, false, gattCallback);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.e("BLE", "BLUETOOTH_CONNECT izni yok");
                        return;
                    }
                }
                Log.d("BLE", "Native baglandi, servisler kesfediliyor...");
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("BLE", "BLUETOOTH_CONNECT izni yok");
                    return;
                }
            }
            BluetoothGattService hrService = gatt.getService(HR_SERVICE_UUID);
            if (hrService == null) { Log.e("BLE", "HR Service bulunamadi"); return; }
            BluetoothGattCharacteristic hrChar = hrService.getCharacteristic(HR_MEASUREMENT_UUID);
            if (hrChar == null) { Log.e("BLE", "0x2A37 bulunamadi"); return; }
            gatt.setCharacteristicNotification(hrChar, true);
            BluetoothGattDescriptor descriptor = hrChar.getDescriptor(CLIENT_CONFIG_UUID);
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                gatt.writeDescriptor(descriptor);
            }
            Log.d("BLE", "0x2A37 notification aktif");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (!HR_MEASUREMENT_UUID.equals(characteristic.getUuid())) return;
            parseHrMeasurement(characteristic.getValue());
        }
    };

    private void parseHrMeasurement(byte[] hrData) {
        if (hrData == null || hrData.length < 2) return;
        int flags = hrData[0] & 0xFF;
        boolean isUint16  = (flags & 0x01) != 0;
        boolean hasEnergy = (flags & 0x08) != 0;
        boolean hasRR     = (flags & 0x10) != 0;
        int offset = 1;
        int heartRate;
        if (isUint16) {
            heartRate = (hrData[offset] & 0xFF) | ((hrData[offset + 1] & 0xFF) << 8);
            offset += 2;
        } else {
            heartRate = hrData[offset] & 0xFF;
            offset += 1;
        }
        if (hasEnergy) offset += 2;
        if (hasRR) {
            while (offset + 1 < hrData.length) {
                int rrRaw = (hrData[offset] & 0xFF) | ((hrData[offset + 1] & 0xFF) << 8);
                offset += 2;
                double rrMs = (rrRaw / 1024.0) * 1000.0;
                Log.d("RR", "RR Interval: " + rrMs + " ms | HR: " + heartRate);
                rrBuffer.add(rrMs);
                sessionRrBuffer.add(rrMs);
                Log.d("RR_BUFFER", "RR tampon boyutu: " + rrBuffer.size());
                synchronized (rrTimestampBuffer) {
                    rrTimestampBuffer.add(new double[]{System.currentTimeMillis(), rrMs});
                    if (rrTimestampBuffer.size() > 20) rrTimestampBuffer.remove(0);
                }
                if (csvWriter != null && hasRR) {
                    try {
                        csvWriter.write(currentSubjectId + "," +
                            currentSessionId + "," +
                            currentActivity + "," +
                            System.currentTimeMillis() + "," +
                            heartRate + "," +
                            rrMs + "\n");
                        csvWriter.flush();
                    } catch (IOException e) {
                        Log.e("CSV", "RR yazma hatasi: " + e.getMessage());
                    }
                }
            }
        } else {
            Log.w("BLE", "RR interval yok (flags=" + flags + ")");
        }
    }
    public List<Double> getRrBuffer() {
        return rrBuffer;
    }
    public List<Double> getSessionRrBuffer() {
        return sessionRrBuffer;
    }
    public void resetSessionRrBuffer() {
        sessionRrBuffer = Collections.synchronizedList(new ArrayList<>());
    }
    private float[][][] interpolateRrToSignal(ArrayList<Double> rr) {
        float[][][] signal = new float[1][320][1];
        if (rr == null || rr.size() < 2) return signal;

        double[] cumTime = new double[rr.size() + 1];
        cumTime[0] = 0;
        for (int i = 0; i < rr.size(); i++) {
            cumTime[i + 1] = cumTime[i] + rr.get(i);
        }

        double totalTime = cumTime[cumTime.length - 1];
        if (totalTime == 0) return signal;

        double step = totalTime / 320.0;
        Log.d("INTERPOLATE", "RR: " + rr.size() + " deger | Sure: " +
                String.format(Locale.getDefault(), "%.0f", totalTime) + "ms | Step: " +
                String.format(Locale.getDefault(), "%.1f", step) + "ms");

        float[] raw = new float[320];
        for (int i = 0; i < 320; i++) {
            double t = i * step;
            double val = 0;
            for (int j = 0; j < cumTime.length - 1; j++) {
                if (t >= cumTime[j] && t < cumTime[j + 1]) {
                    val = rr.get(Math.min(j, rr.size() - 1)) / 1000.0;
                    break;
                }
            }
            raw[i] = (float) val;
        }

        // Winsorization: %5 ve %95
        float[] sorted = raw.clone();
        Arrays.sort(sorted);
        float lower = sorted[(int) (sorted.length * 0.05f)];
        float upper = sorted[(int) (sorted.length * 0.95f)];
        for (int i = 0; i < 320; i++) {
            raw[i] = Math.max(lower, Math.min(upper, raw[i]));
        }

        final float SCALER_MEAN = 0.26859059f;
        final float SCALER_STD = 33.30758689f;

        Log.d("INTERPOLATE", "Normalizasyon - mean: " +
                String.format(Locale.getDefault(), "%.4f", SCALER_MEAN));

        for (int i = 0; i < 320; i++) {
            signal[0][i][0] = (raw[i] - SCALER_MEAN) / SCALER_STD;
        }

        return signal;
    }

    private float[][][] interpolateRrToSyntheticPPG(ArrayList<Double> rr) {
        float[][][] signal = new float[1][320][1];
        if (rr == null || rr.size() < 2) return signal;

        double[] cumTime = new double[rr.size() + 1];
        cumTime[0] = 0;
        for (int i = 0; i < rr.size(); i++) {
            cumTime[i + 1] = cumTime[i] + rr.get(i);
        }

        double totalTime = cumTime[cumTime.length - 1];
        if (totalTime == 0) return signal;

        double step = totalTime / 320.0;
        Log.d("SYNTHETIC_PPG", "Sentetik PPG uretildi: step=" + step + "ms");

        for (int i = 0; i < 320; i++) {
            double t = i * step;
            double val = 0;
            for (int j = 0; j < rr.size(); j++) {
                double rrDuration = rr.get(j);
                double peakTime = cumTime[j] + 0.35 * rrDuration;
                double sigma = 0.15 * rrDuration;
                if (sigma < 0.0001) continue;
                double diff = t - peakTime;
                val += Math.exp(-0.5 * (diff / sigma) * (diff / sigma));
            }
            signal[0][i][0] = (float) val;
        }

        return signal;
    }


    private void logHrvMetrics(ArrayList<Double> rr) {
        if (rr == null || rr.size() < 2) return;

        double sum = 0;
        for (double v : rr) sum += v;
        double mean = sum / rr.size();

        double varSum = 0;
        for (double v : rr) varSum += (v - mean) * (v - mean);
        double sdnn = Math.sqrt(varSum / rr.size());

        double diffSqSum = 0;
        int pnn50Count = 0;
        int diffCount = rr.size() - 1;
        for (int i = 1; i < rr.size(); i++) {
            double diff = rr.get(i) - rr.get(i - 1);
            diffSqSum += diff * diff;
            if (Math.abs(diff) > 50) pnn50Count++;
        }
        double rmssd = Math.sqrt(diffSqSum / diffCount);
        double pnn50 = (pnn50Count / (double) diffCount) * 100.0;

        Log.d("HRV_METRICS", "SDNN=" + sdnn + "ms | RMSSD=" + rmssd + "ms | pNN50=" + pnn50 + "%");
    }

    public void saveTemplate(String userName, float[] embedding) {
        Toast.makeText(this, "Model henüz hazır değil", Toast.LENGTH_LONG).show();
    }

    private void appendToAuthLog(String content) {
        try {
            File logFile = new File(getFilesDir(), "auth_log.txt");
            java.io.FileWriter logWriter = new java.io.FileWriter(logFile, true);
            logWriter.write(content);
            logWriter.close();
        } catch (Exception e) {
            Log.e("AUTH_LOG", "Log yazma hatasi: " + e.getMessage());
        }
    }

    public float[] loadTemplate(String userName) {
        try {
            File file = new File(getFilesDir(), "templates.json");
            if (!file.exists()) return null;
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            String content = sb.toString();
            org.json.JSONObject templates = new org.json.JSONObject(content);
            if (!templates.has(userName)) return null;
            Object value = templates.get(userName);
            org.json.JSONArray arr = (value instanceof org.json.JSONObject)
                    ? ((org.json.JSONObject) value).getJSONArray("embedding")
                    : templates.getJSONArray(userName);
            float[] embedding = new float[arr.length()];
            for (int i = 0; i < arr.length(); i++) {
                embedding[i] = (float) arr.getDouble(i);
            }
            Log.d("TEMPLATE", userName + " yuklendi");
            return embedding;
        } catch (Exception e) {
            Log.e("TEMPLATE", "Yukleme hatasi: " + e.getMessage());
            return null;
        }
    }

    private org.json.JSONObject readJsonFile(String fileName) {
        try {
            File file = new File(getFilesDir(), fileName);
            if (!file.exists()) return new org.json.JSONObject();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new org.json.JSONObject(sb.toString());
        } catch (Exception e) {
            Log.e("TEMPLATE", "Okuma hatasi (" + fileName + "): " + e.getMessage());
            return new org.json.JSONObject();
        }
    }

    public List<String> getTemplateUsers() {
        List<String> users = new ArrayList<>();
        try {
            org.json.JSONObject subjects = readJsonFile("subjects.json");
            org.json.JSONObject templates = readJsonFile("templates.json");
            java.util.Iterator<String> keys = templates.keys();
            while (keys.hasNext()) {
                String userName = keys.next();
                if (subjects.has(userName)) users.add(userName);
            }
        } catch (Exception e) {
            Log.e("TEMPLATE", "Liste alinamadi: " + e.getMessage());
        }
        return users;
    }

    public float authenticate(String claimPerson, String probePerson) {
        Log.d("AUTH", "Model henüz entegre edilmedi");
        return -1f;
    }

    public String getOrCreateSubjectId(String userName) {
        try {
            File file = new File(getFilesDir(), "subjects.json");
            org.json.JSONObject subjects = new org.json.JSONObject();
            if (file.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                subjects = new org.json.JSONObject(sb.toString());
            }
            if (subjects.has(userName)) {
                currentSubjectId = subjects.getString(userName);
                Log.d("SUBJECT", userName + " mevcut ID: " + currentSubjectId);
                return currentSubjectId;
            }
            int nextId = subjects.length() + 1;
            currentSubjectId = String.format(Locale.getDefault(), "S%03d", nextId);
            subjects.put(userName, currentSubjectId);
            java.io.FileWriter fw = new java.io.FileWriter(file);
            fw.write(subjects.toString());
            fw.close();
            Log.d("SUBJECT", userName + " yeni ID atandi: " + currentSubjectId);
            return currentSubjectId;
        } catch (Exception e) {
            Log.e("SUBJECT", "Subject ID hatasi: " + e.getMessage());
            currentSubjectId = "S000";
            return currentSubjectId;
        }
    }

    public int getNextSessionNumber(String subjectId) {
        File dir = getFilesDir();
        File[] files = dir.listFiles((d, name) -> name.startsWith(subjectId + "_session"));
        currentSessionId = (files != null ? files.length : 0) + 1;
        Log.d("SUBJECT", subjectId + " session numarasi: " + currentSessionId);
        return currentSessionId;
    }

    public void setCurrentActivity(String activity) {
        this.currentActivity = activity;
    }

}