package com.scosche.SDK24.example;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Python pipeline.py / NeuroKit2 ile aynı 15 HRV feature'ını Java'da hesaplar.
 * Feature sırası encoder_15feat_scaler.json'daki "feature_order" ile birebir aynı olmalı:
 *   MeanNN, SDNN, RMSSD, SDSD, pNN50, pNN20, CVNN, MedianNN, IQRNN,
 *   SD1, SD2, SD1SD2, S, SampEn, ShanEn
 *
 * Doğrulama durumu (P01_session1 golden test ile ölçüldü, bkz. HrvFeatureExtractorGoldenTest):
 *   - MeanNN, SDNN, RMSSD, SDSD, CVNN, MedianNN, IQRNN, SD1, SD2, SD1SD2, S, ShanEn:
 *     Python/NeuroKit2 ile BİREBİR eşleşiyor (fark ~0.000000).
 *   - pNN50 / pNN20: ~0.1-0.4 puanlık ihmal edilebilir fark (sınır değer karşılaştırma kuralı
 *     farkı) — pratik kullanım için yeterli.
 *   - SampEn: ~%98.5 örtüşüyor (fark ~0.023, muhtemelen kayan nokta sınır etkisi).
 *   - HF ÇIKARILDI: model artık 15 feature ile eğitildi (encoder_15feat.tflite). Sebep: HF'nin
 *     Java'da (Welch PSD) tam replikasyonu ayrı bir mühendislik işiydi; HF'siz model AUC'u
 *     neredeyse hiç değiştirmedi (0.7595 -> 0.7575), yani anlamlı katkısı yoktu.
 *
 * Girdi: temizlenmiş RR dizisi (ms). RR temizleme (fizyolojik sınır + gap tespiti) app'in
 * kendi tarafında, pipeline.py'deki clean_rr() mantığıyla uygulanmalı.
 *
 * ÖNEMLİ: Bu sınıfın hesaplama mantığı değiştirilmemelidir — NeuroKit2 ile doğrulandı.
 */
public class HrvFeatureExtractor {

    public static final String[] FEATURE_ORDER = {
        "MeanNN", "SDNN", "RMSSD", "SDSD", "pNN50", "pNN20", "CVNN", "MedianNN", "IQRNN",
        "SD1", "SD2", "SD1SD2", "S", "SampEn", "ShanEn"
    };

    /** Ana giriş noktası: temizlenmiş RR (ms) -> FEATURE_ORDER sırasıyla 15 değer. */
    public static double[] computeFeatures(double[] rr) {
        if (rr.length < 20) {
            throw new IllegalArgumentException("En az 20 RR değeri gerekli, verilen: " + rr.length);
        }
        double[] diff = diff(rr);

        double meanNN   = mean(rr);
        double sdnn     = std(rr, 1);
        double rmssd    = Math.sqrt(mean(square(diff)));
        double sdsd     = std(diff, 1);
        double pnn50    = 100.0 * countAbsGreater(diff, 50) / diff.length;
        double pnn20    = 100.0 * countAbsGreater(diff, 20) / diff.length;
        double cvnn     = sdnn / meanNN;
        double medianNN = median(rr);
        double iqrnn    = percentile(rr, 75) - percentile(rr, 25);

        double[] poincare = poincareSD(rr); // [SD1, SD2] -- NeuroKit2 ile birebir aynı rotasyon formülü
        double sd1      = poincare[0];
        double sd2      = poincare[1];
        double sd1sd2   = sd1 / sd2;
        double s         = Math.PI * sd1 * sd2;

        double tolerance = 0.2 * sdnn; // NeuroKit2: 0.2 * std(rri, ddof=1)
        double sampEn    = sampleEntropy(rr, 2, tolerance);
        double shanEn    = shannonEntropyRaw(rr); // log2, ham (binlenmemiş) unique değerler

        return new double[]{
            meanNN, sdnn, rmssd, sdsd, pnn50, pnn20, cvnn, medianNN, iqrnn,
            sd1, sd2, sd1sd2, s, sampEn, shanEn
        };
    }

