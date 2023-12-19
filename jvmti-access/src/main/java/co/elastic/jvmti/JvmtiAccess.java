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
package co.elastic.jvmti;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JvmtiAccess {

  private static final Logger logger = Logger.getLogger(JvmtiAccess.class.getName());

  private enum State {
    NOT_LOADED,
    LOAD_FAILED,
    LOADED,
    INITIALIZED,
    INITIALIZATION_FAILED,
    DESTROY_FAILED
  }

  private static volatile State state = State.NOT_LOADED;

  /**
   * @return null, if mound/unmount events are supported. A string containing the reason if not supported.
   */
  public static String checkVirtualThreadMountEventSupport() {
    assertInitialized();
    return JvmtiAccessImpl.checkVirtualThreadMountEventSupport0();
  }

  public static synchronized void addVirtualThreadMountCallback(VirtualThreadMountCallback cb) {
    Set<VirtualThreadMountCallback> newList = Collections.newSetFromMap(new IdentityHashMap<>());
    newList.addAll(JvmtiAccessImpl.threadMountCallbacks);
    if (newList.contains(cb)) {
      throw new IllegalArgumentException("Provided callback has already been added!");
    }
    newList.add(cb);

    if (JvmtiAccessImpl.threadMountCallbacks.isEmpty()) {
      assertInitialized();
      JvmtiAccessImpl.enableVirtualThreadMountEvents0();
    }
    JvmtiAccessImpl.threadMountCallbacks = new ArrayList<>(newList);
  }

  public static synchronized void removeVirtualThreadMountCallback(VirtualThreadMountCallback cb) {
    Set<VirtualThreadMountCallback> newList = Collections.newSetFromMap(new IdentityHashMap<>());
    if (JvmtiAccessImpl.threadMountCallbacks != null) {
      newList.addAll(JvmtiAccessImpl.threadMountCallbacks);
    }
    if (!newList.contains(cb)) {
      throw new IllegalArgumentException("Provided callback has not been added!");
    }
    newList.remove(cb);

    JvmtiAccessImpl.threadMountCallbacks = new ArrayList<>(newList);
    if (JvmtiAccessImpl.threadMountCallbacks.isEmpty()) {
      JvmtiAccessImpl.disableVirtualThreadMountEvents0();
    }
  }


  public static int getStackTrace(int skipFrames, int maxFrames, long[] buffer) {
    if (buffer.length < maxFrames) {
      throw new IllegalArgumentException("Provided buffer for stacktrace is too small!");
    }
    if (skipFrames < 0) {
      throw new IllegalArgumentException("skipFrames must be positive");
      //TODO: support negative skipFrames (counting from stack bottom instead of top)
    }
    if (maxFrames <= 0) {
      throw new IllegalArgumentException("maxFrames must be greater than zero");
    }
    assertInitialized();
    //we skip the frame of this method and of the native method, therefore + 2
    int numFrames = JvmtiAccessImpl.getStackTrace0(skipFrames + 2, maxFrames, buffer);
    if (numFrames < 0) {
      throw new RuntimeException("Native code returned error " + numFrames);
    }
    return numFrames;
  }

  public static Class<?> getDeclaringClass(long methodId) {
    assertInitialized();
    return JvmtiAccessImpl.getDeclaringClass0(methodId);
  }

  public static String getMethodName(long methodId, boolean appendSignature) {
    assertInitialized();
    return JvmtiAccessImpl.getMethodName0(methodId, appendSignature);
  }

  private static void assertInitialized() {
    switch (state) {
      case NOT_LOADED:
      case LOADED:
        doInit();
    }
    if (state != State.INITIALIZED) {
      throw new IllegalStateException("Agent could not be initialized");
    }
  }

  private static boolean checkInitialized() {
    switch (state) {
      case NOT_LOADED:
      case LOADED:
        try {
          doInit();
        } catch (Throwable t) {
          logger.log(Level.SEVERE, "Failed to initialize JVMTI agent", t);
        }
    }
    return state == State.INITIALIZED;
  }

  private static synchronized void doInit() {
    switch (state) {
      case NOT_LOADED:
        try {
          loadNativeLibrary();
          state = State.LOADED;
        } catch (Throwable t) {
          logger.log(Level.SEVERE, "Failed to load jvmti native library", t);
          state = State.LOAD_FAILED;
          return;
        }
      case LOADED:
        try {
          JvmtiAccessImpl.init0();
          state = State.INITIALIZED;
        } catch (Throwable t) {
          logger.log(Level.SEVERE, "Failed to initialize jvmti native library", t);
          state = State.INITIALIZATION_FAILED;
          return;
        }
    }
  }

  public static synchronized void destroy() {
    switch (state) {
      case INITIALIZED:
        try {
          JvmtiAccessImpl.destroy0();
          JvmtiAccessImpl.threadMountCallbacks = Collections.emptyList();
          state = State.LOADED;
        } catch (Throwable t) {
          logger.log(Level.SEVERE, "Failed to shutdown jvmti native library", t);
          state = State.DESTROY_FAILED;
        }
    }
  }

  private static void loadNativeLibrary() {
    String os = System.getProperty("os.name").toLowerCase();
    String arch = System.getProperty("os.arch").toLowerCase();
    String libraryName;
    if (os.contains("linux")) {
      if (arch.contains("arm") || arch.contains("aarch32")) {
        throw new IllegalStateException("Unsupported architecture for Linux: " + arch);
      } else if (arch.contains("aarch")) {
        libraryName = "linux-arm64";
      } else if (arch.contains("64")) {
        libraryName = "linux-x64";
      } else {
        throw new IllegalStateException("Unsupported architecture for Linux: " + arch);
      }
    } else if (os.contains("mac")) {
      if (arch.contains("aarch")) {
        libraryName = "darwin-arm64";
      } else {
        libraryName = "darwin-x64";
      }
    } else {
      throw new IllegalStateException("Native agent does not work on " + os);
    }

    String libraryDirectory = System.getProperty("java.io.tmpdir");
    libraryName = "elastic-jvmti-" + libraryName;
    Path file =
        ResourceExtractionUtil.extractResourceToDirectory(
            "elastic-jvmti/" + libraryName + ".so",
            libraryName,
            ".so",
            Paths.get(libraryDirectory));
    System.load(file.toString());
  }
}
