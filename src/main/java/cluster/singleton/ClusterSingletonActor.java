package cluster.singleton;

import akka.actor.AbstractLoggingActor;
import akka.actor.Props;

class ClusterSingletonActor extends AbstractLoggingActor {
    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(ClusterSingletonMessages.Ping.class, this::ping)
                .build();
    }

    private void ping(ClusterSingletonMessages.Ping ping) {
        log().debug("Ping({}) <- {}", ping.id, getSender());
        getSender().tell(new ClusterSingletonMessages.Pong(ping.id), getSelf());
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
