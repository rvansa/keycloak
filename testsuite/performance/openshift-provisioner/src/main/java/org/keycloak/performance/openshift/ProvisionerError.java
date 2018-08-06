package org.keycloak.performance.openshift;

public class ProvisionerError extends Error {
   public static ProvisionerError format(String fmt, Object... args) {
      return new ProvisionerError(String.format(fmt, args));
   }

   public static ProvisionerError format(Throwable t, String fmt, Object... args) {
      return new ProvisionerError(String.format(fmt, args), t);
   }

   public ProvisionerError(String message) {
      super(message);
   }

   public ProvisionerError(String message, Throwable cause) {
      super(message, cause);
   }
}
