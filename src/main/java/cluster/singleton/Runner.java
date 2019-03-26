package cluster.singleton;

import akka.Done;
import akka.actor.*;
import akka.cluster.singleton.ClusterSingletonManager;
import akka.cluster.singleton.ClusterSingletonManagerSettings;
import akka.cluster.singleton.ClusterSingletonProxy;
import akka.cluster.singleton.ClusterSingletonProxySettings;
import akka.management.javadsl.AkkaManagement;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Runner {
    public static void main(String[] args) {
        if (args.length == 0) {
            startupClusterNodes(Arrays.asList("2551", "2552", "0"));
        } else {
            startupClusterNodes(Arrays.asList(args));
        }
    }

    private static void startupClusterNodes(List<String> ports) {
        System.out.printf("Start cluster on port(s) %s%n", ports);

        ports.forEach(port -> {
            ActorSystem actorSystem = ActorSystem.create("singleton", setupClusterNodeConfig(port));

            AkkaManagement.get(actorSystem).start();

            actorSystem.actorOf(ClusterListenerActor.props(), "clusterListener");
            createClusterSingletonManagerActor(actorSystem);
            actorSystem.actorOf(ClusterSingletonAwareActor.props(createClusterSingletonProxyActor(actorSystem)), "clusterSingletonAware");

            addCoordinatedShutdownTask(actorSystem, CoordinatedShutdown.PhaseClusterShutdown());

            actorSystem.log().info("Akka node {}", actorSystem.provider().getDefaultAddress());
        });
    }

    private static Config setupClusterNodeConfig(String port) {
        return ConfigFactory.parseString(
                String.format("akka.remote.netty.tcp.port=%s%n", port) +
                        String.format("akka.remote.artery.canonical.port=%s%n", port))
                .withFallback(ConfigFactory.load());
    }

    private static void createClusterSingletonManagerActor(ActorSystem actorSystem) {
        Props clusterSingletonManagerProps = ClusterSingletonManager.props(
                ClusterSingletonActor.props(),
                PoisonPill.getInstance(),
                ClusterSingletonManagerSettings.create(actorSystem)
        );

        actorSystem.actorOf(clusterSingletonManagerProps, "clusterSingletonManager");
    }

    private static ActorRef createClusterSingletonProxyActor(ActorSystem actorSystem) {
        Props clusterSingletonProxyProps = ClusterSingletonProxy.props(
                "/user/clusterSingletonManager",
                ClusterSingletonProxySettings.create(actorSystem)
        );

        return actorSystem.actorOf(clusterSingletonProxyProps, "clusterSingletonProxy");
    }

    private static void addCoordinatedShutdownTask(ActorSystem actorSystem, String coordindateShutdownPhase) {
        CoordinatedShutdown.get(actorSystem).addTask(
                coordindateShutdownPhase,
                coordindateShutdownPhase,
                () -> {
                    actorSystem.log().warning("Coordinated shutdown phase {}", coordindateShutdownPhase);
                    return CompletableFuture.completedFuture(Done.getInstance());
                });
    }
}
