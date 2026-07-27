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
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements RhythmSDKScanningCallback, RhythmSDKDeviceCallback, RhythmSDKFitFileCallback, ScannedDeviceFragment.OnListFragmentInteractionListener {

    private ScoscheSDK24 sdk;
    private String fileName;
    private byte[] data;
    private boolean isRhythm24;
    private Interpreter tfliteInterpreter;
    private BluetoothGatt bleGatt;
    private String currentSubjectId = "";
    private int currentSessionId = 1;
    private static final UUID HR_SERVICE_UUID     = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb");
    private static final UUID HR_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CONFIG_UUID  = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final ArrayList<long[]> bpmBuffer = new ArrayList<>();
    private final List<Double> rrBuffer = Collections.synchronizedList(new ArrayList<>());
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
        try {
            tfliteInterpreter = new Interpreter(loadModelFile(), getTfliteOptions());
            Log.d("TFLITE", "Model basariyla yuklendi");
        } catch (Exception e) {
            Log.e("TFLITE", "Model yuklenemedi: " + e.getMessage());
        }
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
                double matchedRr = getClosestRr(timestamp, bpmValue);
                csvWriter.write(currentSubjectId + "," + currentSessionId + "," + currentActivity + "," + timestamp + "," + bpmValue + "," + (matchedRr > 0 ? matchedRr : "") + "\n");                csvWriter.flush();
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
        return false;
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

    @SuppressWarnings("unused")
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
                        " ms | localAvg=" + String.format(Locale.getDefault(), "%.1f", localAvg) + " ms");
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

    private MappedByteBuffer loadModelFile() throws IOException {
        try (AssetFileDescriptor fileDescriptor = getAssets().openFd("ppg_biometric_embedding.tflite");
             FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor())) {
            FileChannel fileChannel = inputStream.getChannel();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.getStartOffset(), fileDescriptor.getDeclaredLength());
        }
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
                Log.d("RR_BUFFER", "RR tampon boyutu: " + rrBuffer.size());
                synchronized (rrTimestampBuffer) {
                    rrTimestampBuffer.add(new double[]{System.currentTimeMillis(), rrMs});
                    if (rrTimestampBuffer.size() > 20) rrTimestampBuffer.remove(0);
                }
            }
        } else {
            Log.w("BLE", "RR interval yok (flags=" + flags + ")");
        }
    }
    public List<Double> getRrBuffer() {
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

        // ESKİ - Winsorization: %5 ve %95
        // YENİ - sabit fizyolojik sınırlar (40-150 BPM arası):
        float lowerFixed = 0.40f;  // 150 BPM
        float upperFixed = 1.50f;  // 40 BPM
        for (int i = 0; i < 320; i++) {
            raw[i] = Math.max(lowerFixed, Math.min(upperFixed, raw[i]));
        }

        float sum = 0;
        for (float v : raw) sum += v;
        float rrMean = sum / raw.length;

        float varSum = 0;
        for (float v : raw) varSum += (v - rrMean) * (v - rrMean);
        float rrStd = (float) Math.sqrt(varSum / raw.length);
        if (rrStd < 0.001f) rrStd = 0.001f;

        Log.d("INTERPOLATE", "Normalizasyon - mean: " +
                String.format(Locale.getDefault(), "%.4f", rrMean));

        for (int i = 0; i < 320; i++) {
            signal[0][i][0] = (raw[i] - rrMean) / rrMean;
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


    public float[] extractEmbeddingFromRr() {
        ArrayList<Double> rrSnapshot;
        synchronized (rrBuffer) {
            if (tfliteInterpreter == null || rrBuffer.size() < 5) {
                Log.e("TFLITE", "Model yuklu degil veya yeterli RR yok");
                return null;
            }
            rrSnapshot = new ArrayList<>(rrBuffer);
        }
        // filterRrNoise threshold Yekta Hoca ile netlesecek - gecici devre disi
        // Alternatif: interpolateRrToSyntheticPPG(rrSnapshot) — Yekta Hoca onayı bekleniyor
        float[][][] input = interpolateRrToSignal(rrSnapshot);
        float[][][] syntheticInput = interpolateRrToSyntheticPPG(rrSnapshot);
        Log.d("SYNTHETIC_PPG", "Sentetik PPG uretildi, boyut: " + syntheticInput[0].length);
        float[][] output = new float[1][16];
        try {
            tfliteInterpreter.run(input, output);
            Log.d("TFLITE", "Embedding cikarildi: " + Arrays.toString(output[0]));
            logHrvMetrics(rrSnapshot);
            return output[0];
        } catch (Exception e) {
            Log.e("TFLITE", "Embedding hatasi: " + e.getMessage());
            return null;
        }
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
        try {
            File file = new File(getFilesDir(), "templates.json");
            org.json.JSONObject templates = new org.json.JSONObject();
            if (file.exists()) {
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                templates = new org.json.JSONObject(sb.toString());
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (float v : embedding) arr.put(v);
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("embedding", arr);
            String savedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new java.util.Date());
            entry.put("savedAt", savedAt);
            templates.put(userName, entry);
            java.io.FileWriter fw = new java.io.FileWriter(file);
            fw.write(templates.toString());
            fw.close();
            Log.d("TEMPLATE", userName + " kaydedildi");

            StringBuilder sb2 = new StringBuilder();
            sb2.append("=== TEMPLATE ===\n");
            sb2.append("Tarih:     ").append(savedAt).append("\n");
            sb2.append("Kisi:      ").append(userName).append(" (").append(currentSubjectId).append(")\n");
            sb2.append("Aktivite:  ").append(currentActivity).append("\n");
            sb2.append("Embedding: ").append(Arrays.toString(embedding)).append("\n\n");
            appendToAuthLog(sb2.toString());
        } catch (Exception e) {
            Log.e("TEMPLATE", "Kayit hatasi: " + e.getMessage());
        }
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

    public float authenticate(String userName) {
        float[] stored = loadTemplate(userName);
        if (stored == null) {
            Log.w("AUTH", userName + " icin template bulunamadi");
            return -1f;
        }
        float[] current = extractEmbeddingFromRr();
        if (current == null) return -1f;
        float similarity = cosineSimilarity(stored, current);
        Log.d("AUTH", userName + " benzerlik: " + similarity);

        boolean accepted = similarity >= 0.75f;
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new java.util.Date());
        StringBuilder sb = new StringBuilder();
        sb.append(accepted ? "=== AUTH ✓ ACCEPTED ===\n" : "=== AUTH ✗ REJECTED ===\n");
        sb.append("Tarih:     ").append(timestamp).append("\n");
        sb.append("Kisi:      ").append(userName).append(" (").append(currentSubjectId).append(")\n");
        sb.append("Template:  ").append(userName).append("\n");
        sb.append("Similarity: ").append(String.format(Locale.getDefault(), "%.4f", similarity)).append("\n");
        sb.append("Embedding: ").append(Arrays.toString(current)).append("\n\n");
        appendToAuthLog(sb.toString());

        return similarity;
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