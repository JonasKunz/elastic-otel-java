package co.elastic.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import co.elastic.otel.utils.ChildFirstCopyClassloader;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

public class StackTracesTest {

  public static class StackTrace {

    public StackTrace(long[] buffer, int numFrames) {
      this.buffer = buffer;
      this.numFrames = numFrames;
    }

    long[] buffer;
    int numFrames;
  }

  public static class StackTracerCreator implements Callable<StackTrace> {
    @Override
    public StackTrace call() throws Exception {
      long[] stackTrace = new long[1000];
      int numFrames = JvmtiAccess.getStackTrace(0, 1000, stackTrace);
      return new StackTrace(stackTrace, numFrames);
    }
  }


  private StackTrace getStackTraceAfterRecursion(int recursionDepth, int skipFrames,
      int maxFrames) {
    if (recursionDepth == 1) {
      long[] buffer = new long[maxFrames * 2];
      int numFrames = JvmtiAccess.getStackTrace(skipFrames, maxFrames, buffer);
      return new StackTrace(buffer, numFrames);
    } else {
      return getStackTraceAfterRecursion(recursionDepth - 1, skipFrames, maxFrames);
    }
  }

  @Test
  public void testStackTrace() {
    StackTrace result = getStackTraceAfterRecursion(5, 0, 200);

    assertThat(result.numFrames).isGreaterThan(6);
    for (int i = 0; i < 5; i++) {
      Class<?> declaringClass = JvmtiAccess.getDeclaringClass(result.buffer[i]);
      String signature = JvmtiAccess.getMethodName(result.buffer[i], true);
      assertThat(declaringClass).isSameAs(StackTracesTest.class);
      assertThat(signature).isEqualTo(
          "getStackTraceAfterRecursion(III)Lco/elastic/otel/StackTracesTest$StackTrace;");
    }

    Class<?> declaringClass = JvmtiAccess.getDeclaringClass(result.buffer[5]);
    String signature = JvmtiAccess.getMethodName(result.buffer[5], true);
    assertThat(declaringClass).isSameAs(StackTracesTest.class);
    assertThat(signature).isEqualTo("testStackTrace()V");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMethodResolutionAfterGC() throws Exception {
    ClassLoader childLoader = new ChildFirstCopyClassloader(
        StackTracerCreator.class.getClassLoader(), StackTracerCreator.class.getName());
    Class<?> creatorClass = childLoader.loadClass(StackTracerCreator.class.getName());

    StackTrace stackTrace = ((Callable<StackTrace>) creatorClass.getConstructor()
        .newInstance()).call();

    assertThat(stackTrace.numFrames).isGreaterThan(0);
    long methodId = stackTrace.buffer[0];
    assertThat(JvmtiAccess.getDeclaringClass(methodId)).isSameAs(creatorClass);

    WeakReference<Class<?>> creatorClassWeak = new WeakReference<>(creatorClass);
    childLoader = null;
    creatorClass = null;

    await().atMost(Duration.ofSeconds(10)).until(() -> {
      System.gc();
      return creatorClassWeak.get() == null;
    });

    assertThat(JvmtiAccess.getDeclaringClass(methodId)).isNull();
    assertThat(JvmtiAccess.getMethodName(methodId, true)).isNull();
    assertThat(JvmtiAccess.getMethodName(methodId, false)).isNull();
  }

}
