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
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

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

import static android.os.Environment.DIRECTORY_DOWNLOADS;

public class MainActivity extends AppCompatActivity implements RhythmSDKScanningCallback, RhythmSDKDeviceCallback, RhythmSDKFitFileCallback, ScannedDeviceFragment.OnListFragmentInteractionListener {

    private ScoscheSDK24 sdk;
    private String fileName;
    private byte[] data;
    private boolean isRhythm24;
    private Interpreter tfliteInterpreter;

    public ScoscheSDK24 getSdk() {
        return sdk;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.scosche.sdk24.example.R.layout.activity_main);

        Log.d("MainActivity", "onCreate: Başlatıldı");

        checkPermissions();

        sdk = new ScoscheSDK24(this);

        try {
            tfliteInterpreter = new Interpreter(loadModelFile(), getTfliteOptions());
            Log.d("TFLITE", "Model basariyla yuklendi");
        } catch (Exception e) {
            Log.e("TFLITE", "Model yuklenemedi: " + e.getMessage());
        }

        Log.d("MainActivity", "onCreate: SDK başlatıldı");

        Fragment fragment = null;
        try {
            fragment = ScannedDeviceFragment.class.newInstance();
            getSupportFragmentManager().beginTransaction().replace(com.scosche.sdk24.example.R.id.flContent, fragment, "ScannedDeviceFragment").commit();
            Log.d("MainActivity", "onCreate: ScannedDeviceFragment yüklendi");
        } catch (Exception e) {
            e.printStackTrace();
        }

