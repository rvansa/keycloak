package org.keycloak.performance.openshift;

import io.fabric8.kubernetes.client.Watch;
import okhttp3.OkHttpClient;

import java.lang.reflect.Field;

public class Util {
   static void closeDispatcher(Watch watch) {
      try {
         Field okhttpClientField = watch.getClass().getDeclaredField("okhttpClient");
         okhttpClientField.setAccessible(true);
         OkHttpClient okhttpClient = (OkHttpClient) okhttpClientField.get(watch);
         okhttpClient.dispatcher().executorService().shutdownNow();
      } catch (IllegalAccessException e) {
      } catch (NoSuchFieldException e) {
      }
   }
}
