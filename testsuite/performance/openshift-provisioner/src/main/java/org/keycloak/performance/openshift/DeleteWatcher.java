package org.keycloak.performance.openshift;

import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.Watchable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class DeleteWatcher<T> implements Watcher<T> {
   private final CountDownLatch latch = new CountDownLatch(1);
   private final Watch watch;

   public DeleteWatcher(Watchable<Watch, Watcher<T>> watchable) {
      watch = watchable.watch(this);
   }

   @Override
   public void eventReceived(Action action, T resource) {
      switch (action) {
         case DELETED:
            latch.countDown();
      }
   }

   @Override
   public void onClose(KubernetesClientException cause) {
      latch.countDown();
   }

   public void await(long duration, TimeUnit timeUnit) throws InterruptedException, TimeoutException {
      try {
         if (!latch.await(duration, timeUnit)) {
            throw new TimeoutException();
         }
      } finally {
         watch.close();
      }
   }
}
