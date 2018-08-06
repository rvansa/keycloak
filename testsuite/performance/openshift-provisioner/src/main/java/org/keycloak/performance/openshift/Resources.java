package org.keycloak.performance.openshift;

import io.fabric8.kubernetes.api.model.Quantity;

import java.util.HashMap;
import java.util.Map;

public class Resources {
   private final String cpu;
   private final String memory;

   public Resources(String cpu, String memory) {
      this.cpu = cpu;
      this.memory = memory;
   }

   public Map<String, Quantity> toMap() {
      Map<String, Quantity> map = new HashMap<>();
      map.put("cpu", new Quantity(cpu));
      map.put("memory", new Quantity(memory));
      return map;
   }
}
