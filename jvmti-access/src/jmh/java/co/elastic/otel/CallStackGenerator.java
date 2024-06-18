package co.elastic.otel;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;

public class CallStackGenerator {

  public static CallStackInvoker generateCallstackInvoker(int depth) {

    Method run;
    try {
      run = Runnable.class.getMethod("run");
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(e);
    }
    DynamicType.Unloaded<CallStackInvoker> topInvoker = new ByteBuddy().subclass(
            CallStackInvoker.class)
        .name("co.elastic.otel.InvokerClass" + depth)
        .defineMethod("invokerMethod" + depth, void.class, Modifier.PUBLIC | Modifier.STATIC)
        .withParameters(Runnable.class)
        .intercept(MethodCall.invoke(run).onArgument(0))
        .defineMethod("run", void.class, Modifier.PUBLIC)
        .withParameters(Runnable.class)
        .intercept(
            MethodCall.invoke(ElementMatchers.nameStartsWith("invokerMethod")).withArgument(0))
        .make();

    for (int i = depth - 1; i > 0; i--) {

      MethodDescription nextMethod = null;
      for (MethodDescription.InDefinedShape meth : topInvoker.getTypeDescription()
          .getDeclaredMethods()) {
        if (meth.getName().startsWith("invokerMethod")) {
          nextMethod = meth;
          break;
        }
      }

      DynamicType.Unloaded<CallStackInvoker> nextInvoker = new ByteBuddy().subclass(
              CallStackInvoker.class)
          .name("co.elastic.otel.InvokerClass" + i)
          .defineMethod("invokerMethod" + i, void.class, Modifier.PUBLIC | Modifier.STATIC)
          .withParameters(Runnable.class)
          .intercept(MethodCall.invoke(nextMethod).withAllArguments())
          .defineMethod("run", void.class, Modifier.PUBLIC)
          .withParameters(Runnable.class)
          .intercept(
              MethodCall.invoke(ElementMatchers.nameStartsWith("invokerMethod")).withArgument(0))
          .make()
          .include(topInvoker);
      topInvoker = nextInvoker;
    }
    DynamicType.Loaded<CallStackInvoker> loaded = topInvoker.load(
        CallStackGenerator.class.getClassLoader(), ClassLoadingStrategy.Default.WRAPPER);
    try {
      return loaded.getLoaded().getConstructor().newInstance();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
