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
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.flex.FlexDelegate;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import android.content.res.AssetFileDescriptor;
import java.io.FileWriter;

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
                    bpmHistory.clear();
                    Log.d("HRV", "HRV modu aktif edildi");
                    try {
                        java.lang.reflect.Field deviceField = rhythmDevice.getClass().getDeclaredField("device");
                        deviceField.setAccessible(true);
                        android.bluetooth.BluetoothDevice btDevice = (android.bluetooth.BluetoothDevice) deviceField.get(rhythmDevice);
                        Log.d("MAC", "Cihaz MAC: " + btDevice.getAddress());
                        connectNativeBle(btDevice);
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
        bpmHistory.add(bpmValue);
        if (bpmHistory.size() > 20) bpmHistory.remove(0);
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

    private final ArrayList<Integer> bpmHistory = new ArrayList<>();

    private boolean isBpmNoise(int newBpm) {
        if (newBpm < 40 || newBpm > 200) return true;
        if (bpmHistory.size() < 3) return false;

        int windowSize = Math.min(5, bpmHistory.size());
        int sum = 0;
        for (int i = bpmHistory.size() - windowSize; i < bpmHistory.size(); i++) {
            Integer val = bpmHistory.get(i);
            if (val == null) continue;
            sum += val;
        }
        double avg = (double) sum / windowSize;
        if (avg == 0) return false;
        double deviation = Math.abs(newBpm - avg) / avg;
        if (deviation > 0.40) {
            Log.w("BPM_FILTER", "Noise: " + newBpm + " BPM (ort: " + avg + ")");
            return true;
        }
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
                // RR'den BPM hesapla, SDK BPM ile karşılaştır
                double rrBpm = 60000.0 / entry[1];
                if (Math.abs(rrBpm - bpm) > 20) continue; // 20 BPM'den fazla fark varsa atla
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
        Fragment f = getSupportFragmentManager().findFragmentByTag("ScannedDeviceFragment");
        if (f != null) ((ScannedDeviceFragment) f).removeDevice(device);
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

    private void connectNativeBle(android.bluetooth.BluetoothDevice btDevice) {
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
}