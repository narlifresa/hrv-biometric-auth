package com.scosche.SDK24.example;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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
import java.util.List;
import java.util.Locale;

public class Rhythm24Fragment extends Fragment {

    private enum SportMode {
        BLANK("", -1), HEART_RATE_ONLY("Heart Rate Only", 0), RUNNING("Running", 1), CYCLING("Cycling", 2), SWIMMING("Swimming", 5),
        HRV("Heart Rate Variability", 255), DUATHLON("Duathlon", 253), TRIATHLON("Triathlon", 254);

        public final String name;
        public final int id;

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
    private Button startRecordingButton, stopRecordingButton, saveTemplateButton, authenticateButton, manageUsersButton;
    private Spinner sportModeSpinner;

    private boolean isRecording = false;
    private boolean isDeviceStabilized = false;
    private long sessionStartTime = 0;
    private FileWriter csvWriter;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;
    private final Runnable userSwitchCooldownRunnable = () -> {
        if (isRecording) return;
        saveTemplateButton.setEnabled(true);
        authenticateButton.setEnabled(true);
        recordingStatusField.setText("Hazir.");
    };

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
            if (zoneOneTwo.isEmpty() || zoneTwoThree.isEmpty() || zoneThreeFour.isEmpty() || zoneFourFive.isEmpty()) {
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
                Fragment fragment = FitFilesFragment.class.getDeclaredConstructor().newInstance();
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
            String userName = userNameField.getText().toString().trim();
            if (userName.isEmpty()) {
                Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
                return;
            }
            // Aktivite secim dialogu
            String[] activities = {"Dinlenme (rest)", "Yürüyüş (walking)", "Egzersiz sonrasi (post_exercise)", "Ayakta (standing)"};
            String[] activityKeys = {"rest", "walking", "post_exercise", "standing"};
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("Aktivite turunu secin")
                    .setItems(activities, (dialog, which) -> {
                        ((MainActivity) getActivity()).setCurrentActivity(activityKeys[which]);
                        startRecording();
                    })
                    .setCancelable(true)
                    .show();
        });

        stopRecordingButton.setOnClickListener(v -> stopRecording());

