package de.verdox.solarminer.solarminerstratumproxy.v1;

public interface MiningProtocol {
    default String interceptMessageFromMiner(String rawMessage, ProxyContext context) {
        return rawMessage;
    }

    void handleMessageFromMiner(String rawMessage, ProxyContext context);

    void handleMessageFromPool(String rawMessage, String targetId, ProxyContext context);

    void onTargetChanged(String newTargetId, ProxyContext context);

    String translateMessageForUpstream(String rawMessage, String targetId, ProxyContext context);
}