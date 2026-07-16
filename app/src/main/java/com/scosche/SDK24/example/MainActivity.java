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
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.flex.FlexDelegate;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import android.content.res.AssetFileDescriptor;
import java.io.FileWriter;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.file.Files;

public class MainActivity extends AppCompatActivity implements RhythmSDKScanningCallback, RhythmSDKDeviceCallback, RhythmSDKFitFileCallback, ScannedDeviceFragment.OnListFragmentInteractionListener {

    private ScoscheSDK24 sdk;
    private String fileName;
    private byte[] data;
    private boolean isRhythm24;
    private Interpreter tfliteInterpreter;
    private BluetoothGatt bleGatt;
    private static final UUID HR_SERVICE_UUID     = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG_UUID  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private ArrayList<long[]> bpmBuffer = new ArrayList<>();
    private ArrayList<Double> rrBuffer = new ArrayList<>();
    private final ArrayList<double[]> rrTimestampBuffer = new ArrayList<>();

    public ScoscheSDK24 getSdk() {
        return sdk;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.scosche.sdk24.example.R.layout.activity_main);
        Log.d("MainActivity", "onCreate: Baslatildi");
        checkPermissions();
        sdk = new ScoscheSDK24(this);
        try {
            tfliteInterpreter = new Interpreter(loadModelFile(), getTfliteOptions());
            Log.d("TFLITE", "Model basariyla yuklendi");
        } catch (Exception e) {
            Log.e("TFLITE", "Model yuklenemedi: " + e.getMessage());
        }
        Log.d("MainActivity", "onCreate: SDK baslatildi");
        try {
            Fragment fragment = ScannedDeviceFragment.class.newInstance();
            getSupportFragmentManager().beginTransaction().replace(com.scosche.sdk24.example.R.id.flContent, fragment, "ScannedDeviceFragment").commit();
            Log.d("MainActivity", "onCreate: ScannedDeviceFragment yuklendi");
        } catch (Exception e) {
            e.printStackTrace();
        }
        sdk.startScan(this);
    }

    private FileWriter csvWriter;

    public void setCsvWriter(FileWriter writer) {
        this.csvWriter = writer;
    }

    public FileWriter getCsvWriter() {
        return csvWriter;
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
                    Fragment rhythm24Fragment = Rhythm24Fragment.class.newInstance();
                    getSupportFragmentManager().beginTransaction().replace(com.scosche.sdk24.example.R.id.flContent, rhythm24Fragment, "Rhythm24Fragment").commit();
                    isRhythm24 = true;
                    sdk.updateSportMode(255);
                    rrBuffer.clear();
                    rrTimestampBuffer.clear();
                    Log.d("HRV", "HRV modu aktif edildi");
                    runTfliteTest();
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
                    Fragment rhythmPlusFragment = RhythmPlusFragment.class.newInstance();
                    getSupportFragmentManager().beginTransaction().replace(R.id.flContent, rhythmPlusFragment, "RhythmPlusFragment").commit();
                    isRhythm24 = false;
                    break;
                default:
                    Toast.makeText(this, "Invalid device type.", Toast.LENGTH_LONG).show();
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void updateHeartRate(String heartRate) {
        int bpmValue = Integer.parseInt(heartRate);
        if (isBpmNoise(bpmValue)) return;
        long timestamp = System.currentTimeMillis();
        bpmBuffer.add(new long[]{timestamp, bpmValue});
        if (csvWriter != null) {
            try {
                double matchedRr = getClosestRr(timestamp, bpmValue);
                csvWriter.write(timestamp + "," + bpmValue + "," + (matchedRr > 0 ? matchedRr : "") + "\n");
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                if (f != null) {
                    ((Rhythm24Fragment) f).updateHeartRate(heartRate);
                }
            }
        });
    }

    private boolean isBpmNoise(int newBpm) {
        return false;
    }

    private double getClosestRr(long timestamp, int bpm) {
        double closest = 0;
        long minDiff = Long.MAX_VALUE;
        int closestIndex = -1;
        for (int i = 0; i < rrTimestampBuffer.size(); i++) {
            double[] entry = rrTimestampBuffer.get(i);
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
        return closest;
    }

    private ArrayList<Double> filterRrNoise(ArrayList<Double> rr) {
        if (rr == null || rr.size() < 3) return rr;

        ArrayList<Double> filtered = new ArrayList<>();
        int window = 5;          // Yekta Hoca: local average pencere boyutu
        double threshold = 100;  // Gecici: Yekta Hoca Cuma'da netlestirecek (ms mi, % mi, BPM mi?)

        for (int i = 0; i < rr.size(); i++) {
            int start = Math.max(0, i - window / 2);
            int end   = Math.min(rr.size(), start + window);
            if (end - start < window) start = Math.max(0, end - window);

            double sum = 0;
            int count = 0;
            for (int j = start; j < end; j++) {
                if (j != i) { sum += rr.get(j); count++; }
            }
            if (count == 0) { filtered.add(rr.get(i)); continue; }

            double localAvg = sum / count;
            if (Math.abs(rr.get(i) - localAvg) <= threshold) {
                filtered.add(rr.get(i));
            } else {
                Log.d("RR_FILTER", "Noise atildi: " + rr.get(i) +
                        " ms | localAvg=" + String.format("%.1f", localAvg) + " ms");
            }
        }
        Log.d("RR_FILTER", "Orijinal: " + rr.size() + " -> Filtrelenmis: " + filtered.size());
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isRhythm24) {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                    if (f != null) ((Rhythm24Fragment) f).updateHeartRate("???");
                } else {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
                    if (f != null) ((RhythmPlusFragment) f).updateHeartRate("???");
                }
            }
        });
    }

    @Override
    public void updateBatteryLevel(int batteryLevel) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isRhythm24) {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                    if (f != null) ((Rhythm24Fragment) f).updateBattery(batteryLevel);
                } else {
                    Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
                    if (f != null) ((RhythmPlusFragment) f).updateBattery(batteryLevel);
                }
            }
        });
    }

    @Override
    public void updateZone(int zone) {}

    public void onSignalQuality(int quality) {
        Log.d("SIGNAL", "Sinyal kalitesi: " + quality);
    }

    @Override
    public void updateSportMode(int sportMode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Fragment f = getSupportFragmentManager().findFragmentByTag("Rhythm24Fragment");
                if (f != null) ((Rhythm24Fragment) f).updateSportMode(sportMode);
            }
        });
    }

    @Override
    public void updateFirmwareVersion(String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Fragment f = getSupportFragmentManager().findFragmentByTag("RhythmPlusFragment");
                if (f != null) ((RhythmPlusFragment) f).updateFirmwareVersion(value);
            }
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
    public void fitFileDownloadComplete(byte[] data, String fileName) {
        this.fileName = fileName;
        this.data = data;
        saveFile();
    }

    @Override
    public void fitFileDeleteComplete(String fileName) {
        getSdk().clearFiles();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "File deleted: " + fileName, Toast.LENGTH_LONG).show();
            }
        });
        getSdk().getFitFiles();
    }

    @Override
    public void downloadProgressUpdate(int percent, FitFileContent.FitFileInfo file) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Fragment f = getSupportFragmentManager().findFragmentByTag("FitFilesFragment");
                if (f != null) ((FitFilesFragment) f).getAdapter().test(percent);
            }
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
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == 23 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveFile();
        }
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("ppg_biometric_embedding.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.getStartOffset(), fileDescriptor.getDeclaredLength());
    }

    private Interpreter.Options getTfliteOptions() {
        Interpreter.Options options = new Interpreter.Options();
        options.addDelegate(new FlexDelegate());
        return options;
    }

    private void runTfliteTest() {
        if (tfliteInterpreter == null) {
            Log.e("TFLITE", "Model yuklu degil");
            return;
        }

        float[][][] input1 = new float[1][320][1];
        float[][][] input2 = new float[1][320][1];
        for (int i = 0; i < 320; i++) {
            input1[0][i][0] = 0.5f;
            input2[0][i][0] = 0.6f;
        }

        float[][] output1 = new float[1][16];
        float[][] output2 = new float[1][16];

        try {
            tfliteInterpreter.run(input1, output1);
            tfliteInterpreter.run(input2, output2);
            float similarity = cosineSimilarity(output1[0], output2[0]);
            Log.d("TFLITE", "Embedding1: " + Arrays.toString(output1[0]));
            Log.d("TFLITE", "Embedding2: " + Arrays.toString(output2[0]));
            Log.d("TFLITE", "Cosine Similarity: " + similarity);
        } catch (Exception e) {
            Log.e("TFLITE", "Inference hatasi: " + e.getMessage());
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

    private void parseHrMeasurement(byte[] data) {
        if (data == null || data.length < 2) return;
        int flags = data[0] & 0xFF;
        boolean isUint16  = (flags & 0x01) != 0;
        boolean hasEnergy = (flags & 0x08) != 0;
        boolean hasRR     = (flags & 0x10) != 0;
        int offset = 1;
        int heartRate;
        if (isUint16) {
            heartRate = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
            offset += 2;
        } else {
            heartRate = data[offset] & 0xFF;
            offset += 1;
        }
        if (hasEnergy) offset += 2;
        if (hasRR) {
            while (offset + 1 < data.length) {
                int rrRaw = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                offset += 2;
                double rrMs = (rrRaw / 1024.0) * 1000.0;
                Log.d("RR", "RR Interval: " + rrMs + " ms | HR: " + heartRate);
                rrBuffer.add(rrMs);
                Log.d("RR_BUFFER", "RR tampon boyutu: " + rrBuffer.size());
                rrTimestampBuffer.add(new double[]{System.currentTimeMillis(), rrMs});
                if (rrTimestampBuffer.size() > 20) rrTimestampBuffer.remove(0);
            }
        } else {
            Log.w("BLE", "RR interval yok (flags=" + flags + ")");
        }
    }

    public ArrayList<Double> getRrBuffer() {
        return rrBuffer;
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

        double windowMs = 5000.0;
        double step = windowMs / 320.0;

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
        java.util.Arrays.sort(sorted);
        float lower = sorted[(int)(320 * 0.05)];
        float upper = sorted[(int)(320 * 0.95)];
        for (int i = 0; i < 320; i++) {
            raw[i] = Math.max(lower, Math.min(upper, raw[i]));
        }

        // StandardScaler: mean=0.2686, std=33.307
        float mean = 0.2685905935296792f;
        float std = 33.307586893002f;
        for (int i = 0; i < 320; i++) {
            signal[0][i][0] = (raw[i] - mean) / std;
        }

        return signal;
    }

    public float[] extractEmbeddingFromRr() {
        if (tfliteInterpreter == null || rrBuffer.size() < 5) {
            Log.e("TFLITE", "Model yuklu degil veya yeterli RR yok");
            return null;
        }
                // filterRrNoise threshold Yekta Hoca ile cuma günü netlesecek - gecici devre disi
        float[][][] input = interpolateRrToSignal(rrBuffer);
        float[][] output = new float[1][16];
        try {
            tfliteInterpreter.run(input, output);
            Log.d("TFLITE", "Embedding cikarildi: " + Arrays.toString(output[0]));
            return output[0];
        } catch (Exception e) {
            Log.e("TFLITE", "Embedding hatasi: " + e.getMessage());
            return null;
        }
    }

    public void saveTemplate(String userName, float[] embedding) {
        try {
            File file = new File(getFilesDir(), "templates.json");
            org.json.JSONObject templates = new org.json.JSONObject();
            if (file.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                String content = sb.toString();
                templates = new org.json.JSONObject(content);
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (float v : embedding) arr.put(v);
            templates.put(userName, arr);
            java.io.FileWriter fw = new java.io.FileWriter(file);
            fw.write(templates.toString());
            fw.close();
            Log.d("TEMPLATE", userName + " kaydedildi");
        } catch (Exception e) {
            Log.e("TEMPLATE", "Kayit hatasi: " + e.getMessage());
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
            org.json.JSONArray arr = templates.getJSONArray(userName);
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

    public boolean authenticate(String userName) {
        float[] stored = loadTemplate(userName);
        if (stored == null) {
            Log.w("AUTH", userName + " icin template bulunamadi");
            return false;
        }
        float[] current = extractEmbeddingFromRr();
        if (current == null) return false;
        float similarity = cosineSimilarity(stored, current);
        Log.d("AUTH", userName + " benzerlik: " + similarity);
        return similarity >= 0.75f;
    }

}