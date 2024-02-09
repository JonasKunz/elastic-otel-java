/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package co.elastic.otel;

import co.elastic.otel.common.AbstractChainingSpanProcessor;
import co.elastic.otel.common.LocalRootSpan;
import co.elastic.otel.common.util.ExecutorUtils;
import co.elastic.otel.common.util.HexUtils;
import co.elastic.otel.profiler.DecodeException;
import co.elastic.otel.profiler.ProfilerMessage;
import co.elastic.otel.profiler.TraceCorrelationMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextStorage;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nullable;

public class UniversalProfilingProcessor extends AbstractChainingSpanProcessor {

  private static final Logger log = Logger.getLogger(UniversalProfilingProcessor.class.getName());
  private static final long POLL_FREQUENCY_MS = 20;
  private static boolean anyInstanceActive = false;

  private final SpanProfilingSamplesCorrelator correlator;
  private final ScheduledExecutorService messagePollAndSpanFlushExecutor;

  // Visibile for testing
  String socketPath;

  public static UniversalProfilingProcessorBuilder builder(SpanProcessor next, Resource resource) {
    return new UniversalProfilingProcessorBuilder(next, resource);
  }

  UniversalProfilingProcessor(
      SpanProcessor next,
      Resource serviceResource,
      int bufferSize,
      Duration spanDelay,
      String socketDir,
      LongSupplier nanoClock) {
    super(next);
    synchronized (UniversalProfilingProcessor.class) {
      if (anyInstanceActive) {
        throw new IllegalStateException(
            "Another instance has already been started and not stopped yet."
                + " There must be at most one processor of this type active at a time!");
      }

      correlator =
          new SpanProfilingSamplesCorrelator(
              bufferSize, nanoClock, spanDelay.toNanos(), this.next::onEnd);

      socketPath = openProfilerSocket(socketDir);
      try {
        ByteBuffer buff =
            ProfilerSharedMemoryWriter.generateProcessCorrelationStorage(
                serviceResource, socketPath);
        UniversalProfilingCorrelation.setProcessStorage(buff);

        ThreadFactory threadFac =
            ExecutorUtils.threadFactory("elastic-profiler-correlation-", true);
        messagePollAndSpanFlushExecutor = Executors.newSingleThreadScheduledExecutor(threadFac);
        messagePollAndSpanFlushExecutor.scheduleWithFixedDelay(
            this::pollMessageAndFlushPendingSpans,
            POLL_FREQUENCY_MS,
            POLL_FREQUENCY_MS,
            TimeUnit.MILLISECONDS);

        ActivationListener.setProcessor(this);
      } catch (Exception e) {
        UniversalProfilingCorrelation.stopProfilerReturnChannel();
        throw e;
      }
      anyInstanceActive = true;
    }
  }

  private String openProfilerSocket(String socketDir) {
    Path dir = Paths.get(socketDir);
    if (!Files.exists(dir) && !dir.toFile().mkdirs()) {
      throw new IllegalArgumentException("Could not create directory '" + socketDir + "'");
    }
    Path socketFile;
    do {
      socketFile = dir.resolve(randomSocketFileName());
    } while (Files.exists(socketFile));

    String absolutePath = socketFile.toAbsolutePath().toString();
    log.log(Level.FINE, "Opening profiler correlation socket '{0}'", absolutePath);
    UniversalProfilingCorrelation.startProfilerReturnChannel(absolutePath);
    return absolutePath;
  }

  private String randomSocketFileName() {
    StringBuilder name = new StringBuilder("essock");
    String allowedChars = "abcdefghijklmonpqrstuvwxzyABCDEFGHIJKLMONPQRSTUVWXYZ0123456789";
    Random rnd = new Random();
    for (int i = 0; i < 8; i++) {
      int idx = rnd.nextInt(allowedChars.length());
      name.append(allowedChars.charAt(idx));
    }
    return name.toString();
  }

  @Override
  protected void doOnStart(Context context, ReadWriteSpan readWriteSpan) {
    LocalRootSpan.onSpanStart(readWriteSpan, context);
    correlator.onSpanStart(readWriteSpan, context);
  }

  @Override
  public void onEnd(ReadableSpan readableSpan) {
    correlator.sendOrBufferSpan(readableSpan);
  }

