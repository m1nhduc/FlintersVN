# Ad Performance Aggregator

A high-performance CLI tool that processes a large CSV dataset (~1GB) of advertising records and outputs top campaigns by CTR and CPA.

## Requirements

- Java 17+

## Build & Run

```bash
# Compile
javac Main.java

# Run
java Main --input ad_data.csv --output results/

# Run tests
javac MainTest.java
java MainTest
```

## Usage

```
java Main --input <path-to-csv> --output <output-directory>
```

## Usage with Docker (Windows)
```cmd
# 1. Build image
docker build -t ad-aggregator .

# 2. Run container with mounted data directory the reprts will be saved in your <path-to-csv>/results directory
docker run --rm -v <path-to-csv>:/data ad-aggregator --input /data/ad_data.csv --output /data/results/
```

### Arguments

| Argument   | Required | Description                          |
|------------|----------|--------------------------------------|
| `--input`  | Yes      | Path to the input CSV file           |
| `--output` | Yes      | Directory for output CSV files       |

### Output

- `top10_ctr.csv` — Top 10 campaigns by highest CTR
- `top10_cpa.csv` — Top 10 campaigns by lowest CPA (excludes 0 conversions)

## Design Decisions

### Performance Optimizations

1. **Byte-level CSV parsing**: Reads raw bytes and parses fields manually, avoiding `String.split()` and `Scanner` overhead. Minimizes object allocation and GC pressure.

2. **Custom number parsers**: `parseLong` and `parseDouble` operate directly on byte arrays without creating intermediate String objects.

3. **Large read buffer (32MB)**: Reduces system call overhead for I/O.

4. **Heap-based Top-K selection**: Uses a min-heap of size 10 instead of sorting all campaigns — O(n) vs O(n log n).

5. **HashMap with pre-sized capacity**: Reduces rehashing for the expected number of unique campaigns.

### Error Handling

- Malformed rows (wrong column count, invalid numbers) are skipped and counted rather than crashing the program.
- Missing input file is detected early with a clear error message.
- Invalid CLI arguments produce usage help.

### Trade-offs

- **Byte-level parsing vs BufferedReader.readLine()**: Manual byte parsing is ~2-3x faster but harder to read and only supports ASCII. This is acceptable since the dataset contains only ASCII characters (campaign IDs, dates, numbers). For CSV files with Unicode or quoted fields, a proper CSV parser library would be needed.

## Benchmarks

Tested on a ~1GB CSV file:

| Metric           | Value       |
|------------------|-------------|
| Processing time  | ~3.5s       |
| Peak memory      | ~80 MB      |
| JDK              | OpenJDK 21  |

*(Results vary by hardware and JVM settings)*