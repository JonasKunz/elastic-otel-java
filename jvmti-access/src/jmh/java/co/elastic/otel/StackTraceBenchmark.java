package co.elastic.otel;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
public class StackTraceBenchmark {


  @State(Scope.Thread)
  public static class Consumers {

    long[] bigStackTraceBuffer = new long[4096];
    long[] smallStackTraceBuffer = new long[32];

    @Param({"10", "50", "100", "250", "500"})
    int stackDepth;
    Runnable noop;
    Runnable threadGetStackTrace;
    Runnable throwableConstructOnly;
    Runnable throwableGetStackTrace;
    Runnable stackwalkerFullWithResolve;
    Runnable stackwalkerFullWithoutResolve;
    Runnable stackwalkerTop16WithResolve;

    Runnable jvmtiFullWithResolve;
    Runnable jvmtiFullWithoutResolve;
    Runnable jvmtiTop16WithoutResolve;

    CallStackInvoker callStack;

    @Setup(Level.Iteration)
    public void init(Blackhole blackhole) {
      callStack = CallStackGenerator.generateCallstackInvoker(stackDepth);

      noop = () -> blackhole.consume(true);
      threadGetStackTrace = () -> blackhole.consume(Thread.currentThread().getStackTrace());
      throwableConstructOnly = () -> blackhole.consume(new Throwable());
      throwableGetStackTrace = () -> blackhole.consume(new Throwable().getStackTrace());

      StackWalker walker = StackWalker.getInstance();
      Consumer<StackWalker.StackFrame> consumeWithResolve = sf -> {
        //Include name resolution in benchmark
        blackhole.consume(sf.getClassName());
        blackhole.consume(sf.getMethodName());
      };
      Consumer<StackWalker.StackFrame> consumeWithoutResolve = blackhole::consume;
      stackwalkerFullWithResolve = () -> walker.forEach(consumeWithResolve);
      stackwalkerFullWithoutResolve = () -> walker.forEach(consumeWithoutResolve);

      Function<Stream<StackWalker.StackFrame>, String> top16Walker = stream -> {
        stream.limit(16).forEach(consumeWithResolve);
        return "";
      };
      stackwalkerTop16WithResolve = () -> walker.walk(top16Walker);

      jvmtiFullWithResolve = () -> {
        int frameCount = JvmtiAccess.getStackTrace(0, bigStackTraceBuffer.length,
            bigStackTraceBuffer);
        for (int i = 0; i < frameCount; i++) {
          blackhole.consume(JvmtiAccess.getDeclaringClass(bigStackTraceBuffer[i]));
          blackhole.consume(JvmtiAccess.getMethodName(bigStackTraceBuffer[i], false));
        }
      };

      jvmtiFullWithoutResolve = () -> {
        int frameCount = JvmtiAccess.getStackTrace(0, bigStackTraceBuffer.length,
            bigStackTraceBuffer);
        blackhole.consume(bigStackTraceBuffer);
      };

      jvmtiTop16WithoutResolve = () -> {
        int frameCount = JvmtiAccess.getStackTrace(0, 16, smallStackTraceBuffer);
        blackhole.consume(bigStackTraceBuffer);
      };
    }
  }

  @Benchmark
  public void baseline(Consumers consumers) {
    consumers.callStack.run(consumers.noop);
  }

  @Benchmark
  public void threadGetStackTrace(Consumers consumers) {
    consumers.callStack.run(consumers.threadGetStackTrace);
  }

  @Benchmark
  public void throwableConstructOnly(Consumers consumers) {
    consumers.callStack.run(consumers.throwableConstructOnly);
  }

  @Benchmark
  public void throwableGetStackTrace(Consumers consumers) {
    consumers.callStack.run(consumers.throwableGetStackTrace);
  }

  @Benchmark
  public void stackwalkerFullWithResolve(Consumers consumers) {
    consumers.callStack.run(consumers.stackwalkerFullWithResolve);
  }

  @Benchmark
  public void stackwalkerFullWithoutResolve(Consumers consumers) {
    consumers.callStack.run(consumers.stackwalkerFullWithoutResolve);
  }

  @Benchmark
  public void stackwalkerTop16WithResolve(Consumers consumers) {
    consumers.callStack.run(consumers.stackwalkerTop16WithResolve);
  }

  @Benchmark
  public void jvmtiFullWithResolve(Consumers consumers) {
    consumers.callStack.run(consumers.jvmtiFullWithResolve);
  }

  @Benchmark
  public void jvmtiFullWithoutResolve(Consumers consumers) {
    consumers.callStack.run(consumers.jvmtiFullWithoutResolve);
  }

  @Benchmark
  public void jvmtiTop16WithoutResolve(Consumers consumers) {
    consumers.callStack.run(consumers.jvmtiTop16WithoutResolve);
  }

}
