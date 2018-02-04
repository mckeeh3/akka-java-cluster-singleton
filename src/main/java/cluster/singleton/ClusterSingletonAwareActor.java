package cluster.singleton;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

class ClusterSingletonAwareActor extends AbstractLoggingActor {
    private final ActorRef clusterSingletonProxy;
    private final FiniteDuration tickInterval = Duration.create(10, TimeUnit.SECONDS);
    private Cancellable ticker;
    private int pingId;
    private int pongId;

    ClusterSingletonAwareActor(ActorRef clusterSingletonProxy) {
        this.clusterSingletonProxy = clusterSingletonProxy;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .matchEquals("tick", t -> tick())
                .match(ClusterSingletonMessages.Pong.class, this::pong)
                .build();
    }

    private void tick() {
        ++pingId;
        log().debug("ping({}) -> {}", pingId, clusterSingletonProxy);
        clusterSingletonProxy.tell(new ClusterSingletonMessages.Ping(pingId), getSelf());
    }

    private void pong(ClusterSingletonMessages.Pong pong) {
        log().debug("pong({}) <- {}", pong.id, getSender());
        if (++pongId != pong.id) {
            log().warning("Pong id invalid, expected {}, actual {}", pongId, pong.id);
        }
        pongId = pong.id;
    }

    @Override
    public void preStart() {
        log().debug("start");
        ticker = getContext().getSystem().scheduler()
                .schedule(Duration.Zero(),
                        tickInterval,
                        getSelf(),
                        "tick",
                        getContext().getSystem().dispatcher(),
                        null);
    }

    @Override
    public void postStop() {
        ticker.cancel();
        log().debug("stop");
    }

    static Props props(ActorRef clusterSingletonProxy) {
        return Props.create(ClusterSingletonAwareActor.class, clusterSingletonProxy);
    }
}
