package com.scosche.SDK24.example;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * P01_session1_20260811_090646.csv -- fizyolojik sınır + gap filtresinden geçmiş RR
 * (Kubios öncesi). Beklenen değerler hrv_features.csv'den (Python/NeuroKit2 referans çıktısı).
 * Toleranslar HrvFeatureExtractor'daki doğrulama notlarıyla eşleşir (pNN50/pNN20 sınır-değer
 * farkı, SampEn kayan-nokta farkı).
 */
public class HrvFeatureExtractorGoldenTest {

    private static final double[] GOLDEN_RR = { 807.0000, 760.0000, 743.0000, 672.0000, 687.0000, 680.0000, 712.0000, 752.0000, 720.0000, 775.0000, 783.0000, 792.0000, 816.0000, 752.0000, 752.0000, 712.0000, 719.0000, 663.0000, 696.0000, 643.0000, 644.0000, 687.0000, 696.0000, 695.0000, 647.0000, 648.0000, 639.0000, 688.0000, 687.0000, 680.0000, 680.0000, 687.0000, 744.0000, 735.0000, 720.0000, 631.0000, 616.0000, 815.0000, 863.0000, 848.0000, 823.0000, 712.0000, 688.0000, 679.0000, 688.0000, 680.0000, 695.0000, 760.0000, 743.0000, 735.0000, 703.0000, 656.0000, 623.0000, 559.0000, 764.0000, 764.0000, 647.0000, 632.0000, 615.0000, 623.0000, 608.0000, 615.0000, 647.0000, 696.0000, 671.0000, 720.0000, 720.0000, 695.0000, 743.0000, 720.0000, 703.0000, 712.0000, 663.0000, 688.0000, 759.0000, 728.0000, 728.0000, 735.0000, 752.0000, 775.0000, 792.0000, 728.0000, 712.0000, 663.0000, 672.0000, 695.0000, 672.0000, 672.0000, 663.0000, 687.0000, 712.0000, 703.0000, 640.0000, 736.0000, 736.0000, 767.0000, 760.0000, 735.0000, 728.0000, 712.0000, 712.0000, 677.0000, 677.0000, 748.0000, 672.0000, 608.0000, 615.0000, 703.0000, 663.0000, 768.0000, 752.0000, 735.0000, 831.0000, 775.0000, 824.0000, 723.0000, 723.0000, 695.0000, 703.0000, 720.0000, 808.0000, 831.0000, 815.0000, 775.0000, 752.0000, 712.0000, 728.0000, 752.0000, 687.0000, 712.0000, 720.0000, 760.0000, 767.0000, 736.0000, 735.0000, 720.0000, 712.0000 };

    private static final double[] GOLDEN_EXPECTED = {
        713.7518248175182, 55.34301871880736, 47.053552094458105, 47.22229853896178,
        19.708029197080293, 53.28467153284672, 0.0775381817523992, 712.0, 72.0,
        33.39120752011547, 70.6484019974481, 0.4726392469757716, 7411.128445753751,
        1.5192128208413749, 5.313014403025719
    };

    @Test
    public void computeFeatures_matchesNeuroKit2Reference() {
        double[] out = HrvFeatureExtractor.computeFeatures(GOLDEN_RR);
        assertEquals(HrvFeatureExtractor.FEATURE_ORDER.length, out.length);

        for (int i = 0; i < HrvFeatureExtractor.FEATURE_ORDER.length; i++) {
            String feature = HrvFeatureExtractor.FEATURE_ORDER[i];
            double tolerance;
            switch (feature) {
                case "pNN50":
                case "pNN20":
                    tolerance = 0.5; // sınır-değer karşılaştırma kuralı farkı
                    break;
                case "SampEn":
                    tolerance = 0.03; // kayan nokta sınır etkisi
                    break;
                default:
                    tolerance = 1e-6; // birebir eşleşme
            }
            assertEquals("Feature: " + feature, GOLDEN_EXPECTED[i], out[i], tolerance);
        }
    }
}
