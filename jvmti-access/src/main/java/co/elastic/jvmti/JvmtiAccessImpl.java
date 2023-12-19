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

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JvmtiAccessImpl {

  private static final Logger logger = Logger.getLogger(JvmtiAccessImpl.class.getName());

  static volatile List<VirtualThreadMountCallback> threadMountCallbacks = Collections.emptyList();

  public static native int init0();

  public static native int destroy0();


  public static native int getStackTrace0(int skipFrames, int maxFrames, long[] result);

  public static native Class<?> getDeclaringClass0(long methodId);

  public static native String getMethodName0(long methodId, boolean appendSignature);

  public static native String checkVirtualThreadMountEventSupport0();

  public static native int enableVirtualThreadMountEvents0();

  public static native int disableVirtualThreadMountEvents0();


  @SuppressWarnings("unused") // called from native code
  static void onThreadMount(Thread thread) {
    List<VirtualThreadMountCallback> cbs = threadMountCallbacks;
    for (VirtualThreadMountCallback callback : cbs) {
      try {
        callback.threadMounted(thread);
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Thread mount callback threw exception", t);
      }
    }
  }

  @SuppressWarnings("unused") // called from native code
  static void onThreadUnmount(Thread thread) {
    List<VirtualThreadMountCallback> cbs = threadMountCallbacks;
    for (VirtualThreadMountCallback callback : cbs) {
      try {
        callback.threadUnmounted(thread);
      } catch (Throwable t) {
        logger.log(Level.SEVERE, "Thread unmount callback threw exception", t);
      }
    }
  }
}
