package com.gyeongsan.cabinet.adapter.out.embedding;

final class EmbeddingMath {

    private EmbeddingMath() {}

    static float[] meanPool(float[][] hidden, long[] attentionMask) {
        if (hidden.length == 0) {
            throw new IllegalArgumentException("토큰이 없습니다.");
        }
        int dim = hidden[0].length;
        double[] sum = new double[dim];
        int count = 0;
        for (int t = 0; t < hidden.length; t++) {
            if (t < attentionMask.length && attentionMask[t] == 0) {
                continue;
            }
            for (int d = 0; d < dim; d++) {
                sum[d] += hidden[t][d];
            }
            count++;
        }
        if (count == 0) {
            throw new IllegalArgumentException("유효한 토큰이 없습니다.");
        }
        float[] pooled = new float[dim];
        for (int d = 0; d < dim; d++) {
            pooled[d] = (float) (sum[d] / count);
        }
        return pooled;
    }

    static float[] normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-12) {
            return vector.clone();
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) (vector[i] / norm);
        }
        return result;
    }
}