  @Override
  protected CompletableResultCode doShutdown() {
    try {
      ActivationListener.setProcessor(null);
      UniversalProfilingCorrelation.reset();
      anyInstanceActive = false;
      messagePollAndSpanFlushExecutor.shutdown();
      try {
        messagePollAndSpanFlushExecutor.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        log.log(Level.WARNING, "Could not wait for executor termination", e);
      }
      // Consume remaining messages
      consumeProfilerMessages();
      correlator.shutdownAndFlushAll();
      return CompletableResultCode.ofSuccess();
    } finally {
      UniversalProfilingCorrelation.stopProfilerReturnChannel();
    }
  }

  @Override
  protected boolean requiresStart() {
    return true;
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  @Nullable
  private void onContextChange(@Nullable Context previous, @Nullable Context next) {
    try {
      Span oldSpan = safeSpanFromContext(previous);
      Span newSpan = safeSpanFromContext(next);
      if (oldSpan != newSpan && !oldSpan.getSpanContext().equals(newSpan.getSpanContext())) {
        ProfilerSharedMemoryWriter.updateThreadCorrelationStorage(newSpan);
      }
    } catch (Throwable t) {
      log.log(Level.SEVERE, "Error on context update", t);
    }
  }

  private Span safeSpanFromContext(@Nullable Context context) {
    if (context == null) {
      return Span.getInvalid();
    }
    return Span.fromContext(context);
  }

  // visible for testing
  synchronized void pollMessageAndFlushPendingSpans() {
    // Order is important: we only want to flush spans after we have consumed all pending messages
    // otherwise the data for the spans to be flushed might be incomplete
    consumeProfilerMessages();
    correlator.flushPendingDelayedSpans();
  }

  private void consumeProfilerMessages() {
    StringBuilder tempBuffer = new StringBuilder();
    try {
      while (true) {
        try {
          ProfilerMessage message =
              UniversalProfilingCorrelation.readProfilerReturnChannelMessage();
          if (message == null) {
            break;
          } else if (message instanceof TraceCorrelationMessage) {
            handleMessage((TraceCorrelationMessage) message, tempBuffer);
          } else {
            log.log(Level.FINE, "Received unknown message type from profiler: {0}", message);
          }
        } catch (DecodeException e) {
          log.log(Level.WARNING, "Failed to read profiler message", e);
          // intentionally no
        }
      }
    } catch (Exception e) {
      log.log(Level.SEVERE, "Cannot read from profiler socket", e);
    }
  }

  private void handleMessage(TraceCorrelationMessage message, StringBuilder tempBuffer) {
    tempBuffer.setLength(0);
    HexUtils.appendAsHex(message.getTraceId(), tempBuffer);
    String traceId = tempBuffer.toString();

    tempBuffer.setLength(0);
    HexUtils.appendAsHex(message.getLocalRootSpanId(), tempBuffer);
    String localRootSpanId = tempBuffer.toString();

    String stackTraceId =
        Base64.getUrlEncoder().withoutPadding().encodeToString(message.getStackTraceId());
    correlator.correlate(traceId, localRootSpanId, stackTraceId, message.getSampleCount());
  }

  private static class ActivationListener implements ContextStorage {

    static {
      // Ensures that this wrapper is registered EXACTLY once
      ContextStorage.addWrapper(ActivationListener::new);
    }

    @Nullable private static volatile UniversalProfilingProcessor processor = null;

    private final ContextStorage delegate;

    private ActivationListener(ContextStorage delegate) {
      this.delegate = delegate;
    }

    private static synchronized void setProcessor(
        @Nullable UniversalProfilingProcessor newProcessor) {
      if (newProcessor != null && processor != null) {
        throw new IllegalStateException("Only one processor can be registered at a time");
      }
      processor = newProcessor;
    }

    @Override
    public Scope attach(Context context) {
      UniversalProfilingProcessor currentProcessor = processor;
      if (currentProcessor != null) {
        Context previous = delegate.current();
        currentProcessor.onContextChange(previous, context);
        Scope delegateScope = delegate.attach(context);
        return () -> {
          UniversalProfilingProcessor proc = processor;
          if (proc != null) {
            proc.onContextChange(context, previous);
          }
          delegateScope.close();
        };
      } else {
        return delegate.attach(context);
      }
    }

    @Nullable
    @Override
    public Context current() {
      return delegate.current();
    }
  }
}
