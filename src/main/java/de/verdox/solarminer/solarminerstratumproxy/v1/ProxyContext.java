package de.verdox.solarminer.solarminerstratumproxy.v1;

public interface ProxyContext {
    void sendToMiner(String message);

    void broadcastToUpstreams(String message);

    void sendToUpstream(String targetId, String message);

    void connectToTargetPool(String workerName);

    void disconnect();

    boolean isConnectedToUpstream();

    String getCurrentTargetId();

    void setCurrentTargetId(String targetId);

    String rollNextJobTarget();

    String getWorkerForTarget(String targetId);

    String getPasswordForTarget(String targetId);

    void reconnectToTarget(String targetId, String s);

    void setDynamicRouting(String targetPool, String workerName, String password);
}