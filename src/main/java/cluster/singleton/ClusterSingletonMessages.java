package cluster.singleton;

import java.io.Serializable;

class ClusterSingletonMessages {
    static class Ping implements Serializable {
        final int id;

        Ping(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return String.format("%s[%d]", getClass().getSimpleName(), id);
        }
    }
    static class Pong implements Serializable {
        final int id;

        Pong(int id) {
            this.id = id;
        }

        @Override
        public String toString() {
            return String.format("%s[%d]", getClass().getSimpleName(), id);
        }
    }
}
