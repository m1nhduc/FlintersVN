import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

public class Main {

    private static final int BUFFER_SIZE = 32 * 1024 * 1024; // 32MB

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);

        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.err.println("Usage: java Main --input <file> --output <dir>");
            System.exit(1);
            return;
        }

        if (!Files.exists(arguments.input)) {
            System.err.println("Error: Input file not found: " + arguments.input);
            System.exit(1);
            return;
        }

        try {
            long start = System.nanoTime();

            ProcessingResult result = processWithBufferedStream(arguments.input);
            Map<String, CampaignStats> stats = result.stats;

            long processingNanos = System.nanoTime() - start;

            List<ResultRow> topCtr = top10ByCtr(stats);
            List<ResultRow> topCpa = top10ByCpa(stats);

            Files.createDirectories(arguments.outputDir);

            Path ctrOutput = arguments.outputDir.resolve("top10_ctr.csv");
            Path cpaOutput = arguments.outputDir.resolve("top10_cpa.csv");

            writeCsv(ctrOutput, topCtr);
            writeCsv(cpaOutput, topCpa);

            long totalNanos = System.nanoTime() - start;

            System.out.println("Input: " + arguments.input);
            System.out.println("Output directory: " + arguments.outputDir);
            System.out.println("Unique campaigns: " + stats.size());
            System.out.println("Rows processed: " + result.rowsProcessed);
            System.out.println("Rows skipped (malformed): " + result.rowsSkipped);
            System.out.printf("Processing time: %.3f s%n", processingNanos / 1_000_000_000.0);
            System.out.printf("Total time: %.3f s%n", totalNanos / 1_000_000_000.0);
            System.out.printf("Peak memory (approx): %.1f MB%n",
                    (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024.0 * 1024.0));
            System.out.println("Generated:");
            System.out.println("  - " + ctrOutput);
            System.out.println("  - " + cpaOutput);

        } catch (IOException e) {
            System.err.println("Error processing file: " + e.getMessage());
            System.exit(1);
        }
    }

    static ProcessingResult processWithBufferedStream(Path input) throws IOException {
        Map<String, CampaignStats> statsMap = new HashMap<>(8192);
        long rowsProcessed = 0;
        long rowsSkipped = 0;

        byte[] buffer = new byte[BUFFER_SIZE];
        byte[] lineBuffer = new byte[256];
        int lineLength = 0;
        boolean headerSkipped = false;

        try (BufferedInputStream in = new BufferedInputStream(Files.newInputStream(input), BUFFER_SIZE)) {
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        if (lineLength > 0 && lineBuffer[lineLength - 1] == '\r') {
                            lineLength--;
                        }

                        if (!headerSkipped) {
                            headerSkipped = true;
                        } else if (lineLength > 0) {
                            if (processCsvLine(lineBuffer, lineLength, statsMap)) {
                                rowsProcessed++;
                            } else {
                                rowsSkipped++;
                            }
                        }

                        lineLength = 0;
                    } else {
                        if (lineLength == lineBuffer.length) {
                            lineBuffer = grow(lineBuffer);
                        }
                        lineBuffer[lineLength++] = b;
                    }
                }
            }
        }

        // Handle last line without trailing newline
        if (lineLength > 0) {
            if (lineBuffer[lineLength - 1] == '\r') {
                lineLength--;
            }
            if (!headerSkipped) {
                // file has only header, no data
            } else if (lineLength > 0) {
                if (processCsvLine(lineBuffer, lineLength, statsMap)) {
                    rowsProcessed++;
                } else {
                    rowsSkipped++;
                }
            }
        }

        return new ProcessingResult(statsMap, rowsProcessed, rowsSkipped);
    }

    /**
     * Parses a single CSV line and updates the stats map.
     * Returns true if successfully parsed, false if malformed.
     */
    static boolean processCsvLine(byte[] line, int length, Map<String, CampaignStats> statsMap) {
        int[] commas = new int[5];
        int commaCount = 0;

        for (int i = 0; i < length; i++) {
            if (line[i] == ',') {
                if (commaCount >= 5) {
                    return false; // too many columns
                }
                commas[commaCount++] = i;
            }
        }

        if (commaCount != 5) {
            return false; // wrong number of columns
        }

        try {
            String campaignId = new String(line, 0, commas[0], StandardCharsets.UTF_8);

            // Skip date field (commas[0]+1 to commas[1])
            long impressions = parseLong(line, commas[1] + 1, commas[2]);
            long clicks = parseLong(line, commas[2] + 1, commas[3]);
            double spend = parseDouble(line, commas[3] + 1, commas[4]);
            long conversions = parseLong(line, commas[4] + 1, length);

            if (impressions < 0 || clicks < 0 || spend < 0 || conversions < 0) {
                return false;
            }

            CampaignStats stats = statsMap.get(campaignId);
            if (stats == null) {
                stats = new CampaignStats(campaignId);
                statsMap.put(campaignId, stats);
            }

            stats.totalImpressions += impressions;
            stats.totalClicks += clicks;
            stats.totalSpend += spend;
            stats.totalConversions += conversions;

            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static long parseLong(byte[] bytes, int start, int end) {
        if (start >= end) throw new NumberFormatException("Empty field");
        long value = 0;
        for (int i = start; i < end; i++) {
            byte b = bytes[i];
            if (b < '0' || b > '9') {
                throw new NumberFormatException("Invalid integer at position " + i);
            }
            value = value * 10 + (b - '0');
        }
        return value;
    }

    static double parseDouble(byte[] bytes, int start, int end) {
        if (start >= end) throw new NumberFormatException("Empty field");
        long integerPart = 0;
        long fractionalPart = 0;
        long divisor = 1;
        boolean seenDot = false;

        for (int i = start; i < end; i++) {
            byte b = bytes[i];
            if (b == '.') {
                if (seenDot) throw new NumberFormatException("Multiple dots");
                seenDot = true;
                continue;
            }
            if (b < '0' || b > '9') {
                throw new NumberFormatException("Invalid decimal at position " + i);
            }
            int digit = b - '0';
            if (!seenDot) {
                integerPart = integerPart * 10 + digit;
            } else {
                fractionalPart = fractionalPart * 10 + digit;
                divisor *= 10;
            }
        }

        return integerPart + (fractionalPart / (double) divisor);
    }

    static List<ResultRow> top10ByCtr(Map<String, CampaignStats> statsMap) {
        PriorityQueue<ResultRow> heap = new PriorityQueue<>(Comparator
                .comparingDouble((ResultRow r) -> r.ctr)
                .thenComparing(r -> r.campaignId, Comparator.reverseOrder()));

        for (CampaignStats stats : statsMap.values()) {
            double ctr = stats.totalImpressions == 0 ? 0.0 :
                    (double) stats.totalClicks / stats.totalImpressions;
            Double cpa = stats.totalConversions == 0 ? null :
                    stats.totalSpend / stats.totalConversions;

            ResultRow row = new ResultRow(
                    stats.campaignId, stats.totalImpressions, stats.totalClicks,
                    stats.totalSpend, stats.totalConversions, ctr, cpa);

            if (heap.size() < 10) {
                heap.offer(row);
            } else if (compareCtr(row, heap.peek()) > 0) {
                heap.poll();
                heap.offer(row);
            }
        }

        List<ResultRow> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble((ResultRow r) -> r.ctr).reversed()
                .thenComparing(r -> r.campaignId));
        return result;
    }

    static List<ResultRow> top10ByCpa(Map<String, CampaignStats> statsMap) {
        PriorityQueue<ResultRow> heap = new PriorityQueue<>((a, b) -> {
            int cmp = Double.compare(b.cpa, a.cpa);
            if (cmp != 0) return cmp;
            return a.campaignId.compareTo(b.campaignId);
        });

        for (CampaignStats stats : statsMap.values()) {
            if (stats.totalConversions == 0) continue;

            double ctr = stats.totalImpressions == 0 ? 0.0 :
                    (double) stats.totalClicks / stats.totalImpressions;
            double cpa = stats.totalSpend / stats.totalConversions;

            ResultRow row = new ResultRow(
                    stats.campaignId, stats.totalImpressions, stats.totalClicks,
                    stats.totalSpend, stats.totalConversions, ctr, cpa);

            if (heap.size() < 10) {
                heap.offer(row);
            } else if (compareCpa(row, heap.peek()) < 0) {
                heap.poll();
                heap.offer(row);
            }
        }

        List<ResultRow> result = new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble((ResultRow r) -> r.cpa)
                .thenComparing(r -> r.campaignId));
        return result;
    }

    private static int compareCtr(ResultRow a, ResultRow b) {
        int cmp = Double.compare(a.ctr, b.ctr);
        if (cmp != 0) return cmp;
        return b.campaignId.compareTo(a.campaignId);
    }

    private static int compareCpa(ResultRow a, ResultRow b) {
        int cmp = Double.compare(a.cpa, b.cpa);
        if (cmp != 0) return cmp;
        return a.campaignId.compareTo(b.campaignId);
    }

    private static void writeCsv(Path output, List<ResultRow> rows) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("campaign_id,total_impressions,total_clicks,total_spend,total_conversions,CTR,CPA");
            writer.newLine();

            for (ResultRow row : rows) {
                writer.write(row.campaignId);
                writer.write(',');
                writer.write(Long.toString(row.totalImpressions));
                writer.write(',');
                writer.write(Long.toString(row.totalClicks));
                writer.write(',');
                writer.write(String.format(Locale.US, "%.2f", row.totalSpend));
                writer.write(',');
                writer.write(Long.toString(row.totalConversions));
                writer.write(',');
                writer.write(String.format(Locale.US, "%.4f", row.ctr));
                writer.write(',');
                writer.write(row.cpa == null ? "null" : String.format(Locale.US, "%.2f", row.cpa));
                writer.newLine();
            }
        }
    }

    private static byte[] grow(byte[] current) {
        byte[] next = new byte[current.length * 2];
        System.arraycopy(current, 0, next, 0, current.length);
        return next;
    }

    // --- Data classes ---

    static class CampaignStats {
        final String campaignId;
        long totalImpressions;
        long totalClicks;
        double totalSpend;
        long totalConversions;

        CampaignStats(String campaignId) {
            this.campaignId = campaignId;
        }
    }

    static class ResultRow {
        final String campaignId;
        final long totalImpressions;
        final long totalClicks;
        final double totalSpend;
        final long totalConversions;
        final double ctr;
        final Double cpa;

        ResultRow(String campaignId, long totalImpressions, long totalClicks,
                  double totalSpend, long totalConversions, double ctr, Double cpa) {
            this.campaignId = campaignId;
            this.totalImpressions = totalImpressions;
            this.totalClicks = totalClicks;
            this.totalSpend = totalSpend;
            this.totalConversions = totalConversions;
            this.ctr = ctr;
            this.cpa = cpa;
        }
    }

    static class ProcessingResult {
        final Map<String, CampaignStats> stats;
        final long rowsProcessed;
        final long rowsSkipped;

        ProcessingResult(Map<String, CampaignStats> stats, long rowsProcessed, long rowsSkipped) {
            this.stats = stats;
            this.rowsProcessed = rowsProcessed;
            this.rowsSkipped = rowsSkipped;
        }
    }

    private static class Arguments {
        final Path input;
        final Path outputDir;

        private Arguments(Path input, Path outputDir) {
            this.input = input;
            this.outputDir = outputDir;
        }

        static Arguments parse(String[] args) {
            Path input = null;
            Path outputDir = null;

            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --input");
                        input = Path.of(args[++i]);
                    }
                    case "--output" -> {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for --output");
                        outputDir = Path.of(args[++i]);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }

            if (input == null) throw new IllegalArgumentException("Missing required argument --input");
            if (outputDir == null) throw new IllegalArgumentException("Missing required argument --output");

            return new Arguments(input, outputDir);
        }
    }
}