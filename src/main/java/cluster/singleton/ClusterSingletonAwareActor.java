package cluster.singleton;

import akka.actor.AbstractLoggingActor;
import akka.actor.ActorRef;
import akka.actor.Cancellable;
import akka.actor.Props;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

class ClusterSingletonAwareActor extends AbstractLoggingActor {
    private final ActorRef clusterSingletonProxy;
    private final FiniteDuration tickInterval = Duration.create(5, TimeUnit.SECONDS);
    private Cancellable ticker;
    private Message.Ping ping;

    private ClusterSingletonAwareActor(ActorRef clusterSingletonProxy) {
        this.clusterSingletonProxy = clusterSingletonProxy;
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .matchEquals("tick", t -> tick())
                .match(Message.Pong.class, this::pong)
                .build();
    }

    private void tick() {
        ping = new Message.Ping();
        log().debug("{} -> {}", ping, clusterSingletonProxy);
        clusterSingletonProxy.tell(ping, self());
    }

    private void pong(Message.Pong pong) {
        log().debug("{} <- {}", pong, sender());
        if (ping.time != pong.pingTime) {
            log().warning("Pong id invalid, expected {}, actual {}", ping.time, pong.pingTime);
        }
    }

    @Override
    public void preStart() {
        log().debug("Start");
        ticker = context().system().scheduler()
                .schedule(Duration.Zero(),
                        tickInterval,
                        self(),
                        "tick",
                        context().system().dispatcher(),
                        null);
    }

    @Override
    public void postStop() {
        ticker.cancel();
        log().debug("Stop");
    }

    static Props props(ActorRef clusterSingletonProxy) {
        return Props.create(ClusterSingletonAwareActor.class, clusterSingletonProxy);
    }

    interface Message {
        class Ping implements Serializable {
            final long time;

            Ping() {
                time = System.nanoTime();
            }

            @Override
            public String toString() {
                return String.format("%s[%dus]", getClass().getSimpleName(), time);
            }
        }

        class Pong implements Serializable {
            final long pingTime;

            private Pong(long pingTime) {
                this.pingTime = pingTime;
            }

            static Pong from(Ping ping) {
                return new Pong(ping.time);
            }

            @Override
            public String toString() {
                return String.format("%s[elapsed %.9fs, %dus]", getClass().getSimpleName(), (System.nanoTime() - pingTime) / 1000000000.0, pingTime);
            }
        }
    }
}
