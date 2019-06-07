## Akka Java Cluster Singleton Example

### Introduction

This is a Java, Maven, Akka project that demonstrates how to setup a basic
[Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html). In this project the focus is on cluster singletons.

This project is one in a series of projects that starts with a simple Akka Cluster project and progressively builds up to examples of event sourcing and command query responsibility segregation.

The project series is composed of the following GitHub repos:
* [akka-java-cluster](https://github.com/mckeeh3/akka-java-cluster)
* [akka-java-cluster-aware](https://github.com/mckeeh3/akka-java-cluster-aware)
* [akka-java-cluster-singleton](https://github.com/mckeeh3/akka-java-cluster-singleton) (this project)
* [akka-java-cluster-sharding](https://github.com/mckeeh3/akka-java-cluster-sharding)
* [akka-java-cluster-persistence](https://github.com/mckeeh3/akka-java-cluster-persistence)
* [akka-java-cluster-persistence-query](https://github.com/mckeeh3/akka-java-cluster-persistence-query)

Each project can be cloned, built, and runs independently of the other projects.

This project contains an example implementation of a cluster. Here we will focus on cluster configuration and on running an Akka cluster with multiple nodes.

### About Akka Clustering Singletons

Let's start with a quick definition is what is a cluster singleton. From the
[cluster singleton](https://doc.akka.io/docs/akka/current/cluster-singleton.html#cluster-singleton)
Akka documentation - *For some use cases, it is convenient and sometimes also mandatory to ensure that you have exactly one actor of a certain type running somewhere in the cluster.* In the spirit of
[DRY](https://en.wikipedia.org/wiki/Don%27t_repeat_yourself)
see the
[Introduction](https://doc.akka.io/docs/akka/current/cluster-singleton.html#introduction)
section for a more complete description.

The Akka provided cluster singleton feature is interesting because it uses a set of out-of-the-box actors that handle the mechanics of routing messages from any node in the cluster to the current cluster singleton actor. The typical use case is other actors need to send messages to a singleton actor. The challenge is that the location of the singleton actor will change at any moment. Routing messages to a moving target across a distributed cluster requires some form of dynamic message routing. Fortunately, the routing of messages to the singleton is what the out-of-the-box Akka actors handle for us.

In this project, the example scenario is that on each node in the cluster there is an instance of an actor that sends ping messages to a singleton actor. There is also an instance of a singleton actor that receives these ping messages and replies with a pong message to the sender. These two actors are custom code, not out-of-the-box code.

~~~java
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
                final double elapsed = (System.nanoTime() - pingTime) / 1000000000.0;
                return String.format("%s[elapsed %.9fs, %dus]", getClass().getSimpleName(), elapsed, pingTime);
            }
        }
    }
}
~~~

The ClusterSingletonAwareActor, shown above, is the actor that sends the ping messages to the cluster singleton actor. Note that this actor is using a scheduler to send itself tick messages periodically. Scheduling was discussed in detail in the [cluster-aware project](https://github.com/mckeeh3/akka-java-cluster-aware).

Note that the constructor is passed an actor reference to what is called a clusterSingletonProxy, this is one of the two out-of-the-box cluster singleton Akka actors. The proxy actor handles the routing of messages from our ping actor to our singleton actor.

~~~java
private ClusterSingletonAwareActor(ActorRef clusterSingletonProxy) {
    this.clusterSingletonProxy = clusterSingletonProxy;
}
~~~
You may have noticed that the constructor is marked private. So where is the constructor invoked?

~~~java
static Props props(ActorRef clusterSingletonProxy) {
    return Props.create(ClusterSingletonAwareActor.class, clusterSingletonProxy);
}
~~~

Recall that the only way to access an actor is via asynchronous messages. The only way to create an instance of an actor is via the Akka `Props.create(...)` method. You pass the `'create(...)` static method the actor class and zero or more constructor parameters. When an actor instance is created, we'll see some examples of this soon, the `Props.create(...)` method is invoked, and it will invoke the class constructor.

~~~java
private void tick() {
    ping = new Message.Ping();
    log().debug("{} -> {}", ping, clusterSingletonProxy);
    clusterSingletonProxy.tell(ping, self());
}
~~~

Our actor delegates the responsibility for sending our ping messages to our cluster singleton actor to the provided cluster singleton proxy actor. When our actor receives a scheduled tick message the `tick()` method is invoked, it creates a ping message object and then sends the message to the cluster singleton actor.

~~~java
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
~~~

Our cluster singleton actor is quite simple. Incoming ping messages trigger a pong response message to the sender.

Note that the heavy lifting for message routing and making sure our singleton actor is only active one node is delegated to other actors.

We've seen one of the out-of-the-box cluster singleton actors, the proxy actor, but there is another actor that is needed to make things work, this is the `ClusterSingletonManager` actor.

The `ClusterSingletonManager` actor is responsible for creating the single instance of our cluster singleton actor. How this is done is described in the Akka documentation. Here we will take a look at how the `ClusterSingletonManager` and the `ClusterSingletonProxy` actors are set up.

~~~java
private static void createClusterSingletonManagerActor(ActorSystem actorSystem) {
    Props clusterSingletonManagerProps = ClusterSingletonManager.props(
            ClusterSingletonActor.props(),
            PoisonPill.getInstance(),
            ClusterSingletonManagerSettings.create(actorSystem)
    );

    actorSystem.actorOf(clusterSingletonManagerProps, "clusterSingletonManager");
}
~~~

The above method `createClusterSingletonManagerActor(...)` is in the `Runner` class, and it is used to create and an instance of a  ClusterSingletonManager actor. Note that our `ClusterSingletonActor.props()` method result is passed as one of the constructor parameters to the `ClusterSingletonManager.props(...)' method; this is used by the manager actor to create instances of our singleton actor as needed when it is necessary to move our singleton actor to another cluster node.

~~~java
private static ActorRef createClusterSingletonProxyActor(ActorSystem actorSystem) {
    Props clusterSingletonProxyProps = ClusterSingletonProxy.props(
            "/user/clusterSingletonManager",
            ClusterSingletonProxySettings.create(actorSystem)
    );

    return actorSystem.actorOf(clusterSingletonProxyProps, "clusterSingletonProxy");
}
~~~

An instance of the `ClusterSingletonProxy` is created in the `createClusterSingletonProxyActor(...)` method, which is also in the `Runner` class. Note that this method returns the actor reference to the proxy actor.

~~~java
actorSystem.actorOf(ClusterSingletonAwareActor.props(createClusterSingletonProxyActor(actorSystem)), "clusterSingletonAware");
~~~

The returned actor reference from the `createClusterSingletonProxyActor(...)` method is passed to the constructor of our `ClusterSingletonAwareActor` actor as an actor instance is created, shown above.

To sum it up, the sequence of steps is as follows.

In the `Runner` class an instance of the Akka provided `ClusterSingletonManager` is created, it is responsible for running a single instance of our singleton actor `ClusterSingletonActor' somewhere in the cluster.

Next, an instance of the Akka provided `ClusterSingletonProxy` is created, and the actor reference to the proxy is passed to an instance of our `ClusterSingletonAwareActor`.

The `ClusterSingletonAwareActor` periodically sends ping messages to our `ClusterSingletonActor' via the `ClusterSingletonProxy` actor. When the `ClusterSingletonActor' receives a ping message, it simply responds with a pong message.

All of the management of what node in the cluster is used to run our singleton actor and how to route messages to the singleton actor is handled not by our actors but is delegated to other actors.

Also note that the message routing and location selection of the singleton actor requires a level of cluster awareness, which was covered in the previous
[cluster aware project](https://github.com/mckeeh3/akka-java-cluster-aware).

### Installation

~~~~bash
git clone https://github.com/mckeeh3/akka-java-cluster-singleton.git
cd akka-java-cluster-singleton
mvn clean package
~~~~

The Maven command builds the project and creates a self contained runnable JAR.

### Run a cluster (Mac, Linux)

The project contains a set of scripts that can be used to start and stop individual cluster nodes or start and stop a cluster of nodes.

The main script `./akka` is provided to run a cluster of nodes or start and stop individual nodes.
Use `./akka node start [1-9] | stop` to start and stop individual nodes and `./akka cluster start [1-9] | stop` to start and stop a cluster of nodes.
The `cluster` and `node` start options will start Akka nodes on ports 2551 through 2559.
Both `stdin` and `stderr` output is sent to a file in the `/tmp` directory using the file naming convention `/tmp/<project-dir-name>-N.log`.

Start node 1 on port 2551 and node 2 on port 2552.
~~~bash
./akka node start 1
./akka node start 2
~~~

Stop node 3 on port 2553.
~~~bash
./akka node stop 3
~~~

Start a cluster of four nodes on ports 2551, 2552, 2553, and 2554.
~~~bash
./akka cluster start 4
~~~

Stop all currently running cluster nodes.
~~~bash
./akka cluster stop
~~~

You can use the `./akka cluster start [1-9]` script to start multiple nodes and then use `./akka node start [1-9]` and `./akka node stop [1-9]`
to start and stop individual nodes.

Use the `./akka node tail [1-9]` command to `tail -f` a log file for nodes 1 through 9.

The `./akka cluster status` command displays the status of a currently running cluster in JSON format using the
[Akka Management](https://developer.lightbend.com/docs/akka-management/current/index.html)
extension
[Cluster Http Management](https://developer.lightbend.com/docs/akka-management/current/cluster-http-management.html).

### Run a cluster (Windows, command line)

The following Maven command runs a signle JVM with 3 Akka actor systems on ports 2551, 2552, and a radmonly selected port.
~~~~bash
mvn exec:java
~~~~
Use CTRL-C to stop.

To run on specific ports use the following `-D` option for passing in command line arguements.
~~~~bash
mvn exec:java -Dexec.args="2551"
~~~~
The default no arguments is equilevalant to the following.
~~~~bash
mvn exec:java -Dexec.args="2551 2552 0"
~~~~
A common way to run tests is to start single JVMs in multiple command windows. This simulates running a multi-node Akka cluster.
For example, run the following 4 commands in 4 command windows.
~~~~bash
mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log
~~~~
~~~~bash
mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log
~~~~
This runs a 4 node Akka cluster starting 2 nodes on ports 2551 and 2552, which are the cluster seed nodes as configured and the `application.conf` file.
And 2 nodes on randomly selected port numbers.
The optional redirect `> /tmp/$(basename $PWD)-4.log` is an example for pushing the log output to filenames based on the project direcctory name.

For convenience, in a Linux command shell define the following aliases.

~~~~bash
alias p1='cd ~/akka-java/akka-java-cluster'
alias p2='cd ~/akka-java/akka-java-cluster-aware'
alias p3='cd ~/akka-java/akka-java-cluster-singleton'
alias p4='cd ~/akka-java/akka-java-cluster-sharding'
alias p5='cd ~/akka-java/akka-java-cluster-persistence'
alias p6='cd ~/akka-java/akka-java-cluster-persistence-query'

alias m1='clear ; mvn exec:java -Dexec.args="2551" > /tmp/$(basename $PWD)-1.log'
alias m2='clear ; mvn exec:java -Dexec.args="2552" > /tmp/$(basename $PWD)-2.log'
alias m3='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-3.log'
alias m4='clear ; mvn exec:java -Dexec.args="0" > /tmp/$(basename $PWD)-4.log'
~~~~

The p1-6 alias commands are shortcuts for cd'ing into one of the six project directories.
The m1-4 alias commands start and Akka node with the appropriate port. Stdout is also redirected to the /tmp directory.
