package co.elastic.otel.waitspans;

import co.elastic.jvmti.JvmtiAccess;
import co.elastic.jvmti.VirtualThreadMountCallback;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.concurrent.TimeUnit;

public class WaitSpanGeneratingProcessor implements SpanProcessor {

  private static final int BUFFER_SIZE = 1024;
  private static final int MAX_STACK_FRAMES = 32;

  //Skip this number of top stack frames when collecting because they are not interesting (e.g. disruptor frames)
  private static final int SKIP_STACK_FRAMES = 7;

  private static class RecordedBlockingOperation {
    long[] stackTraceMethodIds = new long[MAX_STACK_FRAMES];
    /**
     * The actual number of populated values in {@link #stackTraceMethodIds}.
     */
    int numStackFrames;

    long startTimestamp;
    long endTimestamp;

    Context parentContext;

  }

  private final long minSpanDurationNanos;

  private final SpanAnchoredClock clock;

  private Tracer tracer;

  private final Disruptor<RecordedBlockingOperation> disruptor;


  private static class LongHolder {
    long value = -1;
  }

  private final VirtualThreadMountCallback virtualThreadCallbacks = new VirtualThreadMountCallback() {


    private final ThreadLocal<LongHolder> blockingStartTimestamp = new ThreadLocal() {
      @Override
      protected Object initialValue() {
        return new LongHolder();
      }
    };


    @Override
    public void threadUnmounted(Thread thread) {
      blockingStartTimestamp.get().value = clock.nanoTime();
    }

    @Override
    public void threadMounted(Thread thread) {
      LongHolder startTimeHolder = blockingStartTimestamp.get();
      long blockingStart = startTimeHolder.value;
      if (blockingStart == -1) {
        return;
      }
      startTimeHolder.value = -1;

      long endTime = clock.nanoTime();

      if ((endTime - blockingStart) < minSpanDurationNanos) {
        return;
      }

      Context currentCtx = Context.current();
      if (currentCtx == null) {
        return;
      }
      Span parentSpan = Span.fromContext(currentCtx);
      if (parentSpan.getSpanContext().isValid() && parentSpan.getSpanContext().isSampled()) {
        disruptor.getRingBuffer().tryPublishEvent((output, sequence) -> {
          output.parentContext = currentCtx;
          output.startTimestamp = blockingStart;
          output.endTimestamp = endTime;
          output.numStackFrames = JvmtiAccess.getStackTrace(SKIP_STACK_FRAMES, MAX_STACK_FRAMES,
              output.stackTraceMethodIds);
        });
      }
    }
  };

  public WaitSpanGeneratingProcessor(long minSpanDurationNanos) {
    this(minSpanDurationNanos, true);
  }

  public WaitSpanGeneratingProcessor(long minSpanDurationNanos, boolean processEvents) {
    this.minSpanDurationNanos = minSpanDurationNanos;
    this.clock = new SpanAnchoredClock();
    // IMPORTANT! We must not use any wait-strategy which involves looks when publishing values
    // This will cause a deadlock on the virtual thread on contention!
    disruptor = new Disruptor<>(RecordedBlockingOperation::new, BUFFER_SIZE,
        DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new SleepingWaitStrategy(1, 1_000_000));
    if (processEvents) {
      disruptor.handleEventsWith(this::createSpanForRecordedOperation);
    } else {
      disruptor.handleEventsWith(((event, sequence, endOfBatch) -> {
      }));
    }
    disruptor.start();

    JvmtiAccess.addVirtualThreadMountCallback(virtualThreadCallbacks);

  }

  public void setTracer(Tracer tracer) {
    this.tracer = tracer;
  }

  private void createSpanForRecordedOperation(
      RecordedBlockingOperation event,
      long sequence,
      boolean endOfBatch) {
    String name = BlockingMethodNamer.getBlockerMethod(event.stackTraceMethodIds,
        event.numStackFrames);

    if (name != null) {
      Span parent = Span.fromContext(event.parentContext);
      long anchor = clock.getAnchor(parent);

      tracer.spanBuilder(name)
          .setParent(event.parentContext)
          .setStartTimestamp(clock.toEpochNanos(anchor, event.startTimestamp), TimeUnit.NANOSECONDS)
          .startSpan()
          .end(clock.toEpochNanos(anchor, event.endTimestamp), TimeUnit.NANOSECONDS);
    }

  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    clock.onSpanStart(span, parentContext);
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
  }

  @Override
  public boolean isEndRequired() {
    return false;
  }

  @Override
  public CompletableResultCode shutdown() {
    CompletableResultCode result = new CompletableResultCode();
    JvmtiAccess.removeVirtualThreadMountCallback(virtualThreadCallbacks);
    try {
      disruptor.shutdown();
      result.succeed();
    } catch (Exception e) {
      e.printStackTrace();
      result.fail();
    }
    return result;
  }
}
