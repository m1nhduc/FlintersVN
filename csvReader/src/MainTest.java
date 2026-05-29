import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        testParseLong();
        testParseDouble();
        testProcessCsvLine();
        testProcessCsvLineMalformed();
        testTop10ByCtr();
        testTop10ByCpa();
        testEndToEnd();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) System.exit(1);
    }

    static void testParseLong() {
        assertEqual("parseLong basic", 12345L, Main.parseLong("12345".getBytes(), 0, 5));
        assertEqual("parseLong zero", 0L, Main.parseLong("0".getBytes(), 0, 1));
        assertEqual("parseLong offset", 99L, Main.parseLong("abc,99,def".getBytes(), 4, 6));

        try {
            Main.parseLong("12a3".getBytes(), 0, 4);
            fail("parseLong invalid should throw");
        } catch (NumberFormatException e) {
            pass("parseLong invalid throws");
        }
    }

    static void testParseDouble() {
        assertDoubleEqual("parseDouble integer", 45.0, Main.parseDouble("45".getBytes(), 0, 2));
        assertDoubleEqual("parseDouble decimal", 45.50, Main.parseDouble("45.50".getBytes(), 0, 5));
        assertDoubleEqual("parseDouble small", 0.01, Main.parseDouble("0.01".getBytes(), 0, 4));

        try {
            Main.parseDouble("1.2.3".getBytes(), 0, 5);
            fail("parseDouble multiple dots should throw");
        } catch (NumberFormatException e) {
            pass("parseDouble multiple dots throws");
        }
    }

    static void testProcessCsvLine() {
        Map<String, Main.CampaignStats> stats = new HashMap<>();
        byte[] line = "CMP001,2025-01-01,12000,300,45.50,12".getBytes();
        boolean ok = Main.processCsvLine(line, line.length, stats);

        assertEqual("processCsvLine returns true", true, ok);
        assertEqual("processCsvLine campaign exists", true, stats.containsKey("CMP001"));

        Main.CampaignStats s = stats.get("CMP001");
        assertEqual("impressions", 12000L, s.totalImpressions);
        assertEqual("clicks", 300L, s.totalClicks);
        assertDoubleEqual("spend", 45.50, s.totalSpend);
        assertEqual("conversions", 12L, s.totalConversions);

        // Add another row for same campaign
        byte[] line2 = "CMP001,2025-01-02,8000,200,30.00,8".getBytes();
        Main.processCsvLine(line2, line2.length, stats);

        assertEqual("aggregated impressions", 20000L, s.totalImpressions);
        assertEqual("aggregated clicks", 500L, s.totalClicks);
        assertDoubleEqual("aggregated spend", 75.50, s.totalSpend);
        assertEqual("aggregated conversions", 20L, s.totalConversions);
    }

    static void testProcessCsvLineMalformed() {
        Map<String, Main.CampaignStats> stats = new HashMap<>();

        // Too few columns
        byte[] bad1 = "CMP001,2025-01-01,12000,300".getBytes();
        assertEqual("too few columns", false, Main.processCsvLine(bad1, bad1.length, stats));

        // Too many columns
        byte[] bad2 = "CMP001,2025-01-01,12000,300,45.50,12,extra".getBytes();
        assertEqual("too many columns", false, Main.processCsvLine(bad2, bad2.length, stats));

        // Invalid number
        byte[] bad3 = "CMP001,2025-01-01,abc,300,45.50,12".getBytes();
        assertEqual("invalid number", false, Main.processCsvLine(bad3, bad3.length, stats));

        assertEqual("no stats added for bad rows", 0, stats.size());
    }

    static void testTop10ByCtr() {
        Map<String, Main.CampaignStats> stats = new HashMap<>();
        for (int i = 1; i <= 15; i++) {
            Main.CampaignStats s = new Main.CampaignStats("CMP" + String.format("%03d", i));
            s.totalImpressions = 10000;
            s.totalClicks = i * 100; // CTR = i%
            s.totalSpend = 100.0;
            s.totalConversions = 10;
            stats.put(s.campaignId, s);
        }

        List<Main.ResultRow> top = Main.top10ByCtr(stats);
        assertEqual("top10 ctr size", 10, top.size());
        assertEqual("top10 ctr first", "CMP015", top.get(0).campaignId);
        assertEqual("top10 ctr last", "CMP006", top.get(9).campaignId);
    }

    static void testTop10ByCpa() {
        Map<String, Main.CampaignStats> stats = new HashMap<>();
        for (int i = 1; i <= 15; i++) {
            Main.CampaignStats s = new Main.CampaignStats("CMP" + String.format("%03d", i));
            s.totalImpressions = 10000;
            s.totalClicks = 100;
            s.totalSpend = i * 10.0; // CPA = i*10/10 = i
            s.totalConversions = 10;
            stats.put(s.campaignId, s);
        }

        // Add one with zero conversions — should be excluded
        Main.CampaignStats zero = new Main.CampaignStats("CMP000");
        zero.totalImpressions = 10000;
        zero.totalClicks = 100;
        zero.totalSpend = 1.0;
        zero.totalConversions = 0;
        stats.put(zero.campaignId, zero);

        List<Main.ResultRow> top = Main.top10ByCpa(stats);
        assertEqual("top10 cpa size", 10, top.size());
        assertEqual("top10 cpa first (lowest)", "CMP001", top.get(0).campaignId);
        assertEqual("top10 cpa last", "CMP010", top.get(9).campaignId);
        // Ensure CMP000 is not included
        for (Main.ResultRow r : top) {
            assertEqual("CMP000 excluded", true, !r.campaignId.equals("CMP000"));
        }
    }

    static void testEndToEnd() throws Exception {
        Path tempInput = Files.createTempFile("test_input", ".csv");
        Path tempOutputDir = Files.createTempDirectory("test_output");

        String csv = """
                campaign_id,date,impressions,clicks,spend,conversions
                CMP001,2025-01-01,10000,500,50.00,10
                CMP001,2025-01-02,10000,500,50.00,10
                CMP002,2025-01-01,20000,400,80.00,0
                CMP003,2025-01-01,5000,100,25.00,5
                """;
        Files.writeString(tempInput, csv);

        Main.ProcessingResult result = Main.processWithBufferedStream(tempInput);
        assertEqual("e2e rows processed", 4L, result.rowsProcessed);
        assertEqual("e2e rows skipped", 0L, result.rowsSkipped);
        assertEqual("e2e campaigns", 3, result.stats.size());

        Main.CampaignStats cmp1 = result.stats.get("CMP001");
        assertEqual("e2e CMP001 impressions", 20000L, cmp1.totalImpressions);
        assertEqual("e2e CMP001 clicks", 1000L, cmp1.totalClicks);
        assertDoubleEqual("e2e CMP001 spend", 100.00, cmp1.totalSpend);
        assertEqual("e2e CMP001 conversions", 20L, cmp1.totalConversions);

        // CMP002 has 0 conversions
        Main.CampaignStats cmp2 = result.stats.get("CMP002");
        assertEqual("e2e CMP002 conversions", 0L, cmp2.totalConversions);

        // Cleanup
        Files.deleteIfExists(tempInput);
        Files.walk(tempOutputDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        });

        pass("e2e test complete");
    }

    // --- Assertion helpers ---

    static void assertEqual(String name, Object expected, Object actual) {
        if (expected.equals(actual)) {
            pass(name);
        } else {
            fail(name + " — expected: " + expected + ", got: " + actual);
        }
    }

    static void assertDoubleEqual(String name, double expected, double actual) {
        if (Math.abs(expected - actual) < 0.0001) {
            pass(name);
        } else {
            fail(name + " — expected: " + expected + ", got: " + actual);
        }
    }

    static void pass(String name) {
        passed++;
        System.out.println("  ✓ " + name);
    }

    static void fail(String name) {
        failed++;
        System.out.println("  ✗ " + name);
    }
}