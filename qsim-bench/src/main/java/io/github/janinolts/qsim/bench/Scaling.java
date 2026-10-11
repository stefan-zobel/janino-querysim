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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

/**
 * The scaling reports of P2: per series and mode, the time and the
 * allocation per operation of both versions over the length of the source
 * text, the exponent of a power law fitted to them, as CSV and SVG.
 */
final class Scaling {

    /**
     * One level of a series.
     *
     * @param level the level
     * @param chars the length of the source text
     * @param secondsA the time per operation of A, the mean of the fork medians
     * @param secondsB the time per operation of B
     * @param bytesA the allocation per operation of A
     * @param bytesB the allocation per operation of B
     * @param result the result of the point
     */
    record Row(int level, int chars, double secondsA, double secondsB, double bytesA, double bytesB,
            Aggregator.PointResult result) {
    }

    /**
     * A series in one mode.
     *
     * @param series the series, e.g. {@code columns}
     * @param mode the mode
     * @param rows the levels, in ascending order
     */
    record Curve(String series, String mode, List<Row> rows) {

        /** Returns the base name of the files, e.g. {@code columns-xint}. */
        String name() {
            return series + "-" + mode;
        }

        /**
         * Returns the exponent of the power law {@code y = c * chars^k}
         * fitted by least squares on the log scale; NaN with fewer than two
         * levels. An exponent above 1 means that the value grows faster than
         * the source text.
         */
        double exponent(ToDoubleFunction<Row> y) {
            List<double[]> xy = new ArrayList<>();
            for (Row r : rows) {
                double v = y.applyAsDouble(r);
                if (r.chars() > 0 && v > 0 && !Double.isNaN(v)) {
                    xy.add(new double[] {Math.log(r.chars()), Math.log(v)});
                }
            }
            return slope(xy);
        }
    }

    private Scaling() {
    }

