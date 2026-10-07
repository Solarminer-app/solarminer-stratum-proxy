package de.verdox.solarminer.solarminerstratumproxy.v1;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import de.verdox.solarminer.solarminerstratumproxy.monitoring.ProxyTelemetryService;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.MiningProtocolFactory;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Scope("prototype")
public class MinerSession implements ProxyContext {
    private static final Logger log = LoggerFactory.getLogger(MinerSession.class);

    private final ProxyProperties proxyProperties;
    private MiningProtocol miningProtocol;
    private final FeeManager feeManager;
    private final MiningProtocolFactory factory;
    private final ProxyTelemetryService telemetry;
    @Value("${proxy.fee.required:false}")
    private boolean feeRequired;

    private String dynamicPool;
    private String dynamicWorker;
    private String dynamicPass;

    private Channel minerChannel;
    private String coinName;
    private final ConcurrentHashMap<String, Channel> upstreamChannels = new ConcurrentHashMap<>();
    private final List<String> messageBuffer = new ArrayList<>();
    private final List<String> handshakeMessages = new ArrayList<>();
    private boolean connecting;
    private boolean closed;

    private boolean isConnectedToUpstream = false;
    private String currentTargetId = FeeManager.USER_TARGET_ID;

    private String minerIp;
    private String telemetrySessionId;

    public MinerSession(FeeManager feeManager, MiningProtocolFactory factory, ProxyProperties proxyProperties,
                        ProxyTelemetryService telemetry) {
        this.feeManager = feeManager;
        this.factory = factory;
        this.proxyProperties = proxyProperties;
        this.telemetry = telemetry;
    }

    public void initialize(Channel minerChannel, String coinName) {
        this.miningProtocol = factory.getProtocol(coinName);
        this.minerChannel = minerChannel;
        this.coinName = coinName;

        if (minerChannel.remoteAddress() instanceof java.net.InetSocketAddress inetAddress) {
            this.minerIp = inetAddress.getAddress().getHostAddress();
        } else {
            this.minerIp = "unknown";
        }

        // A TCP connect without a Stratum frame is a reachability probe, not a miner.
    }

    public void handleMessageFromMiner(String rawJson) {
        if (closed) return;
        // The GPU relay sends an internal route frame before the miner sends any
        // Stratum traffic. It must not create a visible miner session by itself.
        rawJson = miningProtocol.interceptMessageFromMiner(rawJson, this);
        if (rawJson == null || closed) return;
        if (telemetrySessionId == null) {
            telemetrySessionId = telemetry.connected(coinName, minerIp);
            if (dynamicPool != null) telemetry.userPool(telemetrySessionId, dynamicPool);
            log.info("New miner connection from: {} [{}]", minerIp, coinName);
        }
        if (feeRequired && !feeTargetsReady()) { recordProtocolError("Required fee route unavailable"); disconnect(); return; }
        if (miningProtocol.isHandshakeMessage(rawJson)) {
            if (handshakeMessages.size() >= 16) { recordProtocolError("Handshake limit exceeded"); disconnect(); return; }
            handshakeMessages.add(rawJson);
        }
        if (!isConnectedToUpstream) {
            if (messageBuffer.size() >= 16 || rawJson.length() > 16384) {
                recordProtocolError("Initial message buffer limit exceeded"); disconnect(); return;
            }
            messageBuffer.add(rawJson);
        }
        log.debug("Miner -> Proxy: {} characters", rawJson.length());
        miningProtocol.handleMessageFromMiner(rawJson, this);
    }

    public void handleMessageFromPool(String rawJson, String targetId) {
        if (closed) return;
        if (feeRequired && !feeTargetsReady()) { recordProtocolError("Required fee route unavailable"); disconnect(); return; }
        telemetry.poolMessage(telemetrySessionId, targetId, rawJson);
        telemetry.upstreamResponse(telemetrySessionId, targetId, rawJson);
        log.debug("Pool [{}] -> Proxy: {} characters", targetId, rawJson.length());
        miningProtocol.handleMessageFromPool(rawJson, targetId, this);
    }

    @Override
    public void sendToMiner(String message) {
        log.debug("Proxy -> Miner: {} characters", message.length());
        if (minerChannel != null && minerChannel.isActive()) minerChannel.writeAndFlush(message + "\n");
    }

    @Override
    public void setDynamicRouting(String pool, String worker, String pass) {
        if (dynamicPool != null && (!java.util.Objects.equals(dynamicPool, pool)
                || !java.util.Objects.equals(dynamicWorker, worker) || !java.util.Objects.equals(dynamicPass, pass))) {
            recordProtocolError("Route cannot change inside an established miner session");
            disconnect();
            return;
        }
        this.dynamicPool = pool;
        this.dynamicWorker = worker;
        this.dynamicPass = pass;
        telemetry.userPool(telemetrySessionId, pool);
    }

