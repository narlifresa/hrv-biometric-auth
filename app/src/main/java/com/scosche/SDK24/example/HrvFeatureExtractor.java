package com.scosche.SDK24.example;

import android.util.Log;

import java.util.Arrays;

public class HrvFeatureExtractor {

    private static final int FEATURE_COUNT = 16;
    private static final int MIN_RR_COUNT = 20;

    /** Temizlenmiş RR intervallerinden (ms) 16 elemanlı HRV feature array'i üretir; yetersiz veri varsa null döner. */
    public static float[] extract(double[] rr) {
        if (rr == null || rr.length < MIN_RR_COUNT) {
            return null;
        }

        double meanNN = mean(rr);
        double sdnn = std(rr);
        double[] diffs = absDiffs(rr);
        double rmssd = rms(diffs);
        double sdsd = std(diffs);
        double pnn50 = countAbove(diffs, 50.0);
        double pnn20 = countAbove(diffs, 20.0);
        double cvnn = sdnn / meanNN;
        double medianNN = median(rr);
        double iqrNN = iqr(rr);

        double sd1 = rmssd / Math.sqrt(2.0);
        double sd2 = Math.sqrt(Math.max(0.0, 2.0 * sdnn * sdnn - sd1 * sd1));
        double sd1sd2 = sd1 / sd2;
        double s = Math.PI * sd1 * sd2;

        double sampEn = 0.0;
        double shanEn = 0.0;
        double hf = rmssd / meanNN;

        float[] features = new float[FEATURE_COUNT];
        features[0] = (float) meanNN;
        features[1] = (float) sdnn;
        features[2] = (float) rmssd;
        features[3] = (float) sdsd;
        features[4] = (float) pnn50;
        features[5] = (float) pnn20;
        features[6] = (float) cvnn;
        features[7] = (float) medianNN;
        features[8] = (float) iqrNN;
        features[9] = (float) sd1;
        features[10] = (float) sd2;
        features[11] = (float) sd1sd2;
        features[12] = (float) s;
        features[13] = (float) sampEn;
        features[14] = (float) shanEn;
        features[15] = (float) hf;

        Log.d("HRV_FEATURES", "MeanNN=" + features[0] +
                " | SDNN=" + features[1] +
                " | RMSSD=" + features[2] +
                " | SD1=" + features[9] +
                " | SD2=" + features[10] +
                " | HF=" + features[15]);

        return features;
    }

    /** Dizinin aritmetik ortalamasını hesaplar. */
    private static double mean(double[] a) {
        double sum = 0.0;
        for (double v : a) {
            sum += v;
        }
        return sum / a.length;
    }

    /** Dizinin popülasyon standart sapmasını hesaplar. */
    private static double std(double[] a) {
        double m = mean(a);
        double sumSq = 0.0;
        for (double v : a) {
            sumSq += (v - m) * (v - m);
        }
        return Math.sqrt(sumSq / a.length);
    }

    /** Ardışık elemanlar arasındaki mutlak farkları hesaplar. */
    private static double[] absDiffs(double[] a) {
        double[] diffs = new double[a.length - 1];
        for (int i = 0; i < diffs.length; i++) {
            diffs[i] = Math.abs(a[i + 1] - a[i]);
        }
        return diffs;
    }

    /** Dizinin kareler ortalamasının karekökünü (RMS) hesaplar. */
    private static double rms(double[] a) {
        double sumSq = 0.0;
        for (double v : a) {
            sumSq += v * v;
        }
        return Math.sqrt(sumSq / a.length);
    }

    /** Verilen eşiği aşan elemanların dizideki oranını hesaplar. */
    private static double countAbove(double[] a, double threshold) {
        int count = 0;
        for (double v : a) {
            if (v > threshold) {
                count++;
            }
        }
        return (double) count / a.length;
    }

    /** Diziyi kopyalayıp sıralayarak medyanını hesaplar. */
    private static double median(double[] a) {
        double[] sorted = Arrays.copyOf(a, a.length);
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 0) {
            return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
        }
        return sorted[n / 2];
    }

    /** Diziyi kopyalayıp sıralayarak çeyrekler arası aralığını (Q75 - Q25) hesaplar. */
    private static double iqr(double[] a) {
        double[] sorted = Arrays.copyOf(a, a.length);
        Arrays.sort(sorted);
        int n = sorted.length;
        double q1 = sorted[(int) Math.floor(0.25 * (n - 1))];
        double q3 = sorted[(int) Math.floor(0.75 * (n - 1))];
        return q3 - q1;
    }
}
