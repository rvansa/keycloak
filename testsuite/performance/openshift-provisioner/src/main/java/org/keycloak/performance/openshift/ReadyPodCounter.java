package org.keycloak.performance.openshift;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Listable;
import io.fabric8.kubernetes.client.dsl.Watchable;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ReadyPodCounter implements Watcher<Pod> {
   private final Watch watch;
   private final CountDownLatch latch = new CountDownLatch(1);
   private final int expectedPods;
   private final Listable<PodList> pods;

   public <T extends Listable<PodList> & Watchable<Watch, Watcher<Pod>>> ReadyPodCounter(T pods, int expectedPods) {
      this.pods = pods;
      this.expectedPods = expectedPods;
      watch = pods.watch(this);
      checkReady();
   }

   private void checkReady() {
      if (currentReadyPods(pods) == expectedPods) {
         latch.countDown();
         watch.close();
         Util.closeDispatcher(watch);
      }
   }

   private long currentReadyPods(Listable<PodList> pods) {
      return pods.list().getItems().stream().filter(pod -> Readiness.isPodReady(pod)).count();
   }

   @Override
   public void eventReceived(Action action, Pod resource) {
      checkReady();
   }

   @Override
   public void onClose(KubernetesClientException cause) {
      latch.countDown();
   }

   public void await(long time, TimeUnit timeUnit) throws InterruptedException, TimeoutException {
      try {
         if (!latch.await(time, timeUnit)) {
            throw new TimeoutException();
         }
      } finally {
         watch.close();
         Util.closeDispatcher(watch);
      }
   }
}
