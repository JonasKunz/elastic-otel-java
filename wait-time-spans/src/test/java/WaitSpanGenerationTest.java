import co.elastic.otel.waitspans.WaitSpanGeneratingProcessor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.semconv.ResourceAttributes;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

public class WaitSpanGenerationTest {


  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void generateSpans() throws Exception {
    Resource resource = Resource.builder()
        .put(ResourceAttributes.SERVICE_NAME, "virtual-threads-service")
        .build();
    WaitSpanGeneratingProcessor waitSpanGeneratingProcessor = new WaitSpanGeneratingProcessor(0L);
    try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
        .setResource(resource)
        .addSpanProcessor(waitSpanGeneratingProcessor)
        .addSpanProcessor(BatchSpanProcessor.builder(OtlpGrpcSpanExporter.builder()
            .setEndpoint(
                "https://9d7e5b3a00794f719f2141f2e7e668b2.apm.europe-west3.gcp.cloud.es.io:443")
            .addHeader("Authorization", "Bearer AJwX8DrUBJmG7aIagz")
            .build()
        ).build())
        .build()) {
      waitSpanGeneratingProcessor.setTracer(tracerProvider.get("virtual-thread-blockers"));

      Tracer tr = tracerProvider.get("manual-spans");

      doInSpan(tr, "my-transaction", () -> {

        runInVirtualThread(() ->
            doInSpan(tr, "I'm sleepy", () -> doSleep(200)));
        runInVirtualThread(() ->
            doInSpan(tr, "HttpClient stuff", this::runHttpClientCall));
        runInVirtualThread(() ->
            doInSpan(tr, "HttpUrlConnectionStuff stuff", this::runHttpUrlConnectionCall));

        ReentrantLock lock = new ReentrantLock();

        runInVirtualThread(() ->
            doInSpan(tr, "not blocked", () -> {
              lock.lock();
              lock.unlock();
            }));

        lock.lock();

        Thread thread = startVirtualThread(() ->
            doInSpan(tr, "blocked", () -> {
              lock.lock();
              lock.unlock();
            }));

        doSleep(100);
        lock.unlock();
        try {
          thread.join();
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }

      });
    }
  }

  private void doInSpan(Tracer tracer, String name, Runnable task) {
    Span span = tracer.spanBuilder(name)
        .startSpan();
    try (Scope scope = span.makeCurrent()) {
      task.run();
    } finally {
      span.end();
    }
  }

  private void doSleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private void runHttpClientCall() {

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("https://httpbin.org/delay/2"))
        .timeout(Duration.ofMinutes(2))
        .GET()
        .build();

    HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    try {
      HttpResponse<String> response = client.send(request,
          HttpResponse.BodyHandlers.ofString());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }


  private void runHttpUrlConnectionCall() {

    try {
      HttpURLConnection request = (HttpURLConnection) new URL(
          "https://httpbin.org/delay/2").openConnection();
      request.setRequestMethod("GET");
      request.getResponseCode();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  void runInVirtualThread(Runnable task) {
    try {
      startVirtualThread(task).join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  Thread startVirtualThread(Runnable task) {
    Context ctx = Context.current();
    if (ctx != null) {
      task = ctx.wrap(task);
    }
    try {
      return (Thread)
          Thread.class.getMethod("startVirtualThread", Runnable.class).invoke(null, task);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
