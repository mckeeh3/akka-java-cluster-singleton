package cluster.singleton;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;

class ClusterSingletonActor extends AbstractLoggingActor {
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ClusterSingletonAwareActor.Message.Ping.class, this::ping)
                .build();
    }

    private void ping(ClusterSingletonAwareActor.Message.Ping ping) {
        log().debug("{} <- {}", ping, sender());
        sender().tell(ClusterSingletonAwareActor.Message.Pong.from(ping), self());
    }

    @Override
    public void preStart() {
        log().debug("Start");
    }

    @Override
    public void postStop() {
        log().debug("Stop");
    }

    static Props props() {
        return Props.create(ClusterSingletonActor.class);
    }
}
