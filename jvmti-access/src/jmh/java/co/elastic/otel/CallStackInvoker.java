package co.elastic.otel;

public interface CallStackInvoker {
  void run(Runnable r);
}
