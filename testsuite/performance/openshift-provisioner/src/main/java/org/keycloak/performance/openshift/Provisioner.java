package org.keycloak.performance.openshift;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.VersionInfo;
import io.fabric8.openshift.api.model.Project;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Provisioner {
   private static final String OPENSHIFT_REGISTRY = "OPENSHIFT_REGISTRY";
   private static final String OPENSHIFT_URL = "OPENSHIFT_URL";
   private static final String OPENSHIFT_PROJECT = "OPENSHIFT_PROJECT";
   private static final String OPENSHIFT_USER = "OPENSHIFT_USER";
   private static final String OPENSHIFT_PASSWORD = "OPENSHIFT_PASSWORD";
   private static final String OPENSHIFT_ADDRESS = "OPENSHIFT_ADDRESS";

   private static final boolean SKIP_NAMESPACE_RESET = true;

   private static final String PROJECT = envOptional(OPENSHIFT_PROJECT, "keycloak-test");
   private static final String REGISTRY = envOptional(OPENSHIFT_REGISTRY, "172.30.1.1:5000");
   private static final String EXT_ADDRESS = envRequired(OPENSHIFT_ADDRESS);

   private static final String DEFAULT_KEYCLOAK_JVM_MEMORY = "-Xms64m -Xmx2g -XX:MetaspaceSize=96M -XX:MaxMetaspaceSize=256m";
   private static final String DEFAULT_KEYCLOAK_JAVA_OPTS = " -Djava.net.preferIPv4Stack=true -Djboss.modules.system.pkgs=org.jboss.byteman -Djava.awt.headless=true";

   public static void main(String[] args) {
      if (args.length < 1) {
         throw new ProvisionerError("Too few arguments: use one of: 'create-project', 'define-resources', 'define-monitoring', 'collect-artifacts', 'scaledown'");
      }

      String openshiftUrl = envRequired(OPENSHIFT_URL);
      // TODO this does not work
      String user = envOptional(OPENSHIFT_USER, "developer");
      String password = envOptional(OPENSHIFT_PASSWORD, "");

      System.out.printf("Connecting to OpenShift master on %s as %s%n", openshiftUrl, user);

      Config config = new OpenShiftConfigBuilder()
         .withDisableHostnameVerification(true)
         .withMasterUrl(openshiftUrl).withUsername(user).withPassword(password).build();
      OpenShiftClient oc = new DefaultOpenShiftClient(config);
      try {
         try {
            VersionInfo version = oc.getVersion();
            System.out.printf("Connected to Openshift %s.%s as %s (%s)%n", version.getMajor(), version.getMinor(),
               oc.currentUser().getMetadata().getName(), oc.currentUser().getFullName());
         } catch (KubernetesClientException e) {
            throw new ProvisionerError("Failed to connect.", e);
         }
         try {
            switch (args[0]) {
               case "create-project":
                  createProject(oc);
                  break;
               case "define-resources":
                  defineResources(oc, args);
                  break;
               case "define-monitoring":
                  defineMonitoring(oc);
                  break;
               case "collect-artifacts":
                  collectArtifacts(oc, args);
                  break;
               case "scaledown":
                  scaledown(oc);
                  break;
               default:
                  throw new ProvisionerError("Unknown command '" + args[0] + "'");
            }
         } catch (KubernetesClientException e) {
            throw new ProvisionerError("Failed to provision", e);
         }
      } finally {
         System.out.println("Provisioner closing...");
         oc.close();
      }
   }

   private static void collectArtifacts(OpenShiftClient oc, String[] args) {
      String deployment = "unknown";
      if (args.length >= 2) {
         deployment = args[1];
      }
      String buildDir = ".";
      if (args.length >= 3) {
         buildDir = args[2];
      }
      String artifactsDir = buildDir + "/collected-artifacts/" + deployment + "-" + TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
      File dir = new File(artifactsDir);
      if (!dir.exists() && !dir.mkdirs()) {
         System.out.printf("Failed to create artifacts directory '%s'", dir.getAbsolutePath());
         return;
      }
      for (Pod pod : oc.pods().inNamespace(PROJECT).list().getItems()) {
         String name = pod.getMetadata().getName();
         System.out.printf("Writing down logs for pod '%s'%n", name);
         Reader logReader = oc.pods().inNamespace(PROJECT).withName(name).getLogReader();
         try {
            writeTo(logReader, artifactsDir + "/" + name + ".log");
         } catch (IOException e) {
            System.out.printf("Failed to write logs for pod '%s'%n", name);
         }
      }
   }

   private static void writeTo(Reader reader, String path) throws IOException {
      try (FileWriter writer = new FileWriter(path);) {
         char[] buf = new char[8192];
         int numRead;
         while ((numRead = reader.read(buf)) > 0) {
            writer.write(buf, 0, numRead);
         }
      }
   }

   private static void scaledown(OpenShiftClient oc) {
      scale(oc, "keycloak", 0);
      scale(oc, "mariadb", 0);
      scale(oc, "influxdb", 0);
      scale(oc, "cadvisor", 0);
      scale(oc, "grafana", 0);
      oc.pods().inNamespace(PROJECT).delete();
      try {
         new ReadyPodCounter(oc.pods().inNamespace(PROJECT), 0).await(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
         throw new ProvisionerError("Scaledown was interrupted");
      } catch (TimeoutException e) {
         throw new ProvisionerError("Scaledown timed out.");
      }
      System.out.println("Scaled down.");
   }

   private static void scale(OpenShiftClient oc, String keycloak, int replicas) {
      oc.deploymentConfigs().inNamespace(PROJECT).withName(keycloak).edit()
         .editSpec()
            .withReplicas(replicas)
         .endSpec()
         .done();
   }

   private static void defineResources(OpenShiftClient oc, String[] args) {
      String deployment = null;
      String inputPropertiesFile = null;
      String outputPropertiesFile = null;
      if (args.length >= 2) {
         deployment = args[1];
      }
      if (args.length >= 3) {
         inputPropertiesFile = args[2];
      }
      if (args.length >= 4) {
         outputPropertiesFile = args[3];
      }
      Properties inputProperties = new Properties();

      if (inputPropertiesFile != null) {
         try (FileInputStream fis = new FileInputStream(inputPropertiesFile)) {
            inputProperties.load(fis);
         } catch (FileNotFoundException e) {
            throw ProvisionerError.format("Properties file '%s' does not exist.");
         } catch (IOException e) {
            throw new ProvisionerError("Cannot load properties", e);
         }
      }

      defineDB(oc, inputProperties);
      defineKeycloak(oc, inputProperties);
      System.out.println("Resources created.");

      if (outputPropertiesFile != null) {
         System.out.printf("Writing down properties to %s%n", outputPropertiesFile);

         Properties outputProperties = new Properties();
         outputProperties.setProperty("deployment", deployment);
         outputProperties.setProperty("keycloak.frontend.servers", "http://keycloak." + EXT_ADDRESS + ".nip.io/auth");
         outputProperties.setProperty("keycloak.frontend.servers.jmx", "service:jmx:remote+http://keycloak-management." + EXT_ADDRESS + ".nip.io/auth");
         outputProperties.setProperty("keycloak.admin.user", inputProperties.getProperty("keycloak.admin.user", "admin"));
         outputProperties.setProperty("keycloak.admin.password", inputProperties.getProperty("keycloak.admin.password", "admin"));
         try (FileOutputStream fos = new FileOutputStream(outputPropertiesFile)) {
            outputProperties.store(fos, "Provisioned for Openshift");
         } catch (IOException e) {
            throw ProvisionerError.format(e, "Failed to store output properties to %s", outputPropertiesFile);
         }
      }
      System.out.println("Properties written.");
   }

   private static void defineDB(OpenShiftClient oc, Properties inputProperties) {
      String cpuLimit = inputProperties.getProperty("db.cpulimit", "500m");
      String memLimit = inputProperties.getProperty("db.memlimit", "256M");
      Resources resources = new Resources(cpuLimit, memLimit);
      oc.deploymentConfigs().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("mariadb")
         .endMetadata()
         .withNewSpec()
            .withReplicas(1)
            .withSelector(Collections.singletonMap("name", "mariadb"))
            .withNewTemplate()
               .withNewMetadata()
                  .addToLabels("name", "mariadb")
               .endMetadata()
               .withNewSpec()
                  .addNewContainer()
                     .withName("mariadb")
                     .withImage(REGISTRY + "/" + PROJECT + "/mariadb:latest")
                     .addNewEnv().withName("MYSQL_ROOT_PASSWORD").withValue("root").endEnv()
                     .addNewEnv().withName("MYSQL_DATABASE").withValue("keycloak").endEnv()
                     .addNewEnv().withName("MYSQL_USER").withValue("keycloak").endEnv()
                     .addNewEnv().withName("MYSQL_PASSWORD").withValue("keycloak").endEnv()
                     .addNewEnv().withName("MYSQL_INITDB_SKIP_TZINFO").withValue("1").endEnv()
                     .addNewPort().withProtocol("TCP").withContainerPort(3306).endPort()
                     .withNewReadinessProbe()
                        .withNewExec()
                           .withCommand("mariadb-healthcheck.sh")
                        .endExec()
                     .endReadinessProbe()
                     .withNewResources()
                        .withRequests(resources.toMap())
                        .withLimits(resources.toMap())
                     .endResources()
                     .addNewVolumeMount()
                        .withName("data")
                        .withMountPath("/var/lib/mysql")
                     .endVolumeMount()
                  .endContainer()
                  .addNewVolume()
                     .withName("data")
                     .withNewEmptyDir()
                     .endEmptyDir()
                  .endVolume()
               .endSpec()
            .endTemplate()
            .addNewTrigger()
               .withType("ConfigChange")
            .endTrigger()
         .endSpec()
         .done();
      oc.services().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("mariadb")
         .endMetadata()
         .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Collections.singletonMap("name", "mariadb"))
            .addNewPort()
               .withName("mariadb-3306")
               .withProtocol("TCP")
               .withPort(3306)
               .withNewTargetPort(3306)
            .endPort()
         .endSpec()
         .done();
//      oc.routes().inNamespace(PROJECT).createOrReplaceWithNew()
//         .withNewMetadata()
//            .withName("mariadb")
//         .endMetadata()
//         .withNewSpec()
//            .withHost("mariadb." + EXT_ADDRESS + ".nip.io")
//            .withNewPort().withNewTargetPort("mariadb-3306").endPort()
//            .withNewTo()
//               .withKind("Service")
//               .withName("mariadb")
//            .endTo()
//         .endSpec()
//         .done();

      System.out.println("Waiting for MariaDB to start");
      try {
         new ReadyPodCounter(oc.pods().inNamespace(PROJECT).withLabel("name", "mariadb"), 1).await(1, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
         throw new ProvisionerError("Interrupted waiting for MariaDB to start up.");
      } catch (TimeoutException e) {
         throw new ProvisionerError("Timed out waiting for MariaDB to start up.");
      }
   }

   private static void defineKeycloak(OpenShiftClient oc, Properties inputProperties) {
      String cpuLimit = inputProperties.getProperty("keycloak.cpulimit", "500m");
      String memLimit = inputProperties.getProperty("keycloak.memlimit", "1G");
      Resources resources = new Resources(cpuLimit, memLimit);
      oc.deploymentConfigs().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("keycloak")
         .endMetadata()
         .withNewSpec()
            .withReplicas(Integer.parseInt(inputProperties.getProperty("keycloak.scale", "1")))
            .withSelector(Collections.singletonMap("name", "keycloak"))
            .withNewTemplate()
               .withNewMetadata()
                  .addToLabels("name", "keycloak")
               .endMetadata()
               .withNewSpec()
                  .addNewContainer()
                     .withName("keycloak")
                     .withImage(REGISTRY + "/" + PROJECT + "/keycloak:latest")
                     .addNewEnv().withName("MARIADB_HOSTS").withValue("mariadb:3306").endEnv()
                     .addNewEnv().withName("MARIADB_DATABASE").withValue("keycloak").endEnv()
                     .addNewEnv().withName("MARIADB_USER").withValue("keycloak").endEnv()
                     .addNewEnv().withName("MARIADB_PASSWORD").withValue("keycloak").endEnv()
                     .addNewEnv().withName("KEYCLOAK_ADMIN_USER").withValue(inputProperties.getProperty("keycloak.admin.user", "admin")).endEnv()
                     .addNewEnv().withName("KEYCLOAK_ADMIN_PASSWORD").withValue(inputProperties.getProperty("keycloak.admin.password", "admin")).endEnv()
                     .addNewEnv().withName("JAVA_OPTS").withValue(inputProperties.getProperty("keycloak.jvm.memory", DEFAULT_KEYCLOAK_JVM_MEMORY) + DEFAULT_KEYCLOAK_JAVA_OPTS).endEnv()
                     .addNewEnv().withName("HTTP_MAX_CONNECTIONS").withValue(inputProperties.getProperty("keycloak.http.max-connections", "50000")).endEnv()
                     .addNewEnv().withName("AJP_MAX_CONNECTIONS").withValue(inputProperties.getProperty("keycloak.ajp.max-connections", "50000")).endEnv()
                     .addNewEnv().withName("WORKER_IO_THREADS").withValue(inputProperties.getProperty("keycloak.worker.io-threads", "2")).endEnv()
                     .addNewEnv().withName("WORKER_TASK_MAX_THREADS").withValue(inputProperties.getProperty("keycloak.worker.task-max-threads", "16")).endEnv()
                     .addNewEnv().withName("DS_MIN_POOL_SIZE").withValue(inputProperties.getProperty("keycloak.ds.min-pool-size", "10")).endEnv()
                     .addNewEnv().withName("DS_MAX_POOL_SIZE").withValue(inputProperties.getProperty("keycloak.ds.max-pool-size", "100")).endEnv()
                     .addNewEnv().withName("DS_POOL_PREFILL").withValue(inputProperties.getProperty("keycloak.ds.pool-prefill", "true")).endEnv()
                     .addNewEnv().withName("DS_PS_CACHE_SIZE").withValue(inputProperties.getProperty("keycloak.ds.ps-cache-size", "100")).endEnv()
                     .addNewPort().withProtocol("TCP").withContainerPort(8080).endPort()
                     .addNewPort().withProtocol("TCP").withContainerPort(9990).endPort()
                     .withNewReadinessProbe()
                        .withNewHttpGet()
                           .withNewPort(8080)
                           .withPath("/auth/realms/master")
                        .endHttpGet()
                     .endReadinessProbe()
                     .withNewResources()
                        .withRequests(resources.toMap())
                        .withLimits(resources.toMap())
                     .endResources()
                  .endContainer()
               .endSpec()
            .endTemplate()
            .addNewTrigger()
               .withType("ConfigChange")
            .endTrigger()
         .endSpec()
         .done();
      oc.services().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("keycloak")
         .endMetadata()
         .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Collections.singletonMap("name", "keycloak"))
            .addNewPort()
               .withName("http-8080")
               .withProtocol("TCP")
               .withPort(8080)
               .withNewTargetPort(8080)
            .endPort()
            .addNewPort()
               .withName("http-9990")
               .withProtocol("TCP")
               .withPort(9990)
               .withNewTargetPort(9990)
            .endPort()
         .endSpec()
         .done();
      oc.routes().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("keycloak")
         .endMetadata()
         .withNewSpec()
            .withHost("keycloak." + EXT_ADDRESS + ".nip.io")
            .withNewPort().withNewTargetPort("http-8080").endPort()
            .withNewTo()
               .withKind("Service")
               .withName("keycloak")
            .endTo()
         .endSpec()
         .done();
      oc.routes().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("keycloak-management")
         .endMetadata()
         .withNewSpec()
            .withHost("keycloak-management." + EXT_ADDRESS + ".nip.io")
            .withNewPort().withNewTargetPort("http-9990").endPort()
            .withNewTo()
               .withKind("Service")
               .withName("keycloak")
            .endTo()
         .endSpec()
         .done();

      System.out.println("Waiting for Keycloak to start");
      try {
         new ReadyPodCounter(oc.pods().inNamespace(PROJECT).withLabel("name", "keycloak"), 1).await(2, TimeUnit.MINUTES);
      } catch (InterruptedException e) {
         throw new ProvisionerError("Interrupted waiting for Keycloak to start up.");
      } catch (TimeoutException e) {
         throw new ProvisionerError("Timed out waiting for Keycloak to start up.");
      }
   }

   private static void defineMonitoring(OpenShiftClient oc) {
      oc.deploymentConfigs().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("influxdb")
         .endMetadata()
         .withNewSpec()
            .withReplicas(1)
            .withSelector(Collections.singletonMap("name", "influxdb"))
            .withNewTemplate()
               .withNewMetadata()
                  .withName("influxdb")
                  .addToLabels("name", "influxdb")
               .endMetadata()
               .withNewSpec()
                  .addNewContainer()
                     .withName("influxdb")
                     .withImage("docker.io/influxdb")
                     .addNewVolumeMount()
                        .withName("influx")
                        .withMountPath("/var/lib/influxdb")
                     .endVolumeMount()
                     .addNewPort().withProtocol("TCP").withContainerPort(8086).endPort()
                  .endContainer()
                  .addNewVolume()
                     .withName("influx")
                     .withNewEmptyDir()
                     .endEmptyDir()
                  .endVolume()
               .endSpec()
            .endTemplate()
            .addNewTrigger()
               .withType("ConfigChange")
            .endTrigger()
         .endSpec()
         .done();
      oc.services().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("influxdb")
         .endMetadata()
         .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Collections.singletonMap("name", "influxdb"))
            .addNewPort()
               .withName("http-8086")
               .withProtocol("TCP")
               .withPort(8086)
               .withNewTargetPort(8086)
            .endPort()
         .endSpec()
         .done();

      // TODO: this needs elevated privileges
      oc.deploymentConfigs().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("cadvisor")
         .endMetadata()
         .withNewSpec()
            .withReplicas(1)
            .withSelector(Collections.singletonMap("name", "cadvisor"))
            .withNewTemplate()
               .withNewMetadata()
                  .withName("cadvisor")
                  .addToLabels("name", "cadvisor")
               .endMetadata()
               .withNewSpec()
                  .addNewContainer()
                     .withName("cadvisor")
                     .withImage(REGISTRY + "/" + PROJECT + "/monitoring_cadvisor")
                     .withArgs("--storage_driver_buffer_duration=\"5s\"")
                     .addNewEnv().withName("INFLUX_HOST").withValue("influxdb").endEnv()
                     .addNewEnv().withName("INFLUX_DATABASE").withValue("cadvisor").endEnv()
                     .addNewVolumeMount()
                        .withName("rootfs")
                        .withMountPath("/rootfs")
                        .withReadOnly(true)
                     .endVolumeMount()
                     .addNewVolumeMount()
                        .withName("varrun")
                        .withMountPath("/var/run")
                     .endVolumeMount()
                     .addNewVolumeMount()
                        .withName("sys")
                        .withMountPath("/sys")
                        .withReadOnly(true)
                     .endVolumeMount()
                     .addNewVolumeMount()
                        .withName("varlibdocker")
                        .withMountPath("/var/lib/docker")
                        .withReadOnly(true)
                     .endVolumeMount()
                     .addNewPort().withProtocol("TCP").withHostPort(8087).withContainerPort(8080).endPort()
                  .endContainer()
                  .addNewVolume()
                     .withName("rootfs")
                     .withNewEmptyDir().endEmptyDir() // TODO!
                  .endVolume()
                  .addNewVolume()
                     .withName("varrun")
                     .withNewEmptyDir().endEmptyDir() // TODO!
                  .endVolume()
                  .addNewVolume()
                     .withName("sys")
                     .withNewEmptyDir().endEmptyDir() // TODO!
                  .endVolume()
                  .addNewVolume()
                     .withName("varlibdocker")
                     .withNewEmptyDir().endEmptyDir() // TODO!
                  .endVolume()
               .endSpec()
            .endTemplate()
            .addNewTrigger()
               .withType("ConfigChange")
            .endTrigger()
         .endSpec()
         .done();
      oc.services().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("cadvisor")
         .endMetadata()
         .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Collections.singletonMap("name", "cadvisor"))
            .addNewPort()
            .withName("http-8087")
               .withProtocol("TCP")
               .withPort(8087)
               .withNewTargetPort(8087)
            .endPort()
         .endSpec()
         .done();
      oc.routes().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("cadvisor")
         .endMetadata()
         .withNewSpec()
            .withHost("cadvisor." + EXT_ADDRESS + ".nip.io")
            .withNewPort().withNewTargetPort("http-8087").endPort()
            .withNewTo()
               .withKind("Service")
               .withName("cadvisor")
            .endTo()
         .endSpec()
         .done();

      oc.deploymentConfigs().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("grafana")
         .endMetadata()
         .withNewSpec()
            .withReplicas(1)
            .withSelector(Collections.singletonMap("name", "grafana"))
            .withNewTemplate()
               .withNewMetadata()
                  .withName("grafana")
                  .addToLabels("name", "grafana")
               .endMetadata()
               .withNewSpec()
                  .addNewContainer()
                     .withName("grafana")
                     .withImage(REGISTRY + "/" + PROJECT + "/monitoring_grafana")
                        .addNewEnv().withName("INFLUX_HOST").withValue("influxdb").endEnv()
                        .addNewEnv().withName("INFLUX_DATABASE").withValue("cadvisor").endEnv()
                        .addNewEnv().withName("INFLUX_DATASOURCE_NAME").withValue("influxdb_cadvisor").endEnv()
                        .addNewVolumeMount()
                        .withName("grafana")
                        .withMountPath("/var/lib/grafana")
                     .endVolumeMount()
                     .addNewPort().withProtocol("TCP").withContainerPort(3000).endPort()
                  .endContainer()
                  .addNewVolume()
                     .withName("grafana")
                     .withNewEmptyDir().endEmptyDir()
                  .endVolume()
               .endSpec()
            .endTemplate()
            .addNewTrigger()
               .withType("ConfigChange")
            .endTrigger()
         .endSpec()
         .done();
      oc.services().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("grafana")
         .endMetadata()
         .withNewSpec()
            .withType("ClusterIP")
            .withSelector(Collections.singletonMap("name", "grafana"))
            .addNewPort()
            .withName("http-3000")
               .withProtocol("TCP")
               .withPort(3000)
               .withNewTargetPort(3000)
            .endPort()
         .endSpec()
         .done();
      oc.routes().inNamespace(PROJECT).createOrReplaceWithNew()
         .withNewMetadata()
            .withName("grafana")
         .endMetadata()
         .withNewSpec()
            .withHost("grafana." + EXT_ADDRESS + ".nip.io")
            .withNewPort().withNewTargetPort("http-3000").endPort()
            .withNewTo()
               .withKind("Service")
               .withName("grafana")
            .endTo()
         .endSpec()
         .done();
   }


   private static void createProject(OpenShiftClient oc) {
      boolean projectExists = oc.projects().list().getItems().stream()
         .map(p -> p.getMetadata().getName()).anyMatch(PROJECT::equals);

      if (projectExists) {
         if (SKIP_NAMESPACE_RESET) {
            System.out.println("Project already exists, nothing to do.");
            return;
         }
         System.out.printf("Deleting existing project %s%n", PROJECT);
         // TODO: https://github.com/fabric8io/kubernetes-client/issues/1163
         DeleteWatcher<Project> watcher = new DeleteWatcher<>(oc.projects());
         oc.projects().withName(PROJECT).delete();
         try {
            watcher.await(60, TimeUnit.SECONDS);
         } catch (InterruptedException e) {
            throw new ProvisionerError("Delete was interrupted", e);
         } catch (TimeoutException e) {
            throw new ProvisionerError("Delete timed out", e);
         }
      }

      System.out.printf("Creating new project %s%n", PROJECT);
      oc.projectrequests().createNew()
         .withNewMetadata()
         .withName(PROJECT)
         .endMetadata()
         .done();
      System.out.println("Project created.");
   }

   private static String envOptional(String property, String defaultValue) {
      String value = System.getenv().get(property);
      if (value == null) {
         System.out.printf("Environment property %s was not set, using default value: %s%n", property, defaultValue);
         return defaultValue;
      }
      return value;
   }

   private static String envRequired(String property) {
      String value = System.getenv(property);
      if (value == null) {
         throw ProvisionerError.format("Environment property %s was not set.", property);
      }
      return value;
   }

}