    @Override
    public void recordLocalReject(String reason) { telemetry.localReject(telemetrySessionId, reason); }

    @Override public void recordProtocolError(String reason) {
        log.warn("Stratum failure [{}]: {}", coinName, reason);
        telemetry.event("ERROR", coinName, reason);
    }

    @Override
    public void sendToUpstream(String targetId, String message) {
        log.debug("Proxy -> Pool[{}]: {} characters", targetId, message.length());
        Channel targetChannel = upstreamChannels.get(targetId);
        if (targetChannel != null && targetChannel.isActive()) {
            telemetry.upstreamRequest(telemetrySessionId, targetId, message);
            targetChannel.writeAndFlush(message + "\n");
        }
    }

    @Override
    public void reconnectToTarget(String targetId, String newAddress) {
        if (closed) return;
        if (newAddress == null) {
            FeeTarget target = feeManager.getTarget(coinName, targetId);
            newAddress = FeeManager.USER_TARGET_ID.equals(targetId) ? dynamicPool : target == null ? null : target.poolAddress();
        }
        if (newAddress == null) { recordProtocolError("Reconnect route unavailable for " + targetId); disconnect(); return; }
        Channel oldChannel = upstreamChannels.remove(targetId);
        if (oldChannel != null) {
            oldChannel.close();
        }

        connectToUpstream(targetId, newAddress, minerChannel.eventLoop(), () -> {
            log.info("Replaying handshake after reconnect for target {}", targetId);
            flushBufferForTarget(targetId, List.copyOf(handshakeMessages));
        });
    }

    @Override
    public void broadcastToUpstreams(String message) {
        log.debug("Proxy -> Pool: {} characters", message.length());
        for (String targetId : upstreamChannels.keySet()) {
            String translated = miningProtocol.translateMessageForUpstream(message, targetId, this);
            sendToUpstream(targetId, translated == null ? message : translated);
        }
    }

    @Override
    public void connectToTargetPool(String workerName) {
        if (closed || connecting || isConnectedToUpstream) return;
        if (this.dynamicPool == null) {
            log.error("Connection refused: Miner IP {} did not define a target pool in the workername (Format: pool;user;pass)", this.minerIp);
            disconnect();
            return;
        }
        if (feeRequired && !feeTargetsReady()) {
            log.error("Connection rejected for {}: no usable fee target is loaded", coinName);
            disconnect();
            return;
        }

        log.info("Routing {} to {}", workerName, this.dynamicPool);
        connecting = true;

        connectToUpstream(FeeManager.USER_TARGET_ID, this.dynamicPool, minerChannel.eventLoop(), () -> {
            log.info("Main pool connection established for USER. Activating real-time routing.");
            this.isConnectedToUpstream = true;
            List<String> initialMessages = List.copyOf(messageBuffer);
            messageBuffer.clear();
            flushBufferForTarget(FeeManager.USER_TARGET_ID, initialMessages);

            for (FeeTarget target : feeManager.getFeeTargets(coinName)) {
                connectToUpstream(target.targetId(), target.poolAddress(), minerChannel.eventLoop(), () -> {
                    log.info("Dev Fee pool connection established for target: {}", target.targetId());
                    flushBufferForTarget(target.targetId(), handshakeMessages.isEmpty() ? initialMessages : List.copyOf(handshakeMessages));
                });
            }
        });
    }

    private boolean feeTargetsReady() {
        return feeManager.getFeeTargets(coinName).stream().anyMatch(target -> target.percentage() > 0
                && target.poolAddress() != null && !target.poolAddress().isBlank()
                && target.workerName() != null && !target.workerName().isBlank());
    }

    @Override
    public void disconnect() {
        if (closed) return;
        closed = true;
        isConnectedToUpstream = false;
        messageBuffer.clear();
        handshakeMessages.clear();
        if (telemetrySessionId != null) telemetry.disconnected(telemetrySessionId);
        if (minerChannel != null && minerChannel.isActive()) minerChannel.close();
        upstreamChannels.values().forEach(Channel::close);
        upstreamChannels.clear();
    }

    @Override
    public boolean isConnectedToUpstream() {
        return this.isConnectedToUpstream;
    }

    @Override
    public String getCurrentTargetId() {
        return this.currentTargetId;
    }

    @Override
    public void setCurrentTargetId(String targetId) {
        if (!this.currentTargetId.equals(targetId)) {
            log.info("Switch: {} -> {}", this.currentTargetId, targetId);
            this.currentTargetId = targetId;
            telemetry.currentTarget(telemetrySessionId, targetId);
            miningProtocol.onTargetChanged(targetId, this);
        }
    }

    @Override
    public String rollNextJobTarget() {
        String selected = feeManager.rollNextJobTarget(coinName);
        telemetry.assignedJob(telemetrySessionId, selected);
        return selected;
    }

