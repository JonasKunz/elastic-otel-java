package co.elastic.otel.waitspans;

import co.elastic.jvmti.JvmtiAccess;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BlockingMethodNamer {

  private static final List<Pattern> IGNORE_PATTERNS = Stream.<String>of(
          "sun\\..*",
          "java\\.lang\\.VirtualThread\\..*",
          "java\\.util\\.concurrent\\.locks\\.LockSupport\\..*",
          "java\\.util\\.concurrent\\.CompletableFuture\\..*",
          "java\\.util\\.concurrent\\.ForkJoinPool\\.unmanagedBlock",
          "java\\.util\\.concurrent\\.ForkJoinPool\\.managedBlock",
          "java\\.util\\.concurrent\\.locks\\.AbstractQueuedSynchronizer\\..*",
          "java\\.util\\.concurrent\\.locks\\.ReentrantLock$Sync\\..*",
          "java\\.util\\.concurrent\\.CompletableFuture\\..*",
          "java\\.util\\.concurrent\\..*\\$.*\\..*",
          ".*park.*"
      ).map(Pattern::compile)
      .collect(Collectors.toList());
  private static final Set<Long> knownIgnoredMethods = new HashSet<>();


  public static String getBlockerMethod(long[] stackTrace, int numStackFrames) {
    for (int i = 0; i < numStackFrames; i++) {
      long methodId = stackTrace[i];
      if (!knownIgnoredMethods.contains(methodId)) {
        Class<?> clazz = JvmtiAccess.getDeclaringClass(methodId);
        String methodName = JvmtiAccess.getMethodName(methodId, false);
        if (methodName != null && clazz != null) {
          String combined = clazz.getName() + "." + methodName;
          if (!isIgnored(combined, methodId)) {
            return combined;
          }
        }
      }
    }
    return null;
  }

  private static boolean isIgnored(String methodName, long methodId) {
    for (Pattern ignored : IGNORE_PATTERNS) {
      if (ignored.matcher(methodName).matches()) {
        knownIgnoredMethods.add(methodId);
        return true;
      }
    }
    return false;
  }
}
