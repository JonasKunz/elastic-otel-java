package co.elastic.otel.utils;

import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.io.IOUtils;

public class ChildFirstCopyClassloader extends ClassLoader {

  String childFirstClassName;

  public ChildFirstCopyClassloader(ClassLoader parent, String childFirstClassName) {
    super(parent);
    this.childFirstClassName = childFirstClassName;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      if (childFirstClassName.equals(name)) {
        Class<?> c = findLoadedClass(name);
        if (c == null) {
          try {
            String binaryName = name.replace('.', '/') + ".class";
            InputStream resourceAsStream = getParent().getResourceAsStream(binaryName);
            if (resourceAsStream == null) {
              throw new IllegalStateException(binaryName + " not found in parent classloader!");
            }
            byte[] bytecode = IOUtils.toByteArray(resourceAsStream);
            c = defineClass(name, bytecode, 0, bytecode.length);
            if (resolve) {
              resolveClass(c);
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        return c;
      } else {
        return super.loadClass(name, resolve);
      }
    }
  }
}