    @Override
    public String getWorkerForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicWorker != null) return dynamicWorker;
        FeeTarget target = feeManager.getTarget(coinName, targetId);
        return target != null ? target.workerName() : null;
    }

    @Override
    public String getPasswordForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicPass != null) return dynamicPass;
        FeeTarget target = feeManager.getTarget(coinName, targetId);
        return target != null ? target.password() : null;
    }

    private void connectToUpstream(String targetId, String address, EventLoop eventLoop, Runnable onSuccess) {
        URI upstream;
        try {
            upstream = URI.create(address.contains("://") ? address : "stratum+tcp://" + address);
        } catch (IllegalArgumentException e) {
            log.error("Invalid upstream URL for target {}", targetId);
            recordProtocolError("Invalid upstream URL for " + targetId);
            if (FeeManager.USER_TARGET_ID.equals(targetId) || feeRequired) disconnect();
            return;
        }
        String scheme = upstream.getScheme();
        String host = upstream.getHost();
        int port = upstream.getPort();
        if ((!"stratum+tcp".equals(scheme) && !"stratum+ssl".equals(scheme)) || host == null || port < 1 || port > 65535) {
            log.error("Unsupported upstream URL for target {}", targetId);
            recordProtocolError("Unsupported upstream URL for " + targetId);
            if (FeeManager.USER_TARGET_ID.equals(targetId) || feeRequired) disconnect();
            return;
        }
        boolean tls = "stratum+ssl".equals(scheme);
        SslContext sslContext;
        try {
            sslContext = tls ? SslContextBuilder.forClient().endpointIdentificationAlgorithm("HTTPS").build() : null;
        } catch (javax.net.ssl.SSLException e) {
            log.error("TLS initialization failed for target {}", targetId, e);
            return;
        }
        Bootstrap bootstrap = new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true);
        bootstrap.handler(createPoolInitializer(targetId, sslContext, host, port));

            ChannelFuture future = bootstrap.connect(host, port);
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    if (closed) { f.channel().close(); return; }
                    if (tls) {
                        SslHandler sslHandler = f.channel().pipeline().get(SslHandler.class);
                        sslHandler.handshakeFuture().addListener(handshake -> {
                            if (handshake.isSuccess()) {
                                if (closed) { f.channel().close(); return; }
                                upstreamChannels.put(targetId, f.channel());
                                telemetry.upstream(telemetrySessionId, targetId, true, safeAddress(host, port));
                                if (onSuccess != null) onSuccess.run();
                            } else {
                                log.error("TLS handshake failed for target {}", targetId);
                                telemetry.upstream(telemetrySessionId, targetId, false, "TLS handshake failed");
                                f.channel().close();
                                if (targetId.equals(FeeManager.USER_TARGET_ID) || feeRequired) disconnect();
                            }
                        });
                    } else {
                        upstreamChannels.put(targetId, f.channel());
                        telemetry.upstream(telemetrySessionId, targetId, true, safeAddress(host, port));
                        if (onSuccess != null) onSuccess.run();
                    }
                    log.info("Successfully connected to upstream pool [{}]: {}", targetId, address);
                } else {
                    log.error("Connection to {} ({}) did not work!", targetId, safeAddress(host, port));
                    telemetry.upstream(telemetrySessionId, targetId, false, "Connection failed · " + safeAddress(host, port));
                    if (targetId.equals(FeeManager.USER_TARGET_ID) || feeRequired) disconnect();
                }
            });
    }

    private static String safeAddress(String host, int port) { return host + ":" + port; }

    private ChannelInitializer<SocketChannel> createPoolInitializer(String targetId, SslContext sslContext, String host, int port) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (sslContext != null) ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                ch.pipeline().addLast(new LineBasedFrameDecoder(4_194_304));
                ch.pipeline().addLast(new StringDecoder());
                ch.pipeline().addLast(new StringEncoder());
                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                        handleMessageFromPool(msg, targetId);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        if (upstreamChannels.get(targetId) != ctx.channel()) return;
                        upstreamChannels.remove(targetId, ctx.channel());
                        telemetry.upstream(telemetrySessionId, targetId, false, "Connection closed");
                        if (!closed) recordProtocolError("Upstream connection closed for " + targetId);
                        if (targetId.equals(FeeManager.USER_TARGET_ID) || feeRequired) disconnect();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        log.warn("Upstream transport failure for {} [{}]: {}", coinName, targetId, cause.getClass().getSimpleName());
                        ctx.close();
                    }
                });
            }
        };
    }

    private void flushBufferForTarget(String targetId, List<String> initialMessages) {
        log.info("Flushing handshake buffer specifically for target: {}", targetId);
        for (String rawJson : initialMessages) {
            if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                sendToUpstream(FeeManager.USER_TARGET_ID, rawJson);
            } else {
                String rewritten = miningProtocol.translateMessageForUpstream(rawJson, targetId, this);
                sendToUpstream(targetId, rewritten != null ? rewritten : rawJson);
            }
        }
    }
}