        sdk.startScan(this);
    }

    private void checkPermissions() {
        // Konum izinlerini kontrol et
        Log.d("MainActivity", "checkPermissions: İzinler kontrol ediliyor");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d("MainActivity", "checkPermissions: Konum izni verilmedi");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        } else {
            Log.d("MainActivity", "checkPermissions: Konum izni mevcut");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                Log.d("MainActivity", "checkPermissions: Bluetooth izinleri verilmedi");
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 2);
            } else {
                Log.d("MainActivity", "checkPermissions: Bluetooth izinleri mevcut");
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
            Log.d("MainActivity", "deviceFound: Cihaz ScannedDeviceFragment'e eklendi");
        } else {
            Log.d("MainActivity", "deviceFound: Cihaz adı null veya fragment bulunamadı");
        }
    }

    @Override
    public void deviceConnected(RhythmDevice rhythmDevice) {

        //forward to appropriate screen
        try {
            switch (rhythmDevice.deviceModel) {
                case RHYTHM_24:
                    Fragment rhythm24Fragment = Rhythm24Fragment.class.newInstance();
                    getSupportFragmentManager().beginTransaction().replace(com.scosche.sdk24.example.R.id.flContent, rhythm24Fragment, "Rhythm24Fragment").commit();
                    isRhythm24 = true;
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

    // Rhythm24'ten gelen BPM verilerini biriktiren tampon.
    // Her eleman [timestamp (ms), bpmDegeri] formatinda bir dizi.
    // Yeterli veri birikince TFLite modeline beslenecek.
    private ArrayList<long[]> bpmBuffer = new ArrayList<>();

    @Override
    public void updateHeartRate(String heartRate) {

        // SDK'dan gelen BPM String'ini sayiya cevir
        int bpmValue = Integer.parseInt(heartRate);

        // Verinin hangi anda geldigini kaydet
        // ML modeli icin zamansal desen onemli
        long timestamp = System.currentTimeMillis();

        // [timestamp, bpm] cifti olarak tampona ekle
        bpmBuffer.add(new long[]{timestamp, bpmValue});

        // Tampon 20 saniye doldu mu kontrol et
        if (bpmBuffer.size() >= 2) {
            long firstTimestamp = bpmBuffer.get(0)[0];
            long lastTimestamp = bpmBuffer.get(bpmBuffer.size() - 1)[0];
            long elapsed = lastTimestamp - firstTimestamp;

            if (elapsed >= 20000) { // 20 saniye = 20000 milisaniye
                Log.d("BPM_WINDOW", "20 saniyelik pencere tamamlandi. Veri sayisi: " + bpmBuffer.size());
                // TODO: buraya TFLite modeline veri gonderme kodu gelecek
                bpmBuffer.clear(); // tamponu temizle, yeni pencere baslat
            }
        }

        // Logcat'te veri akisini dogrula — cihaz baglandiginda buradan test ederiz
        Log.d("BPM_BUFFER", timestamp + " -> " + bpmValue + " BPM | Tampon: " + bpmBuffer.size());

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                FragmentManager fragmentManager = getSupportFragmentManager();
                Fragment f = fragmentManager.findFragmentByTag("Rhythm24Fragment");
                if (f != null) {
                    // Ekranda BPM gosterimini guncelle
                    ((Rhythm24Fragment) f).updateHeartRate(heartRate);
                }
            }
        });
    }

    @Override
    public void monitorStateInvalid() {

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                FragmentManager fragmentManager = getSupportFragmentManager();
                //TODO: create method to get active fragment
                if (isRhythm24) {
                    Fragment f = fragmentManager.findFragmentByTag("Rhythm24Fragment");
                    if (f != null) {
                        ((Rhythm24Fragment) f).updateHeartRate("???");
                    }
                } else {
                    Fragment f = fragmentManager.findFragmentByTag("RhythmPlusFragment");
                    if (f != null) {
                        ((RhythmPlusFragment) f).updateHeartRate("???");
                    }
                }
            }
        });
    }

    @Override
    public void updateBatteryLevel(int batteryLevel) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                FragmentManager fragmentManager = getSupportFragmentManager();
                if (isRhythm24) {
                    Fragment f = fragmentManager.findFragmentByTag("Rhythm24Fragment");
                    if (f != null) {
                        ((Rhythm24Fragment) f).updateBattery(batteryLevel);
                    }
                } else {
                    Fragment f = fragmentManager.findFragmentByTag("RhythmPlusFragment");
                    if (f != null) {
                        ((RhythmPlusFragment) f).updateBattery(batteryLevel);
                    }
                }
            }
        });
    }

    @Override
    public void updateZone(int zone) {

    }

    @Override
    public void updateSportMode(int sportMode) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                FragmentManager fragmentManager = getSupportFragmentManager();
                Fragment f = fragmentManager.findFragmentByTag("Rhythm24Fragment");
                if (f != null) {
                    ((Rhythm24Fragment) f).updateSportMode(sportMode);
                }
            }
        });
    }

    @Override
    public void updateFirmwareVersion(String value) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {

                FragmentManager fragmentManager = getSupportFragmentManager();
                Fragment f = fragmentManager.findFragmentByTag("RhythmPlusFragment");
                if (f != null) {
                    ((RhythmPlusFragment) f).updateFirmwareVersion(value);
                }
            }
        });
    }

    @Override
    public void error(ErrorType errorType) {
        Toast.makeText(this, "Error scanning: " + errorType, Toast.LENGTH_LONG).show();

        if (errorType == ErrorType.NO_LOCATION_PERMISSION) {
//            ActivityCompat.requestPermissions((Activity) getApplicationContext(), new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void deviceLost(RhythmDevice device) {

        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment f = fragmentManager.findFragmentByTag("ScannedDeviceFragment");
        if (f != null) {
            ((ScannedDeviceFragment) f).removeDevice(device);
        }
    }

    @Override
    public void fitFilesFound(List<FitFileContent.FitFileInfo> files) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment f = fragmentManager.findFragmentByTag("FitFilesFragment");

        if (f != null) {
            ((FitFilesFragment) f).displayFitFiles(files);
        }
    }

    @Override
    public void fitFileDownloadComplete(byte[] data, String fileName) {

        this.fileName = fileName;
        this.data = data;
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                23);
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
                FragmentManager fragmentManager = getSupportFragmentManager();
                Fragment f = fragmentManager.findFragmentByTag("FitFilesFragment");

                if (f != null) {
                    ((FitFilesFragment) f).getAdapter().test(percent);
                }
            }
        });
    }

    private void saveFile() {
        File directory =
                Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS);

        String filePath = directory + "/" + fileName;
        FileOutputStream outputStream;

        try {
            outputStream = new FileOutputStream(filePath);
            outputStream.write(data);
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        Toast.makeText(getApplicationContext(), "File downloaded: " + fileName, Toast.LENGTH_LONG).show();

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if (requestCode == 23 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveFile();
            Log.d("MainActivity", "onRequestPermissionsResult: İzin verildi, dosya kaydedildi");
        } else {
            Log.d("MainActivity", "onRequestPermissionsResult: İzin verilmedi");
        }
    }
    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("ppg_biometric_embedding.tflite");
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private Interpreter.Options getTfliteOptions() {
        Interpreter.Options options = new Interpreter.Options();
        options.addDelegate(new FlexDelegate());
        return options;
    }
}