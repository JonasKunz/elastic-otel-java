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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mockito;

public class VirtualThreadMountEventsTest {

  @AfterEach
  public void destroyJvmtiAccess() {
    JvmtiAccess.destroy();
  }

  @Test
  @EnabledForJreRange(max = JRE.JAVA_19)
  void checkMissingVirtualThreadSupportDetected() {
    String unsupportedReason = JvmtiAccess.checkVirtualThreadMountEventSupport();
    assertThat(unsupportedReason).isNotNull();
    VirtualThreadMountCallback mockCallback = Mockito.mock(VirtualThreadMountCallback.class);
    assertThatThrownBy(() -> JvmtiAccess.addVirtualThreadMountCallback(mockCallback))
        .isInstanceOf(RuntimeException.class);
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_20)
  void checkEventListenersSupported() {
    String unsupportedReason = JvmtiAccess.checkVirtualThreadMountEventSupport();
    assertThat(unsupportedReason).isNull();
  }

  @Test
  @EnabledForJreRange(min = JRE.JAVA_21)
  void checkEventListenersWorking() throws Exception {

    Set<Thread> mountedThreads = Collections.newSetFromMap(new ConcurrentHashMap<>());
    Set<Thread> unmountedThreads = Collections.newSetFromMap(new ConcurrentHashMap<>());

    VirtualThreadMountCallback cb1 =
        new VirtualThreadMountCallback() {
          @Override
          public void threadMounted(Thread thread) {
            mountedThreads.add(thread);
          }

          @Override
          public void threadUnmounted(Thread thread) {
            unmountedThreads.add(thread);
          }
        };

    AtomicBoolean mountEventFound = new AtomicBoolean();
    AtomicBoolean unmountEventFound = new AtomicBoolean();

    JvmtiAccess.addVirtualThreadMountCallback(cb1);
    try {

      startVirtualThread(
          () -> {
            try {
              mountedThreads.clear();
              unmountedThreads.clear();
              Thread.sleep(10);
              mountEventFound.set(mountedThreads.contains(Thread.currentThread()));
              unmountEventFound.set(unmountedThreads.contains(Thread.currentThread()));
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          })
          .join();

    } finally {
      JvmtiAccess.removeVirtualThreadMountCallback(cb1);
    }

    assertThat(mountEventFound.get()).isTrue();
    assertThat(unmountEventFound.get()).isTrue();
  }

  Thread startVirtualThread(Runnable task) {
    try {
      return (Thread)
          Thread.class.getMethod("startVirtualThread", Runnable.class).invoke(null, task);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
