package com.scosche.SDK24.example;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.scosche.sdk24.Zone;
import com.scosche.sdk24.example.R;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Rhythm24Fragment extends Fragment {

    private enum SportMode {
        BLANK("", -1), HEART_RATE_ONLY("Heart Rate Only", 0), RUNNING("Running", 1), CYCLING("Cycling", 2), SWIMMING("Swimming", 5),
        HRV("Heart Rate Variability", 255), DUATHLON("Duathlon", 253), TRIATHLON("Triathlon", 254);

        public String name;
        public int id;

        SportMode(String name, int id) {
            this.name = name;
            this.id = id;
        }

        public static SportMode fromId(int id) {
            for (SportMode s : SportMode.values()) {
                if (s.id == id) return s;
            }
            return HEART_RATE_ONLY;
        }

        @Override
        public String toString() { return name; }
    }

    private TextView heartRateField, batteryField, recordingStatusField;
    private EditText zoneOneTwoBPM, zoneTwoThreeBPM, zoneThreeFourBPM, zoneFourFiveBPM, userNameField;
    private Button readZonesButton, updateZonesButton, readSportModeButton, updateSportModeButton, viewFitFilesButton;
    private Button startRecordingButton, stopRecordingButton, saveTemplateButton, authenticateButton;
    private Spinner sportModeSpinner;

    private boolean isRecording = false;
    private boolean isDeviceStabilized = false;
    private long sessionStartTime = 0;
    private FileWriter csvWriter;
    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    public Rhythm24Fragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_rhythm24, container, false);

        heartRateField = view.findViewById(R.id.heartRateField);
        batteryField = view.findViewById(R.id.batteryLevelField);
        recordingStatusField = view.findViewById(R.id.recordingStatusField);
        userNameField = view.findViewById(R.id.userNameField);

        sportModeSpinner = view.findViewById(R.id.sportModeSpinner);
        sportModeSpinner.setAdapter(new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, SportMode.values()));
        sportModeSpinner.setSelection(0);

        zoneOneTwoBPM = view.findViewById(R.id.zoneOneTwoBPM);
        zoneTwoThreeBPM = view.findViewById(R.id.zoneTwoThreeBPM);
        zoneThreeFourBPM = view.findViewById(R.id.zoneThreeFourBPM);
        zoneFourFiveBPM = view.findViewById(R.id.zoneFourFiveBPM);

        readZonesButton = view.findViewById(R.id.readZonesButton);
        readZonesButton.setOnClickListener(v -> {
            Zone zone = ((MainActivity) getActivity()).getSdk().getZoneValues();
            if (zone != null) {
                zoneOneTwoBPM.setText(String.valueOf(zone.getZoneOneTwo()));
                zoneTwoThreeBPM.setText(String.valueOf(zone.getZoneTwoThree()));
                zoneThreeFourBPM.setText(String.valueOf(zone.getZoneThreeFour()));
                zoneFourFiveBPM.setText(String.valueOf(zone.getZoneFourFive()));
            }
        });

        updateZonesButton = view.findViewById(R.id.updateZonesButton);
        updateZonesButton.setOnClickListener(v -> {
            String zoneOneTwo = zoneOneTwoBPM.getText().toString();
            String zoneTwoThree = zoneTwoThreeBPM.getText().toString();
            String zoneThreeFour = zoneThreeFourBPM.getText().toString();
            String zoneFourFive = zoneFourFiveBPM.getText().toString();
            if ("".equals(zoneOneTwo) || "".equals(zoneTwoThree) || "".equals(zoneThreeFour) || "".equals(zoneFourFive)) {
                Toast.makeText(getContext(), "Please enter all zone values.", Toast.LENGTH_LONG).show();
            } else {
                short z1 = Short.parseShort(zoneOneTwo), z2 = Short.parseShort(zoneTwoThree);
                short z3 = Short.parseShort(zoneThreeFour), z4 = Short.parseShort(zoneFourFive);
                if (z1 > 250 || z2 > 250 || z3 > 250 || z4 > 250) {
                    Toast.makeText(getContext(), "Please enter valid zone numbers.", Toast.LENGTH_LONG).show();
                } else if (z1 > z2 || z2 > z3 || z3 > z4) {
                    Toast.makeText(getContext(), "Zones must be in order.", Toast.LENGTH_LONG).show();
                } else {
                    ((MainActivity) getActivity()).getSdk().updateZoneValues(new Zone(z1, z2, z3, z4));
                }
            }
        });

        readSportModeButton = view.findViewById(R.id.readSportModeButton);
        readSportModeButton.setOnClickListener(v -> {
            int sportMode = ((MainActivity) getActivity()).getSdk().getSportMode();
            SportMode sportModeEnum = SportMode.fromId(sportMode);
            for (int i = 0; i < sportModeSpinner.getCount(); i++) {
                if (sportModeSpinner.getItemAtPosition(i).equals(sportModeEnum)) {
                    sportModeSpinner.setSelection(i);
                    break;
                }
            }
        });

        updateSportModeButton = view.findViewById(R.id.updateSportModeButton);
        updateSportModeButton.setOnClickListener(v -> {
            int value = ((SportMode) sportModeSpinner.getSelectedItem()).id;
            if (value == -1) {
                Toast.makeText(getContext(), "Please select a sport mode.", Toast.LENGTH_LONG).show();
            } else {
                ((MainActivity) getActivity()).getSdk().updateSportMode(value);
            }
        });

        viewFitFilesButton = view.findViewById(R.id.viewFitFilesButton);
        viewFitFilesButton.setOnClickListener(v -> {
            try {
                ((MainActivity) getActivity()).getSdk().getFitFiles();
                Fragment fragment = FitFilesFragment.class.newInstance();
                getActivity().getSupportFragmentManager().beginTransaction()
                        .replace(R.id.flContent, fragment, "FitFilesFragment")
                        .addToBackStack("FitFilesFragment").commit();
            } catch (Exception e) {
                Log.e("FIT_FILES", "Fragment gecis hatasi: " + e.getMessage());
            }
        });

        startRecordingButton = view.findViewById(R.id.startRecordingButton);
        stopRecordingButton = view.findViewById(R.id.stopRecordingButton);
        saveTemplateButton = view.findViewById(R.id.saveTemplateButton);
        authenticateButton = view.findViewById(R.id.authenticateButton);

        startRecordingButton.setEnabled(false);
        startRecordingButton.setOnClickListener(v -> {
            if (!isDeviceStabilized) {
                Toast.makeText(getContext(), "Cihaz stabilize olmadi, lutfen bekleyin.", Toast.LENGTH_SHORT).show();
                return;
            }
            startRecording();
        });

        stopRecordingButton.setOnClickListener(v -> stopRecording());

        saveTemplateButton.setOnClickListener(v -> {
            String userName = userNameField.getText().toString().trim();
            if (userName.isEmpty()) {
                Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
                return;
            }
            float[] embedding = ((MainActivity) getActivity()).extractEmbeddingFromRr();
            if (embedding == null) {
                Toast.makeText(getContext(), "Yeterli RR verisi yok.", Toast.LENGTH_SHORT).show();
                return;
            }
            ((MainActivity) getActivity()).saveTemplate(userName, embedding);
            Toast.makeText(getContext(), userName + " template kaydedildi.", Toast.LENGTH_SHORT).show();
            recordingStatusField.setText("Template kaydedildi: " + userName);
        });

        authenticateButton.setOnClickListener(v -> {
            String userName = userNameField.getText().toString().trim();
            if (userName.isEmpty()) {
                Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
                return;
            }
            int rrCount = ((MainActivity) getActivity()).getRrBuffer().size();
            if (rrCount < 50) {
                Toast.makeText(getContext(),
                        "Yeterli veri yok: " + rrCount + "/50 RR. Lutfen bekleyin.",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            boolean result = ((MainActivity) getActivity()).authenticate(userName);
            ((MainActivity) getActivity()).getRrBuffer().clear();
            Log.d("RR_BUFFER", "Authentication yapildi, rrBuffer temizlendi.");
            if (result) {
                recordingStatusField.setText("Dogrulandi: " + userName);
                Toast.makeText(getContext(), userName + " dogrulandi!", Toast.LENGTH_SHORT).show();
            } else {
                recordingStatusField.setText("Dogrulanamadi: " + userName);
                Toast.makeText(getContext(), userName + " dogrulanamadi.", Toast.LENGTH_SHORT).show();
            }
        });

        return view;
    }

    public void startStabilizationCountdown() {
        isDeviceStabilized = false;
        startRecordingButton.setEnabled(false);
        saveTemplateButton.setEnabled(false);
        authenticateButton.setEnabled(false);
        recordingStatusField.setText("Cihaz stabilize oluyor...");
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            int secondsLeft = 15;
            @Override
            public void run() {
                if (secondsLeft > 0) {
                    recordingStatusField.setText("Stabilizasyon: " + secondsLeft + " saniye...");
                    secondsLeft--;
                    new Handler(Looper.getMainLooper()).postDelayed(this, 1000);
                } else {
                    waitForRr();
                }
            }
        }, 1000);
    }

    private void waitForRr() {
        if (getActivity() == null) return;
        MainActivity main = (MainActivity) getActivity();

        if (main.getRrBuffer().size() > 0) {
            isDeviceStabilized = true;
            startRecordingButton.setEnabled(true);
            authenticateButton.setEnabled(true);
            recordingStatusField.setText("Cihaz hazir. Kaydi baslatin.");
            Log.d("STABILIZE", "RR verisi alindi, cihaz hazir.");
        } else {
            recordingStatusField.setText("RR bekleniyor...");
            new Handler(Looper.getMainLooper()).postDelayed(this::waitForRr, 1000);
        }
    }

    private void startRecording() {
        String userName = userNameField.getText().toString().trim();
        if (userName.isEmpty()) {
            Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String subjectId = ((MainActivity) getActivity()).getOrCreateSubjectId(userName);
            int sessionNum = ((MainActivity) getActivity()).getNextSessionNumber(subjectId);
            String fileName = subjectId + "_session" + sessionNum + "_" + timestamp + ".csv";
            File file = new File(getActivity().getFilesDir(), fileName);
            csvWriter = new FileWriter(file, true);
            csvWriter.write("subject_id,session_id,timestamp,bpm,rr_ms\n");

            isRecording = true;
            sessionStartTime = System.currentTimeMillis();
            startRecordingButton.setEnabled(false);
            stopRecordingButton.setEnabled(true);
            saveTemplateButton.setEnabled(false);
            authenticateButton.setEnabled(false);

            ((MainActivity) getActivity()).getRrBuffer().clear();

            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        long elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000;
                        String status = String.format(Locale.getDefault(),
                                "Kayit: %02d:%02d | Dosya: %s", elapsed / 60, elapsed % 60, fileName);
                        recordingStatusField.setText(status);
                        timerHandler.postDelayed(this, 1000);
                    }
                }
            };
            timerHandler.post(timerRunnable);

            ((MainActivity) getActivity()).setCsvWriter(csvWriter);

            Log.d("KAYIT", "Dosya olusturuldu: " + file.getAbsolutePath());
            Toast.makeText(getContext(), "Kayit basladi: " + fileName, Toast.LENGTH_SHORT).show();

        } catch (IOException e) {
            Toast.makeText(getContext(), "Dosya olusturulamadi: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void stopRecording() {
        isRecording = false;
        timerHandler.removeCallbacks(timerRunnable);
        startRecordingButton.setEnabled(true);
        stopRecordingButton.setEnabled(false);
        saveTemplateButton.setEnabled(true);
        authenticateButton.setEnabled(true);

        ((MainActivity) getActivity()).setCsvWriter(null);

        try {
            if (csvWriter != null) {
                csvWriter.flush();
                csvWriter.close();
                csvWriter = null;
            }
        } catch (IOException e) {
            Log.e("KAYIT", "CSV kapatma hatasi: " + e.getMessage());
        }

        recordingStatusField.setText("Kayit tamamlandi.");
        Toast.makeText(getContext(), "Kayit durduruldu.", Toast.LENGTH_SHORT).show();
    }

    public void updateHeartRate(String heartRate) {
        heartRateField.setText(heartRate);
    }

    public void updateBattery(int batteryLevel) {
        batteryField.setText(String.valueOf(batteryLevel));
    }

    public void updateZone(int zone) {}

    public void updateSportMode(int sportMode) {}
}