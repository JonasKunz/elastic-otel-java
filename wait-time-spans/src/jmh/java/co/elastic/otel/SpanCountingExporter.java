package co.elastic.otel;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

public class SpanCountingExporter implements SpanExporter {

  private final AtomicLong spanCount = new AtomicLong();

  long maxCount = Long.MIN_VALUE;

  long minCount = Long.MAX_VALUE;

  long totalCounts = 0;
  long numResets = 0;

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    spanCount.addAndGet(spans.size());
    return CompletableResultCode.ofSuccess();
  }

  public long getSpanCount() {
    return spanCount.get();
  }


  public void reset() {
    long val = spanCount.getAndSet(0L);
    maxCount = Math.max(val, maxCount);
    minCount = Math.min(val, minCount);
    totalCounts += val;
    numResets++;
  }

  @Override
  public CompletableResultCode flush() {
    return CompletableResultCode.ofSuccess();
  }

  @Override
  public CompletableResultCode shutdown() {
    return CompletableResultCode.ofSuccess();
  }
}