    /** Returns the curves of the P2 results, with the source lengths and levels from the point infos. */
    static List<Curve> curves(List<Aggregator.PointResult> results, List<PointInfo> infos) {
        Map<String, PointInfo> byPoint = new TreeMap<>();
        for (PointInfo i : infos) {
            if (i.measurement().equals("P2")) {
                byPoint.put(i.point(), i);
            }
        }
        Map<String, List<Row>> groups = new TreeMap<>();
        for (Aggregator.PointResult r : results) {
            PointInfo info = byPoint.get(r.point());
            if (!r.measurement().equals("P2") || info == null || info.attribute("series") == null
                    || r.forks().isEmpty()) {
                continue;
            }
            double sa = r.forks().stream().mapToDouble(Aggregator.ForkSummary::secondsA).average().orElse(Double.NaN);
            double sb = r.forks().stream().mapToDouble(Aggregator.ForkSummary::secondsB).average().orElse(Double.NaN);
            double ba = r.forks().stream().mapToDouble(Aggregator.ForkSummary::bytesA).average().orElse(Double.NaN);
            double bb = r.forks().stream().mapToDouble(Aggregator.ForkSummary::bytesB).average().orElse(Double.NaN);
            groups.computeIfAbsent(info.attribute("series") + "|" + r.mode(), k -> new ArrayList<>())
                    .add(new Row(Integer.parseInt(info.attribute("level")), info.sourceChars(), sa, sb, ba, bb, r));
        }
        List<Curve> curves = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : groups.entrySet()) {
            String[] key = e.getKey().split("\\|");
            List<Row> rows = new ArrayList<>(e.getValue());
            rows.sort(Comparator.comparingInt(Row::level));
            curves.add(new Curve(key[0], key[1], rows));
        }
        return curves;
    }

    /** Returns the slope of a least-squares line through the points; NaN with fewer than two distinct x. */
    static double slope(List<double[]> xy) {
        if (xy.size() < 2) {
            return Double.NaN;
        }
        double mx = xy.stream().mapToDouble(p -> p[0]).average().orElseThrow();
        double my = xy.stream().mapToDouble(p -> p[1]).average().orElseThrow();
        double sxy = 0;
        double sxx = 0;
        for (double[] p : xy) {
            sxy += (p[0] - mx) * (p[1] - my);
            sxx += (p[0] - mx) * (p[0] - mx);
        }
        return sxx == 0 ? Double.NaN : sxy / sxx;
    }

    /** Returns the CSV of a curve. */
    static String csv(Curve c) {
        StringBuilder sb = new StringBuilder("series,mode,level,source_chars,seconds_a,seconds_b,bytes_a,bytes_b,"
                + "ratio,ratio_low,ratio_high,classification\n");
        for (Row r : c.rows()) {
            double[] ci = r.result().ratio().interval(0.95);
            sb.append(String.join(",", c.series(), c.mode(), Integer.toString(r.level()), Integer.toString(r.chars()),
                    num(r.secondsA()), num(r.secondsB()), num(r.bytesA()), num(r.bytesB()),
                    num(r.result().ratio().ratio()), num(ci[0]), num(ci[1]), r.result().classification().name()))
                    .append('\n');
        }
        return sb.toString();
    }

    private static final int WIDTH = 640;
    private static final int HEIGHT = 400;
    private static final int LEFT = 70;
    private static final int RIGHT = 20;
    private static final int TOP = 40;
    private static final int BOTTOM = 50;

    /**
     * Returns an SVG of the time per operation of both versions over the
     * length of the source text, both axes logarithmic.
     */
    static String svg(Curve c) {
        List<Row> rows = c.rows().stream().filter(r -> r.chars() > 0 && r.secondsA() > 0 && r.secondsB() > 0)
                .toList();
        StringBuilder sb = new StringBuilder();
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(WIDTH).append("\" height=\"")
                .append(HEIGHT).append("\" viewBox=\"0 0 ").append(WIDTH).append(' ').append(HEIGHT)
                .append("\" font-family=\"sans-serif\" font-size=\"12\">\n");
        sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>\n");
        sb.append("<text x=\"").append(LEFT).append("\" y=\"22\" font-size=\"14\">P2 ").append(c.series())
                .append(" (").append(c.mode()).append("): time per operation over source length</text>\n");
        if (rows.isEmpty()) {
            return sb.append("</svg>\n").toString();
        }
        double x0 = Math.log10(rows.stream().mapToInt(Row::chars).min().orElseThrow());
        double x1 = Math.log10(rows.stream().mapToInt(Row::chars).max().orElseThrow());
        double y0 = Math.log10(rows.stream().mapToDouble(r -> Math.min(r.secondsA(), r.secondsB())).min().orElseThrow());
        double y1 = Math.log10(rows.stream().mapToDouble(r -> Math.max(r.secondsA(), r.secondsB())).max().orElseThrow());
        if (x1 - x0 < 1e-9) {
            x0 -= 0.5;
            x1 += 0.5;
        }
        if (y1 - y0 < 1e-9) {
            y0 -= 0.5;
            y1 += 0.5;
        }
        double[] xr = {Math.floor(x0), Math.ceil(x1)};
        double[] yr = {Math.floor(y0), Math.ceil(y1)};
        int plotW = WIDTH - LEFT - RIGHT;
        int plotH = HEIGHT - TOP - BOTTOM;
        sb.append("<rect x=\"").append(LEFT).append("\" y=\"").append(TOP).append("\" width=\"").append(plotW)
                .append("\" height=\"").append(plotH).append("\" fill=\"none\" stroke=\"#999999\"/>\n");
        for (int d = (int) xr[0]; d <= (int) xr[1]; d++) {
            double x = LEFT + plotW * (d - xr[0]) / (xr[1] - xr[0]);
            sb.append(line(x, TOP, x, TOP + plotH, "#eeeeee"));
            sb.append("<text x=\"").append(fmt(x)).append("\" y=\"").append(TOP + plotH + 18)
                    .append("\" text-anchor=\"middle\">1e").append(d).append("</text>\n");
        }
        for (int d = (int) yr[0]; d <= (int) yr[1]; d++) {
            double y = TOP + plotH - plotH * (d - yr[0]) / (yr[1] - yr[0]);
            sb.append(line(LEFT, y, LEFT + plotW, y, "#eeeeee"));
            sb.append("<text x=\"").append(LEFT - 6).append("\" y=\"").append(fmt(y + 4))
                    .append("\" text-anchor=\"end\">1e").append(d).append(" s</text>\n");
        }
        sb.append("<text x=\"").append(LEFT + plotW / 2).append("\" y=\"").append(HEIGHT - 10)
                .append("\" text-anchor=\"middle\">source characters</text>\n");
        String[] colors = {"#4c6ef5", "#e8590c"};
        String[] labels = {"A (baseline)", "B (candidate)"};
        for (int arm = 0; arm < 2; arm++) {
            StringBuilder points = new StringBuilder();
            for (Row r : rows) {
                double x = LEFT + plotW * (Math.log10(r.chars()) - xr[0]) / (xr[1] - xr[0]);
                double v = arm == 0 ? r.secondsA() : r.secondsB();
                double y = TOP + plotH - plotH * (Math.log10(v) - yr[0]) / (yr[1] - yr[0]);
                points.append(fmt(x)).append(',').append(fmt(y)).append(' ');
                sb.append("<circle cx=\"").append(fmt(x)).append("\" cy=\"").append(fmt(y))
                        .append("\" r=\"3\" fill=\"").append(colors[arm]).append("\"/>\n");
            }
            sb.append("<polyline fill=\"none\" stroke=\"").append(colors[arm]).append("\" stroke-width=\"1.5\" points=\"")
                    .append(points.toString().strip()).append("\"/>\n");
            double k = c.exponent(arm == 0 ? Row::secondsA : Row::secondsB);
            sb.append("<text x=\"").append(LEFT + 10).append("\" y=\"").append(TOP + 18 + 16 * arm)
                    .append("\" fill=\"").append(colors[arm]).append("\">").append(labels[arm])
                    .append(", exponent ").append(num2(k)).append("</text>\n");
        }
        return sb.append("</svg>\n").toString();
    }

    private static String line(double x1, double y1, double x2, double y2, String color) {
        return "<line x1=\"" + fmt(x1) + "\" y1=\"" + fmt(y1) + "\" x2=\"" + fmt(x2) + "\" y2=\"" + fmt(y2)
                + "\" stroke=\"" + color + "\"/>\n";
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    static String num(double v) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%.6g", v);
    }

    static String num2(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.2f", v);
    }
}
