package de.verdox.solarminer.solarminerstratumproxy.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.verdox.solarminer.solarminerstratumproxy.monitoring.ProxyTelemetryService;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.MiningProtocolFactory;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.RavencoinStratumProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.EthereumClassicStratumProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.DecredStratumProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.gpu.QuantusStratumProtocol;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GpuMinerSessionHandshakeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void tcpOnlyReachabilityProbeIsNotRecordedAsMiner() {
        var telemetry = new ProxyTelemetryService();
        var factory = mock(MiningProtocolFactory.class);
        when(factory.getProtocol("ethereumclassic")).thenAnswer(unused -> new EthereumClassicStratumProtocol());
        var channel = mock(Channel.class);
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("127.0.0.1", 41000));

        var probe = new MinerSession(mock(FeeManager.class), factory, new ProxyProperties(), telemetry);
        probe.initialize(channel, "ethereumclassic");
        probe.disconnect();
        assertTrue(telemetry.console("ethereumclassic", "all", 10).isEmpty());
        assertEquals(0, telemetry.metrics("ethereumclassic", List.of(), "online").connectedWorkers());

        String pool = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("stratum+tcp://pool.example:5555".getBytes(StandardCharsets.UTF_8));
        for (String coin : List.of("ravencoin", "ethereumclassic", "decred", "quantus")) {
            when(factory.getProtocol(coin)).thenAnswer(unused -> switch (coin) {
                case "ravencoin" -> new RavencoinStratumProtocol();
                case "ethereumclassic" -> new EthereumClassicStratumProtocol();
                case "decred" -> new DecredStratumProtocol();
                default -> new QuantusStratumProtocol();
            });
            String wallet = switch (coin) {
                case "ravencoin" -> "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG";
                case "ethereumclassic" -> "0x" + "a".repeat(40);
                case "decred" -> "D" + "A".repeat(30);
                default -> "qz" + "a".repeat(38);
            };
            var routeOnly = new MinerSession(mock(FeeManager.class), factory, new ProxyProperties(), telemetry);
            routeOnly.initialize(channel, coin);
            routeOnly.handleMessageFromMiner("{\"method\":\"solarminer.route\",\"params\":[\""
                    + wallet + ".sm1." + pool + ".rig\",\"x\"]}");
            routeOnly.disconnect();
            assertTrue(telemetry.console(coin, "all", 10).isEmpty(),
                    "An internal relay route without miner traffic is not a connected miner: " + coin);
        }

        var miner = new MinerSession(mock(FeeManager.class), factory, new ProxyProperties(), telemetry);
        miner.initialize(channel, "ethereumclassic");
        miner.handleMessageFromMiner("{\"id\":17,\"method\":\"mining.subscribe\",\"params\":[]}");
        assertTrue(telemetry.console("ethereumclassic", "all", 10).stream()
                .anyMatch(entry -> "Miner connected".equals(entry.message())));
    }

    @Test @Timeout(15)
    void subscribeFirstReceivesRealNonceAndLaterAuthorizeReachesBothPools() throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var group = new NioEventLoopGroup(2);
        Channel server = null;
        try (var user = new ServerSocket(0, 4, loopback); var fee = new ServerSocket(0, 4, loopback);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            user.setSoTimeout(5000); fee.setSoTimeout(5000);
            Future<String> userWorker = executor.submit(() -> handshake(user));
            Future<String> feeWorker = executor.submit(() -> handshake(fee));
            var fees = mock(FeeManager.class);
            var house = new FeeTarget("HOUSE", "stratum+tcp://127.0.0.1:" + fee.getLocalPort(), "house.rig", "x", 2.5, true);
            when(fees.getFeeTargets("ravencoin")).thenReturn(List.of(house));
            when(fees.getTarget("ravencoin", "HOUSE")).thenReturn(house);
            when(fees.rollNextJobTarget("ravencoin")).thenReturn("USER");
            var factory = mock(MiningProtocolFactory.class);
            when(factory.getProtocol("ravencoin")).thenAnswer(unused -> new RavencoinStratumProtocol());
            server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<Channel>() {
                        @Override protected void initChannel(Channel channel) {
                            channel.pipeline().addLast(new LineBasedFrameDecoder(16384), new StringDecoder(), new StringEncoder());
                            channel.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                                private MinerSession session;
                                @Override public void channelActive(ChannelHandlerContext ctx) {
                                    session = new MinerSession(fees, factory, new ProxyProperties(), new ProxyTelemetryService());
                                    session.initialize(ctx.channel(), "ravencoin");
                                }
                                @Override protected void channelRead0(ChannelHandlerContext ctx, String line) { session.handleMessageFromMiner(line); }
                                @Override public void channelInactive(ChannelHandlerContext ctx) { session.disconnect(); }
                            });
                        }
                    }).bind(loopback, 0).sync().channel();
            try (var miner = new Socket(loopback, ((InetSocketAddress) server.localAddress()).getPort())) {
                miner.setSoTimeout(5000);
                var incoming = new BufferedReader(new InputStreamReader(miner.getInputStream(), StandardCharsets.UTF_8));
                String pool = "stratum+tcp://127.0.0.1:" + user.getLocalPort();
                String login = "RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG.sm1."
                        + Base64.getUrlEncoder().withoutPadding().encodeToString(pool.getBytes(StandardCharsets.UTF_8)) + ".rig";
                send(miner, "{\"method\":\"solarminer.route\",\"params\":[\"" + login + "\",\"x\"]}");
                send(miner, "{\"id\":17,\"method\":\"mining.subscribe\",\"params\":[]}");
                var subscription = mapper.readTree(incoming.readLine());
                assertEquals(17, subscription.path("id").asInt());
                assertEquals("abcdef", subscription.path("result").path(1).asText());
                send(miner, "{\"id\":42,\"method\":\"mining.authorize\",\"params\":[\"" + login + "\",\"x\"]}");
                assertEquals(42, mapper.readTree(incoming.readLine()).path("id").asInt());
                assertEquals("RHUC17zAVjNqXDtkqwLPRvQ2XgoRZsXeeG.rig", userWorker.get(5, TimeUnit.SECONDS));
                assertEquals("house.rig", feeWorker.get(5, TimeUnit.SECONDS));
            }
        } finally {
            if (server != null) server.close().syncUninterruptibly();
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private String handshake(ServerSocket pool) throws Exception {
        try (Socket socket = pool.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var subscribe = mapper.readTree(input.readLine());
            assertEquals("mining.subscribe", subscribe.path("method").asText(), "Internal route must not leak to the pool");
            send(socket, "{\"id\":" + subscribe.path("id") + ",\"result\":[null,\"abcdef\"],\"error\":null}");
            var authorize = mapper.readTree(input.readLine());
            assertEquals("mining.authorize", authorize.path("method").asText());
            send(socket, "{\"id\":" + authorize.path("id") + ",\"result\":true,\"error\":null}");
            return authorize.path("params").path(0).asText();
        }
    }

    private static void send(Socket socket, String message) throws IOException {
        socket.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }
}
