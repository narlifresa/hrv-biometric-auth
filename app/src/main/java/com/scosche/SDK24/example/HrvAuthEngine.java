package com.scosche.SDK24.example;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * encoder.tflite modelini çalıştırır: standardize edilmiş 15 HRV feature -> 16 boyutlu
 * L2-normalize embedding. HrvFeatureExtractor.computeFeatures() çıktısını burada
 * standardize edip modele veriyoruz.
 *
 * ÖNEMLİ: assets/scaler.json'daki feature_order, HrvFeatureExtractor.FEATURE_ORDER ile
 * birebir aynı sırada olmak zorunda (aksi halde model sessizce yanlış sonuç üretir).
 * Bu sınıf yüklenirken bu sırayı doğrular ve uyuşmazlıkta hemen hata fırlatır.
 *
 * Bu model bir pilot çalışmanın (18 kişi, yalnızca dinlenme-durumu oturumları (89 oturum),
 * AUC≈0.83, EER≈0.27) ürünüdür, üretim kalite bir güvenlik sistemi değildir. AUTH_THRESHOLD
 * burada geçici bir başlangıç değeridir, kalibre edilmeden nihai kabul edilmemelidir.
 */
public class HrvAuthEngine {

    private static final String TAG = "HrvAuthEngine";
    private static final String MODEL_ASSET = "encoder.tflite";
    private static final String SCALER_ASSET = "scaler.json";

    // AUTH_THRESHOLD kalibrasyonu (güncel, düzeltilmiş veri):
    // Veri setindeki ayakta (standing) durumda kaydedilmiş 18 oturumun tespit edilip
    // analiz dışı bırakılmasından sonra (bkz. proje raporu Bölüm 5.6), pipeline
    // yalnızca 89 dinlenme oturumuyla yeniden çalıştırıldı:
    //   LOSO AUC: 0.7575 -> 0.8295   LOSO EER: 0.3319 -> 0.2733
    // Bu eşik, EER noktasından (dengeli FAR/FRR) türetilmiştir:
    //   threshold=0.60  ->  FAR≈0.2603, FRR≈0.2614
    // Önceki değer (0.55f) eski, karışık (rest+standing) veriyle kalibre edilmişti.
    public static final float AUTH_THRESHOLD = 0.60f;

    private final Interpreter interpreter;
    private final double[] scalerMean;
    private final double[] scalerStd;
    private final int inDim;
    private final int embDim;

    private HrvAuthEngine(Interpreter interpreter, double[] scalerMean, double[] scalerStd, int embDim) {
        this.interpreter = interpreter;
        this.scalerMean = scalerMean;
        this.scalerStd = scalerStd;
        this.inDim = scalerMean.length;
        this.embDim = embDim;
    }

    /** assets/encoder.tflite ve assets/scaler.json'dan bir HrvAuthEngine kurar. */
    public static HrvAuthEngine fromAssets(Context context) throws IOException, org.json.JSONException {
        MappedByteBuffer modelBuffer = loadModelFile(context, MODEL_ASSET);
        Interpreter interpreter = new Interpreter(modelBuffer);

        JSONObject scalerJson = readScalerJson(context, SCALER_ASSET);
        JSONArray featureOrderArr = scalerJson.getJSONArray("feature_order");
        JSONArray meanArr = scalerJson.getJSONArray("scaler_mean");
        JSONArray stdArr = scalerJson.getJSONArray("scaler_std");

        int n = featureOrderArr.length();
        if (n != HrvFeatureExtractor.FEATURE_ORDER.length) {
            throw new IllegalStateException("scaler.json feature sayisi (" + n +
                    ") HrvFeatureExtractor.FEATURE_ORDER ile eslesmiyor (" +
                    HrvFeatureExtractor.FEATURE_ORDER.length + ")");
        }
        for (int i = 0; i < n; i++) {
            String scalerFeature = featureOrderArr.getString(i);
            String expected = HrvFeatureExtractor.FEATURE_ORDER[i];
            // scaler.json "HRV_" onekiyle geliyor (ör. "HRV_MeanNN"), computeFeatures() onneksiz.
            String normalized = scalerFeature.startsWith("HRV_") ? scalerFeature.substring(4) : scalerFeature;
            if (!normalized.equals(expected)) {
                throw new IllegalStateException("Feature sirasi uyusmuyor (index " + i + "): scaler.json='" +
                        scalerFeature + "' beklenen='" + expected + "'. Model yanlis sonuc uretebilir, durduruldu.");
            }
        }

        double[] scalerMean = new double[n];
        double[] scalerStd = new double[n];
        for (int i = 0; i < n; i++) {
            scalerMean[i] = meanArr.getDouble(i);
            scalerStd[i] = stdArr.getDouble(i);
        }

        int embDim = scalerJson.optInt("emb_dim", 16);
        Log.d(TAG, "HrvAuthEngine yuklendi: inDim=" + n + " embDim=" + embDim);
        return new HrvAuthEngine(interpreter, scalerMean, scalerStd, embDim);
    }

    private static MappedByteBuffer loadModelFile(Context context, String assetName) throws IOException {
        try (AssetFileDescriptor fd = context.getAssets().openFd(assetName);
             FileInputStream inputStream = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = inputStream.getChannel();
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    private static JSONObject readScalerJson(Context context, String assetName) throws IOException {
        try (InputStream is = context.getAssets().open(assetName);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return new JSONObject(sb.toString());
        } catch (org.json.JSONException e) {
            throw new IOException("scaler.json parse edilemedi: " + e.getMessage(), e);
        }
    }

    /**
     * @param rawFeatures HrvFeatureExtractor.computeFeatures(rr) çıktısı (HrvFeatureExtractor.FEATURE_ORDER
     *                    sırasıyla, ham/standardize edilmemiş)
     * @return L2-normalize edilmiş embedding
     */
    public float[] embed(double[] rawFeatures) {
        if (rawFeatures.length != inDim) {
            throw new IllegalArgumentException("Beklenen " + inDim + " feature, verilen: " + rawFeatures.length);
        }
        float[][] input = new float[1][inDim];
        for (int i = 0; i < inDim; i++) {
            input[0][i] = (float) ((rawFeatures[i] - scalerMean[i]) / scalerStd[i]);
        }
        float[][] output = new float[1][embDim];
        interpreter.run(input, output);
        return output[0];
    }

    /** İki embedding zaten L2-normalize olduğu için dot product = kosinüs benzerliği. */
    public static float cosineSimilarity(float[] a, float[] b) {
        float sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }

    /**
     * Kayıt (enrollment) akışı: bir kişinin N oturumundan embedding üretip ortalamasını
     * alarak "template" oluşturur.
     */
    public static float[] averageTemplate(float[][] embeddings) {
        int dim = embeddings[0].length;
        float[] avg = new float[dim];
        for (float[] emb : embeddings) {
            for (int i = 0; i < dim; i++) avg[i] += emb[i];
        }
        float norm = 0;
        for (int i = 0; i < dim; i++) { avg[i] /= embeddings.length; norm += avg[i] * avg[i]; }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < dim; i++) avg[i] /= (norm + 1e-8f);
        return avg;
    }

    public void close() {
        interpreter.close();
    }
}
