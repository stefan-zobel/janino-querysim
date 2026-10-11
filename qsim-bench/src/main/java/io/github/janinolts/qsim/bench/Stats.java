/*
 * Copyright 2026 the janino-querysim authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.janinolts.qsim.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The statistical functions of the measurements: robust and classical
 * location estimators, the Student t distribution, and the multiple-testing
 * procedures of Benjamini and Hochberg (selection) and Benjamini and
 * Yekutieli (intervals with false coverage rate control).
 */
public final class Stats {

    private Stats() {
    }

    /** Returns the median of the values. */
    public static double median(List<Double> values) {
        return quantile(values, 0.5);
    }

    /**
     * Returns a quantile with linear interpolation between the order
     * statistics (type 7 of Hyndman and Fan, the default of R).
     */
    public static double quantile(List<Double> values, double p) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("No values");
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double h = (sorted.size() - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        return sorted.get(lo) + (h - lo) * (sorted.get(hi) - sorted.get(lo));
    }

    /** Returns the arithmetic mean. */
    public static double mean(List<Double> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("No values");
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    /** Returns the sample standard deviation (with {@code n - 1}). */
    public static double standardDeviation(List<Double> values) {
        if (values.size() < 2) {
            return Double.NaN;
        }
        double m = mean(values);
        double sum = 0;
        for (double v : values) {
            sum += (v - m) * (v - m);
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    /** Returns the distribution function of Student's t distribution with {@code df} degrees of freedom. */
    public static double studentTCdf(double t, double df) {
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        if (Double.isInfinite(t)) {
            return t > 0 ? 1 : 0;
        }
        double x = df / (df + t * t);
        double tail = 0.5 * regularizedIncompleteBeta(x, df / 2, 0.5);
        return t > 0 ? 1 - tail : tail;
    }

    /** Returns the quantile of Student's t distribution with {@code df} degrees of freedom, for {@code 0 < p < 1}. */
    public static double studentTQuantile(double p, double df) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("p: " + p);
        }
        if (p < 0.5) {
            return -studentTQuantile(1 - p, df);
        }
        double lo = 0;
        double hi = 1;
        while (studentTCdf(hi, df) < p) {
            hi *= 2;
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (studentTCdf(mid, df) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** Returns the two-sided p-value of a t statistic. */
    public static double twoSidedP(double t, double df) {
        return 2 * (1 - studentTCdf(Math.abs(t), df));
    }

    /** The regularized incomplete beta function I_x(a, b), by a continued fraction (Lentz). */
    static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double logFront = logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x);
        if (x < (a + 1) / (a + b + 2)) {
            return Math.exp(logFront) * betaContinuedFraction(x, a, b) / a;
        }
        return 1 - Math.exp(logFront) * betaContinuedFraction(1 - x, b, a) / b;
    }

    private static double betaContinuedFraction(double x, double a, double b) {
        double tiny = 1e-300;
        double c = 1;
        double d = 1 - (a + b) * x / (a + 1);
        d = Math.abs(d) < tiny ? tiny : d;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 10_000; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((a + m2 - 1) * (a + m2));
            d = 1 + aa * d;
            d = Math.abs(d) < tiny ? tiny : d;
            c = 1 + aa / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (a + b + m) * x / ((a + m2) * (a + m2 + 1));
            d = 1 + aa * d;
            d = Math.abs(d) < tiny ? tiny : d;
            c = 1 + aa / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1) < 1e-15) {
                break;
            }
        }
        return h;
    }

    /** The logarithm of the gamma function (Lanczos approximation, g = 7). */
    static double logGamma(double x) {
        double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6,
            1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - logGamma(1 - x);
        }
        x -= 1;
        double sum = c[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) {
            sum += c[i] / (x + i);
        }
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    /**
     * Selects hypotheses by the procedure of Benjamini and Hochberg.
     *
     * @param pValues the p-values
     * @param q the false discovery rate, e.g. 0.05
     * @return per hypothesis whether it is selected
     */
    public static boolean[] benjaminiHochberg(double[] pValues, double q) {
        int m = pValues.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (x, y) -> Double.compare(pValues[x], pValues[y]));
        int k = 0;
        for (int i = m; i >= 1; i--) {
            if (pValues[order[i - 1]] <= q * i / m) {
                k = i;
                break;
            }
        }
        boolean[] selected = new boolean[m];
        for (int i = 0; i < k; i++) {
            selected[order[i]] = true;
        }
        return selected;
    }

    /**
     * Returns the confidence level of the intervals of the selected
     * hypotheses after Benjamini and Yekutieli: {@code 1 - k q / m}, which
     * controls the false coverage rate at {@code q}.
     *
     * @param selected the number {@code k} of selected hypotheses, at least 1
     * @param total the number {@code m} of hypotheses
     */
    public static double fcrLevel(int selected, int total, double q) {
        return 1 - (double) Math.max(selected, 1) * q / total;
    }
}