        saveTemplateButton.setOnClickListener(v -> {
            android.widget.Toast.makeText(getContext(),
                "TEMPLATE BUTONA BASILDI",
                android.widget.Toast.LENGTH_LONG).show();
            Log.d("TEMPLATE_DEBUG", "adim 0: butona basildi");
            String userName = userNameField.getText().toString().trim();
            if (userName.isEmpty()) {
                Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
                return;
            }
            Log.d("TEMPLATE_DEBUG", "adim 1: sessionRrBuffer aliniyor");
            List<Double> rrList = ((MainActivity) getActivity()).getSessionRrBuffer();

            Log.d("TEMPLATE_DEBUG", "adim 2: double[] donusumu, rrList.size()=" + rrList.size());
            double[] rrArray = new double[rrList.size()];
            for (int i = 0; i < rrArray.length; i++) {
                rrArray[i] = rrList.get(i);
            }

            Log.d("TEMPLATE_DEBUG", "adim 3: HrvFeatureExtractor.computeFeatures cagriliyor");
            double[] features;
            try {
                features = HrvFeatureExtractor.computeFeatures(rrArray);
            } catch (IllegalArgumentException e) {
                Toast.makeText(getContext(), "Yeterli RR verisi yok: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }

            Log.d("TEMPLATE_DEBUG", "adim 5: saveTemplate cagriliyor");
            ((MainActivity) getActivity()).saveTemplate(userName, features);
            Toast.makeText(getContext(), userName + " template kaydedildi.", Toast.LENGTH_SHORT).show();
            recordingStatusField.setText("Template kaydedildi: " + userName);
        });

        authenticateButton.setOnClickListener(v -> {
            String probePerson = userNameField.getText().toString().trim();
            if (probePerson.isEmpty()) {
                Toast.makeText(getContext(), "Lutfen kullanici adi girin.", Toast.LENGTH_SHORT).show();
                return;
            }
            int rrCount = ((MainActivity) getActivity()).getRrBuffer().size();
            authenticateButton.setText("Kimlik Dogrula (" + rrCount + "/50 RR)");
            if (rrCount < 50) {
                Toast.makeText(getContext(),
                        "Yeterli veri yok: " + rrCount + "/50 RR. Lutfen bekleyin.",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            List<String> templateUsers = ((MainActivity) getActivity()).getTemplateUsers();
            if (templateUsers.isEmpty()) {
                Toast.makeText(getContext(), "Kayitli template yok", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] userArray = templateUsers.toArray(new String[0]);
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("Kimin template'i?")
                    .setItems(userArray, (dialog, which) -> {
                        String claimPerson = userArray[which];
                        float similarity = ((MainActivity) getActivity()).authenticate(claimPerson, probePerson);
                        ((MainActivity) getActivity()).getRrBuffer().clear();
                        Log.d("RR_BUFFER", "Authentication yapildi, rrBuffer temizlendi.");

                        boolean isGenuine = claimPerson.equals(probePerson);
                        boolean accepted = similarity >= HrvAuthEngine.AUTH_THRESHOLD;
                        String simStr = similarity >= 0 ? String.format(Locale.getDefault(), "%.4f", similarity) : "hata";
                        String resultText = (accepted ? "✓ Kabul" : "✗ Reddedildi")
                                + " | " + probePerson + " → " + claimPerson
                                + " | " + simStr
                                + (isGenuine ? " [genuine]" : " [impostor]");
                        recordingStatusField.setText(resultText);
                        Toast.makeText(getContext(), resultText, Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });

        userNameField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                ((MainActivity) getActivity()).getRrBuffer().clear();
                Log.d("RR_BUFFER", "Kullanici adi degisti, rrBuffer temizlendi.");
                saveTemplateButton.setEnabled(false);
                authenticateButton.setEnabled(false);
                recordingStatusField.setText("Kullanici degisti — buffer temizlendi, 30sn bekleyin.");
                timerHandler.removeCallbacks(userSwitchCooldownRunnable);
                timerHandler.postDelayed(userSwitchCooldownRunnable, 30000);
            }
        });

        manageUsersButton = view.findViewById(R.id.manageUsersButton);
        manageUsersButton.setOnClickListener(v -> showManageUsersDialog());

        return view;
    }

    private org.json.JSONObject readJsonFile(String fileName) {
        try {
            File file = new File(getActivity().getFilesDir(), fileName);
            if (!file.exists()) return new org.json.JSONObject();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return new org.json.JSONObject(sb.toString());
        } catch (Exception e) {
            Log.e("MANAGE_USERS", "Okuma hatasi (" + fileName + "): " + e.getMessage());
            return new org.json.JSONObject();
        }
    }

    private void deleteUser(String userName) {
        try {
            org.json.JSONObject subjects = readJsonFile("subjects.json");
            subjects.remove(userName);
            FileWriter subjectsWriter = new FileWriter(new File(getActivity().getFilesDir(), "subjects.json"));
            subjectsWriter.write(subjects.toString());
            subjectsWriter.close();

            org.json.JSONObject templates = readJsonFile("templates.json");
            templates.remove(userName);
            FileWriter templatesWriter = new FileWriter(new File(getActivity().getFilesDir(), "templates.json"));
            templatesWriter.write(templates.toString());
            templatesWriter.close();

            Toast.makeText(getContext(), userName + " silindi.", Toast.LENGTH_SHORT).show();
            Log.d("MANAGE_USERS", userName + " subjects.json ve templates.json'dan silindi.");
        } catch (IOException e) {
            Toast.makeText(getContext(), "Silme hatasi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e("MANAGE_USERS", "Silme hatasi: " + e.getMessage());
        }
    }

    private String getTemplateTimestamp(org.json.JSONObject templates, String userName) {
        try {
            if (!templates.has(userName)) return "-";
            Object value = templates.get(userName);
            if (!(value instanceof org.json.JSONObject)) return "-";
            org.json.JSONObject entry = (org.json.JSONObject) value;
            String savedAt = entry.optString("savedAt", null);
            if (savedAt != null) return savedAt;
            long timestamp = entry.optLong("timestamp", -1);
            if (timestamp <= 0) return "-";
            return new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(new Date(timestamp));
        } catch (Exception e) {
            return "-";
        }
    }

    private void showManageUsersDialog() {
        org.json.JSONObject subjects = readJsonFile("subjects.json");
        org.json.JSONObject templates = readJsonFile("templates.json");
        java.util.Iterator<String> keys = subjects.keys();
        java.util.List<String> userNames = new java.util.ArrayList<>();
        while (keys.hasNext()) userNames.add(keys.next());

        if (userNames.isEmpty()) {
            Toast.makeText(getContext(), "Kayitli kullanici yok.", Toast.LENGTH_SHORT).show();
            return;
        }

        int padding = (int) (16 * getResources().getDisplayMetrics().density);

        android.widget.ScrollView scrollView = new android.widget.ScrollView(getContext());
        android.widget.LinearLayout container = new android.widget.LinearLayout(getContext());
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(padding, padding, padding, padding);
        scrollView.addView(container);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(getContext())
                .setTitle("Kullanicilari Yonet")
                .setView(scrollView)
                .setNegativeButton("Kapat", null)
                .create();

        for (String userName : userNames) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(getContext());
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setPadding(0, padding / 2, 0, padding / 2);

            TextView nameView = new TextView(getContext());
            String timestampStr = getTemplateTimestamp(templates, userName);
            nameView.setText(userName + "\nkaydedildi: " + timestampStr);
            nameView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            Button deleteButton = new Button(getContext());
            deleteButton.setText("Sil");
            deleteButton.setOnClickListener(v -> {
                new android.app.AlertDialog.Builder(getContext())
                        .setTitle("Kullaniciyi Sil")
                        .setMessage(userName + " silinsin mi?")
                        .setPositiveButton("Evet", (d, w) -> {
                            deleteUser(userName);
                            dialog.dismiss();
                            showManageUsersDialog();
                        })
                        .setNegativeButton("Vazgec", null)
                        .show();
            });

            row.addView(nameView);
            row.addView(deleteButton);
            container.addView(row);
        }

        dialog.show();
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

        if (!main.getRrBuffer().isEmpty()) {
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

        // BPM validasyonu — 100+ BPM'de veri kalitesi dusuk
        String bpmText = heartRateField.getText().toString().trim();
        if (!bpmText.isEmpty() && !bpmText.equals("???")) {
            try {
                int currentBpm = Integer.parseInt(bpmText);
                if (currentBpm > 130) {
                    Toast.makeText(getContext(),
                            "Uyari: BPM yuksek (" + currentBpm + "). Veri kalitesi dusuk olabilir.",
                            Toast.LENGTH_LONG).show();
                    Log.w("KAYIT", "Yuksek BPM ile kayit basladi: " + currentBpm);
                }
            } catch (NumberFormatException e) {
                Log.w("KAYIT", "BPM okunamadi, devam ediliyor");
            }
        }

        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String subjectId = ((MainActivity) getActivity()).getOrCreateSubjectId(userName);
            int sessionNum = ((MainActivity) getActivity()).getNextSessionNumber(subjectId);
            String safeName = userName.replace(" ", "_")
                    .replace("ş","s").replace("Ş","S")
                    .replace("ğ","g").replace("Ğ","G")
                    .replace("ü","u").replace("Ü","U")
                    .replace("ö","o").replace("Ö","O")
                    .replace("ç","c").replace("Ç","C")
                    .replace("ı","i").replace("İ","I");
            String fileName = safeName + "_session" + sessionNum + "_" + timestamp + ".csv";            File file = new File(getActivity().getFilesDir(), fileName);
            csvWriter = new FileWriter(file, true);
            csvWriter.write("subject_id,session_id,activity,timestamp,bpm,rr_ms\n");

            isRecording = true;
            sessionStartTime = System.currentTimeMillis();
            startRecordingButton.setEnabled(false);
            stopRecordingButton.setEnabled(true);
            saveTemplateButton.setEnabled(false);
            authenticateButton.setEnabled(false);

            ((MainActivity) getActivity()).getRrBuffer().clear();
            ((MainActivity) getActivity()).resetSessionRrBuffer();

            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isRecording) {
                        long elapsed = (System.currentTimeMillis() - sessionStartTime) / 1000;
                        int rrCount = ((MainActivity) getActivity()).getRrBuffer().size();
                        String status = String.format(Locale.getDefault(),
                                "Kayit: %02d:%02d | %d RR", elapsed / 60, elapsed % 60, rrCount);
                        recordingStatusField.setText(status);
                        authenticateButton.setText("Kimlik Dogrula (" + rrCount + "/50 RR)");
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

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        timerHandler.removeCallbacksAndMessages(null);
    }

    public void updateHeartRate(String heartRate) {
        heartRateField.setText(heartRate);
    }

    public void updateBattery(int batteryLevel) {
        batteryField.setText(String.valueOf(batteryLevel));
    }

    @SuppressWarnings("unused")
    public void updateZone(int zone) {}

    @SuppressWarnings("unused")
    public void updateSportMode(int sportMode) {}
}