package ru.ricnorr.benchmarks.jmh;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import ru.ricnorr.benchmarks.BenchmarkResultsCsv;
import ru.ricnorr.benchmarks.params.ConsumeCpuBenchmarkParameters;
import ru.ricnorr.numa.locks.Utils;

import static ru.ricnorr.benchmarks.Main.autoThreadsInit;

public class JmhBenchmarkRunner {

  public static List<Options> fillBenchmarkParameters(JSONArray array) {
    List<Options> paramList = new ArrayList<>();
    for (Object o : array) {
      JSONObject obj = (JSONObject) o;
      String name = (String) obj.get("name");
      if (name.equals("consumeCpu")) {
        JSONObject payload = (JSONObject) obj.get("payload");
        ConsumeCpuBenchmarkParameters consumeCpuBenchmarkParameters;
        try {
          consumeCpuBenchmarkParameters =
              new ObjectMapper().readValue(payload.toJSONString(), ConsumeCpuBenchmarkParameters.class);
        } catch (Exception e) {
          throw new RuntimeException("Failed to parse payload of benchmark, err=" + e.getMessage());
        }
        if (consumeCpuBenchmarkParameters.skip) {
          continue;
        }
        if (consumeCpuBenchmarkParameters.threads == null) {
          consumeCpuBenchmarkParameters.threads = autoThreadsInit();
        }
        paramList.addAll(consumeCpuBenchmarkParameters.getOptions());
      }
    }
    return paramList;
  }

  public static List<Double> runBenchmarkNano(Options options)
      throws RunnerException {
    var res = new Runner(options).run();
    var executionTimesInNanos = new ArrayList<Double>();
    res.forEach(it -> it.getBenchmarkResults().forEach(
        it2 -> it2.getIterationResults()
            .forEach(it3 -> executionTimesInNanos.add(it3.getPrimaryResult().getScore()))));
    return executionTimesInNanos;
  }

  public static BenchmarkResultsCsv runBenchmark(Options options) {
    List<Double> withLocksNanos;
    try {
      withLocksNanos = runBenchmarkNano(options);
    } catch (Exception e) {
      withLocksNanos = List.of(Double.MAX_VALUE);
    }
    if (withLocksNanos.isEmpty()) {
      withLocksNanos = List.of(Double.MAX_VALUE);
    }
    int threads = Integer.parseInt(options.getParameter("threads").get().stream().findFirst().get());
    int actionsCount = Integer.parseInt(options.getParameter("actionsCount").get().stream().findFirst().get());
    String title = options.getParameter("title").get().stream().findFirst().get();
    String lockType = options.getParameter("lockType").get().stream().findFirst().get();

    double withLockNanosMin = withLocksNanos.stream().min(Double::compare).get();
    double withLockNanosMax = withLocksNanos.stream().max(Double::compare).get();
    double withLockNanosMedian = Utils.median(withLocksNanos);
    double withoutLocksNanos = 0;
    double overheadNanosMin = withLockNanosMin - withoutLocksNanos;
    double overheadNanosMax = withLockNanosMax - withoutLocksNanos;
    double overheadNanosAverage = withLockNanosMedian - withoutLocksNanos;
    double throughputNanosMin = actionsCount / withLockNanosMax;
    double throughputNanosMax = actionsCount / withLockNanosMin;
    double throughputNanosMedian = actionsCount / withLockNanosMedian;
    System.out.printf(
        "Consume cpu bench: i got max_over=%f, min_over=%f, avg_over=%f, max_thrpt=%f, min_thrpt=%f, avg_thrpt=%f%n",
        overheadNanosMax, overheadNanosMin, overheadNanosAverage, throughputNanosMax, throughputNanosMin,
        throughputNanosMedian);
    return new BenchmarkResultsCsv(
        title,
        lockType,
        threads,
        overheadNanosMax, overheadNanosMin, overheadNanosAverage, throughputNanosMax, throughputNanosMin,
        throughputNanosMedian);
  }
}