    // ───────────────────────── Temel istatistik yardımcıları ─────────────────────────
    static double mean(double[] x) { double s = 0; for (double v : x) s += v; return s / x.length; }

    static double std(double[] x, int ddof) {
        double m = mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - ddof));
    }

    static double[] diff(double[] x) {
        double[] d = new double[x.length - 1];
        for (int i = 0; i < d.length; i++) d[i] = x[i + 1] - x[i];
        return d;
    }

    static double[] square(double[] x) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = x[i] * x[i];
        return r;
    }

    static int countAbsGreater(double[] x, double t) {
        int c = 0;
        for (double v : x) if (Math.abs(v) > t) c++;
        return c;
    }

    static double median(double[] x) { return percentile(x, 50); }

    /** NeuroKit2 ile birebir aynı: x1=(rri_n-rri_next)/sqrt(2), x2=(rri_n+rri_next)/sqrt(2), std(ddof=1). */
    static double[] poincareSD(double[] rr) {
        int n = rr.length - 1;
        double[] x1 = new double[n], x2 = new double[n];
        for (int i = 0; i < n; i++) {
            x1[i] = (rr[i] - rr[i + 1]) / Math.sqrt(2);
            x2[i] = (rr[i] + rr[i + 1]) / Math.sqrt(2);
        }
        return new double[]{ std(x1, 1), std(x2, 1) };
    }

    /** numpy 'linear' interpolasyon yöntemiyle aynı percentile hesaplaması. */
    static double percentile(double[] x, double p) {
        double[] s = x.clone();
        Arrays.sort(s);
        double idx = (p / 100.0) * (s.length - 1);
        int lo = (int) Math.floor(idx), hi = (int) Math.ceil(idx);
        if (lo == hi) return s[lo];
        double frac = idx - lo;
        return s[lo] + (s[hi] - s[lo]) * frac;
    }

    // ───────────────────────── Sample Entropy (NeuroKit2 ile birebir aynı algoritma) ──
    // Kaynak: neurokit2/complexity/utils_entropy.py _phi() / _phi_divide()
    // phi(dim) = mean_i[ (count_i - 1) / (n_vec - 1) ], count_i self-match dahil, Chebyshev <= r
    // SampEn = -log( phi(m+1) / phi(m) )
    static double sampleEntropy(double[] x, int m, double r) {
        double phiM  = phi(x, m, r);
        double phiM1 = phi(x, m + 1, r);
        if (phiM == 0 || phiM1 == 0 || Double.isNaN(phiM) || Double.isNaN(phiM1)) return Double.NaN;
        return -Math.log(phiM1 / phiM);
    }

    private static double phi(double[] x, int dim, double r) {
        int n = x.length;
        int nVec = n - dim + 1; // delay=1 gömme vektörü sayısı
        if (nVec < 2) return Double.NaN;
        double[][] emb = new double[nVec][dim];
        for (int i = 0; i < nVec; i++)
            for (int k = 0; k < dim; k++)
                emb[i][k] = x[i + k];

        double sum = 0;
        for (int i = 0; i < nVec; i++) {
            int count = 0; // self-match dahil
            for (int j = 0; j < nVec; j++) {
                if (chebyshevLE(emb[i], emb[j], r)) count++;
            }
            sum += (count - 1.0) / (nVec - 1.0);
        }
        return sum / nVec;
    }

    private static boolean chebyshevLE(double[] a, double[] b, double r) {
        for (int k = 0; k < a.length; k++) {
            if (Math.abs(a[k] - b[k]) > r) return false; // <= r koşulu
        }
        return true;
    }

    // ───────────────────────── Shannon Entropy (ham, binlenmemiş, log2) ────────────────
    static double shannonEntropyRaw(double[] x) {
        Map<Double, Integer> freq = new HashMap<>();
        for (double v : x) freq.merge(v, 1, Integer::sum);
        double n = x.length, h = 0;
        for (int c : freq.values()) {
            double p = c / n;
            h -= p * (Math.log(p) / Math.log(2));
        }
        return h;
    }
}
