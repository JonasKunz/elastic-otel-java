package co.elastic.otel;

import co.elastic.jvmti.JvmtiAccess;
import co.elastic.jvmti.VirtualThreadMountCallback;
import co.elastic.otel.waitspans.WaitSpanGeneratingProcessor;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

// Results when native callbacks are replaced with NO-OP:
//   (Measured without active profiler)
//    WaitSpansBenchmark.baseline                     avgt    3  0,236 ± 0,142   s/op
//    WaitSpansBenchmark.processorActive              avgt    3  0,502 ± 0,260   s/op
//    WaitSpansBenchmark.processorActiveButNoSpans    avgt    3  0,487 ± 0,279   s/op
//    WaitSpansBenchmark.processorActiveNoProcessing  avgt    3  0,482 ± 0,385   s/op
public class WaitSpansBenchmark {

  private static final int NUM_VIRTUAL_THREADS = 100_000;

  private static final int STACK_DEPTH = 100;

  public static void recurse(int depth, Runnable task) {
    if (depth == 0) {
      task.run();
    } else {
      recurse(depth - 1, task);
    }
  }

  public void runThreadsAndUnmount(Context toActivate) {

    CountDownLatch threadsStartedLatch = new CountDownLatch(NUM_VIRTUAL_THREADS);
    CountDownLatch finishLatch = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();

    for (int i = 0; i < NUM_VIRTUAL_THREADS; i++) {
      threads.add(startVirtualThread(toActivate.wrap(() -> {
        recurse(STACK_DEPTH, () -> {
          threadsStartedLatch.countDown();
          try {
            finishLatch.await();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        });
      })));
    }

    try {
      threadsStartedLatch.await();
      finishLatch.countDown();
      for (Thread thread : threads) {
        thread.join();
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void baseline(PlainOtelState otelState) throws Exception {
    runThreadsAndUnmount(otelState.spanContext);
    otelState.counter.reset();
  }

  @State(Scope.Benchmark)
  public static class CallbackOnlyState implements VirtualThreadMountCallback {

    int mountCount;

    @Override
    public void threadMounted(Thread thread) {
      mountCount++;
    }

    @Override
    public void threadUnmounted(Thread thread) {
      mountCount--;
    }

    @Setup(Level.Iteration)
    public void init() {
      JvmtiAccess.addVirtualThreadMountCallback(this);
    }

    @TearDown(Level.Iteration)
    public void destroy() {
      JvmtiAccess.removeVirtualThreadMountCallback(this);
    }
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public int emptyThreadMountCallback(CallbackOnlyState state) throws Exception {
    runThreadsAndUnmount(Context.root());

    return state.mountCount;
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void processorActiveButNoSpans(WaitSpansEnabledState otelState) throws Exception {
    runThreadsAndUnmount(otelState.noSpanContext);
    otelState.counter.reset();
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void processorActive(WaitSpansEnabledState otelState) {
    runThreadsAndUnmount(otelState.spanContext);
    otelState.counter.reset();
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  public void processorActiveNoProcessing(WaitSpansEnabledNoProcessingState otelState) {
    runThreadsAndUnmount(otelState.spanContext);
    otelState.counter.reset();
  }

  public static abstract class OtelStateBase {

    SdkTracerProvider tracerProvider;

    SpanCountingExporter counter;

    Context noSpanContext;
    Context spanContext;

    @Setup(Level.Iteration)
    public void init() {
      counter = new SpanCountingExporter();
      tracerProvider = initTraceProvider(SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(counter))
      );

      Span span = tracerProvider.get("benchmark-spans").spanBuilder("root")
          .startSpan();
      span.end();
      spanContext = Context.current().with(span);
      noSpanContext = Context.current().with(Baggage.builder().put("foo", "bar").build());
    }

    protected SdkTracerProvider initTraceProvider(SdkTracerProviderBuilder builder) {
      return builder.build();
    }

    @TearDown(Level.Iteration)
    public void destroy() {
      tracerProvider.close();
      System.out.println();
      System.out.println("Max spans generated: " + counter.maxCount);
      System.out.println("Min spans generated: " + counter.minCount);
      System.out.println("Avg spans generated: " + (counter.totalCounts / counter.numResets));
    }


  }

  @State(Scope.Benchmark)
  public static class WaitSpansEnabledState extends OtelStateBase {
    @Override
    protected SdkTracerProvider initTraceProvider(SdkTracerProviderBuilder builder) {
      WaitSpanGeneratingProcessor processor = new WaitSpanGeneratingProcessor(0L);
      SdkTracerProvider result = builder.addSpanProcessor(processor).build();
      processor.setTracer(result.get("wait-spans"));
      return result;
    }
  }

  @State(Scope.Benchmark)
  public static class WaitSpansEnabledNoProcessingState extends OtelStateBase {
    @Override
    protected SdkTracerProvider initTraceProvider(SdkTracerProviderBuilder builder) {
      WaitSpanGeneratingProcessor processor = new WaitSpanGeneratingProcessor(0L, false);
      SdkTracerProvider result = builder.addSpanProcessor(processor).build();
      processor.setTracer(result.get("wait-spans"));
      return result;
    }
  }


  @State(Scope.Benchmark)
  public static class PlainOtelState extends OtelStateBase {
  }

  private static final MethodHandle START_VIRTUAL_THREAD;

  static {
    Method startVirtualThread = null;
    try {
      startVirtualThread = Thread.class.getMethod("startVirtualThread", Runnable.class);
      START_VIRTUAL_THREAD = MethodHandles.publicLookup().unreflect(startVirtualThread);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static Thread startVirtualThread(Runnable task) {
    try {
      return (Thread) START_VIRTUAL_THREAD.invokeExact(task);
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

}
