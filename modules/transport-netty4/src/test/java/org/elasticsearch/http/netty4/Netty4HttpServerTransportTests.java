/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.http.netty4;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PoolArenaMetric;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocatorMetric;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpHost;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.ElasticsearchWrapperException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionTestUtils;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.SubscribableListener;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.Iterators;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.BoundTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.http.AbstractHttpServerTransportTestCase;
import org.elasticsearch.http.AggregatingDispatcher;
import org.elasticsearch.http.BindHttpException;
import org.elasticsearch.http.CorsHandler;
import org.elasticsearch.http.HttpHeadersValidationException;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.http.HttpTransportSettings;
import org.elasticsearch.http.netty4.internal.HttpHeadersAuthenticatorUtils;
import org.elasticsearch.http.netty4.internal.HttpValidator;
import org.elasticsearch.rest.ChunkedRestResponseBodyPart;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.telemetry.TelemetryProvider;
import org.elasticsearch.test.rest.FakeRestRequest;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transports;
import org.elasticsearch.transport.netty4.AcceptChannelHandler;
import org.elasticsearch.transport.netty4.Netty4Plugin;
import org.elasticsearch.transport.netty4.NettyAllocator;
import org.elasticsearch.transport.netty4.SharedGroupFactory;
import org.elasticsearch.transport.netty4.TLSConfig;
import org.elasticsearch.xcontent.ToXContent;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static com.carrotsearch.randomizedtesting.RandomizedTest.getRandom;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ALLOW_ORIGIN;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_CORS_ENABLED;
import static org.elasticsearch.http.HttpTransportSettings.SETTING_HTTP_SERVER_SHUTDOWN_GRACE_PERIOD;
import static org.elasticsearch.rest.RestStatus.BAD_REQUEST;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.RestStatus.UNAUTHORIZED;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyIterable;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for the {@link Netty4HttpServerTransport} class.
 */
public class Netty4HttpServerTransportTests extends AbstractHttpServerTransportTestCase {

    private NetworkService networkService;
    private ThreadPool threadPool;
    private ClusterSettings clusterSettings;

    @Before
    public void setup() throws Exception {
        networkService = new NetworkService(Collections.emptyList());
        threadPool = new TestThreadPool("test");
        clusterSettings = randomClusterSettings();
    }

    @After
    public void shutdown() throws Exception {
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
        threadPool = null;
        networkService = null;
        clusterSettings = null;
    }

    /**
     * Test that {@link Netty4HttpServerTransport} supports the "Expect: 100-continue" HTTP header
     * @throws InterruptedException if the client communication with the server is interrupted
     */
    public void testExpectContinueHeader() throws InterruptedException {
        final Settings settings = createSettings();
        final int contentLength = randomIntBetween(1, HttpTransportSettings.SETTING_HTTP_MAX_CONTENT_LENGTH.get(settings).bytesAsInt());
        runExpectHeaderTest(settings, HttpHeaderValues.CONTINUE.toString(), contentLength, HttpResponseStatus.CONTINUE);
    }

    /**
     * Test that {@link Netty4HttpServerTransport} responds to a
     * 100-continue expectation with too large a content-length
     * with a 413 status.
     * @throws InterruptedException if the client communication with the server is interrupted
     */
    public void testExpectContinueHeaderContentLengthTooLong() throws InterruptedException {
        final String key = HttpTransportSettings.SETTING_HTTP_MAX_CONTENT_LENGTH.getKey();
        final int maxContentLength = randomIntBetween(1, 104857600);
        final Settings settings = createBuilderWithPort().put(key, maxContentLength + "b").build();
        final int contentLength = randomIntBetween(maxContentLength + 1, Integer.MAX_VALUE);
        runExpectHeaderTest(settings, HttpHeaderValues.CONTINUE.toString(), contentLength, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE);
    }

    /**
     * Test that {@link Netty4HttpServerTransport} responds to an unsupported expectation with a 417 status.
     * @throws InterruptedException if the client communication with the server is interrupted
     */
    public void testExpectUnsupportedExpectation() throws InterruptedException {
        Settings settings = createSettings();
        runExpectHeaderTest(settings, "chocolate=yummy", 0, HttpResponseStatus.EXPECTATION_FAILED);
    }

    private void runExpectHeaderTest(
        final Settings settings,
        final String expectation,
        final int contentLength,
        final HttpResponseStatus expectedStatus
    ) throws InterruptedException {
        final HttpServerTransport.Dispatcher dispatcher = new AggregatingDispatcher() {
            @Override
            public void dispatchAggregatedRequest(RestRequest request, RestChannel channel, ThreadContext threadContext) {
                channel.sendResponse(new RestResponse(OK, RestResponse.TEXT_CONTENT_TYPE, new BytesArray("done")));
            }

            @Override
            public void dispatchBadRequest(RestChannel channel, ThreadContext threadContext, Throwable cause) {
                logger.error(() -> "--> Unexpected bad request [" + FakeRestRequest.requestToString(channel.request()) + "]", cause);
                throw new AssertionError();
            }
        };
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                final FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
                request.headers().set(HttpHeaderNames.EXPECT, expectation);
                HttpUtil.setContentLength(request, contentLength);

                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(response.status(), equalTo(expectedStatus));
                    if (expectedStatus.equals(HttpResponseStatus.CONTINUE)) {
                        final FullHttpRequest continuationRequest = new DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1,
                            HttpMethod.POST,
                            "/",
                            Unpooled.EMPTY_BUFFER
                        );
                        final FullHttpResponse continuationResponse = client.send(remoteAddress.address(), continuationRequest);
                        try {
                            assertThat(continuationResponse.status(), is(HttpResponseStatus.OK));
                            assertThat(
                                new String(ByteBufUtil.getBytes(continuationResponse.content()), StandardCharsets.UTF_8),
                                is("done")
                            );
                        } finally {
                            continuationResponse.release();
                        }
                    }
                } finally {
                    response.release();
                }
            }
        }
    }

    public void testBindUnavailableAddress() {
        Settings initialSettings = createSettings();
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                initialSettings,
                networkService,
                threadPool,
                xContentRegistry(),
                new AggregatingDispatcher(),
                clusterSettings,
                new SharedGroupFactory(Settings.EMPTY),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            Settings settings = Settings.builder()
                .put("http.port", remoteAddress.getPort())
                .put("network.host", remoteAddress.getAddress())
                .build();
            try (
                Netty4HttpServerTransport otherTransport = new Netty4HttpServerTransport(
                    settings,
                    networkService,
                    threadPool,
                    xContentRegistry(),
                    new AggregatingDispatcher(),
                    clusterSettings,
                    new SharedGroupFactory(settings),
                    TelemetryProvider.NOOP,
                    TLSConfig.noTLS(),
                    null,
                    randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
                )
            ) {
                BindHttpException bindHttpException = expectThrows(BindHttpException.class, otherTransport::start);
                assertEquals("Failed to bind to " + NetworkAddress.format(remoteAddress.address()), bindHttpException.getMessage());
            }
        }
    }

    public void testBadRequest() throws InterruptedException {
        final AtomicReference<Throwable> causeReference = new AtomicReference<>();
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                logger.error("--> Unexpected successful request [{}]", FakeRestRequest.requestToString(request));
                throw new AssertionError();
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                causeReference.set(cause);
                try {
                    final ElasticsearchException e = new ElasticsearchException("you sent a bad request and you should feel bad");
                    channel.sendResponse(new RestResponse(channel, BAD_REQUEST, e));
                } catch (final IOException e) {
                    throw new AssertionError(e);
                }
            }

        };

        final Settings settings;
        final int maxInitialLineLength;
        final Setting<ByteSizeValue> httpMaxInitialLineLengthSetting = HttpTransportSettings.SETTING_HTTP_MAX_INITIAL_LINE_LENGTH;
        if (randomBoolean()) {
            maxInitialLineLength = httpMaxInitialLineLengthSetting.getDefault(Settings.EMPTY).bytesAsInt();
            settings = createSettings();
        } else {
            maxInitialLineLength = randomIntBetween(1, 8192);
            settings = createBuilderWithPort().put(httpMaxInitialLineLengthSetting.getKey(), maxInitialLineLength + "b").build();
        }

        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());

            try (Netty4HttpClient client = new Netty4HttpClient()) {
                final String url = "/" + new String(new byte[maxInitialLineLength], StandardCharsets.UTF_8);
                final FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url);

                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(response.status(), equalTo(HttpResponseStatus.BAD_REQUEST));
                    assertThat(
                        new String(response.content().array(), StandardCharsets.UTF_8),
                        containsString("you sent a bad request and you should feel bad")
                    );
                } finally {
                    response.release();
                }
            }
        }

        assertNotNull(causeReference.get());
        assertThat(causeReference.get(), instanceOf(TooLongFrameException.class));
    }

    public void testLargeCompressedResponse() throws InterruptedException {
        testLargeResponse(true);
    }

    public void testLargeUncompressedResponse() throws InterruptedException {
        testLargeResponse(false);
    }

    private void testLargeResponse(boolean compressed) throws InterruptedException {
        final String responseString = randomAlphaOfLength(4 * 1024 * 1024);
        final String url = "/thing";
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                if (url.equals(request.uri())) {
                    channel.sendResponse(new RestResponse(OK, responseString));
                } else {
                    logger.error("--> Unexpected successful uri [{}]", request.uri());
                    throw new AssertionError();
                }
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                logger.error(() -> "--> Unexpected bad request [" + FakeRestRequest.requestToString(channel.request()) + "]", cause);
                throw new AssertionError();
            }

        };

        final AtomicBoolean seenThrottledWrite = new AtomicBoolean(false);
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                Settings.EMPTY,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(Settings.EMPTY),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            ) {
                @Override
                public ChannelHandler configureServerChannelHandler() {
                    return new HttpChannelHandler(
                        this,
                        handlingSettings,
                        TLSConfig.noTLS(),
                        null,
                        randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
                    ) {
                        @Override
                        protected void initChannel(Channel ch) throws Exception {
                            super.initChannel(ch);
                            ch.pipeline().addBefore("pipelining", "assert-throttling", new ChannelOutboundHandlerAdapter() {

                                private boolean seenNotWritable = false;

                                @Override
                                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                                    if (seenNotWritable) {
                                        // track that we saw a write after the channel became unwriteable on a previous write, so we can
                                        // later assert that we indeed saw throttled writes in this test
                                        seenThrottledWrite.set(true);
                                    }
                                    assertTrue("handler should throttle to only write into writable channels", ctx.channel().isWritable());
                                    super.write(ctx, msg, promise);
                                    if (ctx.channel().isWritable() == false) {
                                        seenNotWritable = true;
                                    }
                                }
                            });
                        }
                    };
                }
            }
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());

            try (Netty4HttpClient client = new Netty4HttpClient()) {
                DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url);
                if (compressed) {
                    request.headers().add(HttpHeaderNames.ACCEPT_ENCODING, randomFrom("deflate", "gzip"));
                }
                long numOfHugeAllocations = getHugeAllocationCount();
                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(getHugeAllocationCount(), equalTo(numOfHugeAllocations));
                    assertThat(response.status(), equalTo(HttpResponseStatus.OK));
                    byte[] bytes = new byte[response.content().readableBytes()];
                    response.content().readBytes(bytes);
                    assertThat(new String(bytes, StandardCharsets.UTF_8), equalTo(responseString));
                    assertTrue(seenThrottledWrite.get());
                } finally {
                    response.release();
                }
            }
        }
    }

    private long getHugeAllocationCount() {
        long numOfHugAllocations = 0;
        ByteBufAllocator allocator = NettyAllocator.getAllocator();
        assert allocator instanceof NettyAllocator.NoDirectBuffers;
        ByteBufAllocator delegate = ((NettyAllocator.NoDirectBuffers) allocator).getDelegate();
        if (delegate instanceof PooledByteBufAllocator) {
            PooledByteBufAllocatorMetric metric = ((PooledByteBufAllocator) delegate).metric();
            numOfHugAllocations = metric.heapArenas().stream().mapToLong(PoolArenaMetric::numHugeAllocations).sum();
        }
        return numOfHugAllocations;
    }

    public void testCorsRequest() throws InterruptedException {
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                logger.error("--> Unexpected successful request [{}]", FakeRestRequest.requestToString(request));
                throw new AssertionError();
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                logger.error(() -> "--> Unexpected bad request [" + FakeRestRequest.requestToString(channel.request()) + "]", cause);
                throw new AssertionError();
            }

        };

        final Settings settings = createBuilderWithPort().put(SETTING_CORS_ENABLED.getKey(), true)
            .put(SETTING_CORS_ALLOW_ORIGIN.getKey(), "elastic.co")
            .build();

        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                randomClusterSettings(),
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());

            // Test pre-flight request
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                final FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, "/");
                request.headers().add(CorsHandler.ORIGIN, "elastic.co");
                request.headers().add(CorsHandler.ACCESS_CONTROL_REQUEST_METHOD, "POST");

                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(response.status(), equalTo(HttpResponseStatus.OK));
                    assertThat(response.headers().get(CorsHandler.ACCESS_CONTROL_ALLOW_ORIGIN), equalTo("elastic.co"));
                    assertThat(response.headers().get(CorsHandler.VARY), equalTo(CorsHandler.ORIGIN));
                    assertTrue(response.headers().contains(CorsHandler.DATE));
                } finally {
                    response.release();
                }
            }

            // Test short-circuited request
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                final FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                request.headers().add(CorsHandler.ORIGIN, "elastic2.co");

                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(response.status(), equalTo(HttpResponseStatus.FORBIDDEN));
                } finally {
                    response.release();
                }
            }
        }
    }

    public void testChannelAcceptorCannotTamperThreadContext() throws Exception {
        HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {
            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                assertThreadContextNotTampered(threadContext);
                channel.sendResponse(new RestResponse(OK, RestResponse.TEXT_CONTENT_TYPE, new BytesArray("done")));
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                logger.error(() -> "--> Unexpected bad request [" + FakeRestRequest.requestToString(channel.request()) + "]", cause);
                throw new AssertionError();
            }
        };
        // there's only one netty worker thread that's reused across client requests
        Settings settings = createBuilderWithPort().put(Netty4Plugin.WORKER_COUNT.getKey(), 1)
            .put(Netty4Plugin.SETTING_HTTP_WORKER_COUNT.getKey(), 0)
            .build();
        AtomicBoolean acceptChannel = new AtomicBoolean();
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                randomClusterSettings(),
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                new AcceptChannelHandler.AcceptPredicate() {
                    @Override
                    public void setBoundAddress(BoundTransportAddress boundHttpTransportAddress) {}

                    @Override
                    public boolean test(String profile, InetSocketAddress peerAddress) {
                        assertThreadContextNotTampered(threadPool.getThreadContext());
                        tamperThreadContext(threadPool.getThreadContext());
                        return acceptChannel.get();
                    }
                },
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            int nRetries = randomIntBetween(7, 9);
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                for (int i = 0; i < nRetries; i++) {
                    acceptChannel.set(randomBoolean());
                    var responses = client.get(randomFrom(transport.boundAddress().boundAddresses()).address(), "/test/url");
                    try {
                        if (acceptChannel.get()) {
                            assertThat(responses, iterableWithSize(1));
                            assertThat(responses.iterator().next().status(), equalTo(HttpResponseStatus.OK));
                        } else {
                            assertThat(responses, emptyIterable());
                        }
                    } finally {
                        for (FullHttpResponse response : responses) {
                            response.release();
                        }
                    }
                }
            }
        }
    }

    public void testReadTimeout() throws Exception {
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                logger.error("--> Unexpected successful request [{}]", FakeRestRequest.requestToString(request));
                throw new AssertionError("Should not have received a dispatched request");
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                logger.error(() -> "--> Unexpected bad request [" + FakeRestRequest.requestToString(channel.request()) + "]", cause);
                throw new AssertionError("Should not have received a dispatched request");
            }

        };

        Settings settings = createBuilderWithPort().put(
            HttpTransportSettings.SETTING_HTTP_READ_TIMEOUT.getKey(),
            new TimeValue(randomIntBetween(100, 300))
        ).build();

        NioEventLoopGroup group = new NioEventLoopGroup();
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                randomClusterSettings(),
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());

            CountDownLatch channelClosedLatch = new CountDownLatch(1);

            Bootstrap clientBootstrap = new Bootstrap().option(ChannelOption.ALLOCATOR, NettyAllocator.getAllocator())
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {

                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ChannelHandlerAdapter() {
                        });

                    }
                })
                .group(group);
            ChannelFuture connect = clientBootstrap.connect(remoteAddress.address());
            connect.channel().closeFuture().addListener(future -> channelClosedLatch.countDown());

            assertTrue("Channel should be closed due to read timeout", channelClosedLatch.await(1, TimeUnit.MINUTES));

        } finally {
            group.shutdownGracefully().await();
        }
    }

    public void testHeadRequestToChunkedApi() throws InterruptedException {
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {

            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                try {
                    channel.sendResponse(
                        RestResponse.chunked(OK, ChunkedRestResponseBodyPart.fromXContent(ignored -> Iterators.single((builder, params) -> {
                            throw new AssertionError("should not be called for HEAD REQUEST");
                        }), ToXContent.EMPTY_PARAMS, channel), null)
                    );
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                throw new AssertionError();
            }

        };

        final Settings settings = createSettings();
        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                settings,
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(settings),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());

            try (Netty4HttpClient client = new Netty4HttpClient()) {
                final String url = "/some-head-endpoint";
                final FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.HEAD, url);

                final FullHttpResponse response = client.send(remoteAddress.address(), request);
                try {
                    assertThat(response.status(), equalTo(HttpResponseStatus.OK));
                    assertFalse(response.content().isReadable());
                } finally {
                    response.release();
                }
            }
        }
    }

    public void testHttpHeadersSuccessfulValidation() throws InterruptedException {
        final AtomicReference<HttpMethod> httpMethodReference = new AtomicReference<>();
        final AtomicReference<String> urlReference = new AtomicReference<>();
        final AtomicReference<String> requestHeaderReference = new AtomicReference<>();
        final AtomicReference<String> requestHeaderValueReference = new AtomicReference<>();
        final AtomicReference<String> contextHeaderReference = new AtomicReference<>();
        final AtomicReference<String> contextHeaderValueReference = new AtomicReference<>();
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {
            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                assertThat(request.getHttpRequest().uri(), is(urlReference.get()));
                assertThat(request.getHttpRequest().header(requestHeaderReference.get()), is(requestHeaderValueReference.get()));
                assertThat(request.getHttpRequest().method(), is(translateRequestMethod(httpMethodReference.get())));
                // validation context is restored
                assertThat(threadPool.getThreadContext().getHeader(contextHeaderReference.get()), is(contextHeaderValueReference.get()));
                assertThat(threadPool.getThreadContext().getTransient(contextHeaderReference.get()), is(contextHeaderValueReference.get()));
                // return some response
                channel.sendResponse(new RestResponse(OK, RestResponse.TEXT_CONTENT_TYPE, new BytesArray("done")));
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                throw new AssertionError("A validated request should not dispatch as bad");
            }
        };
        final HttpValidator httpValidator = (httpRequest, channel, validationListener) -> {
            // assert that the validator sees the request unaltered
            assertThat(httpRequest.uri(), is(urlReference.get()));
            assertThat(httpRequest.headers().get(requestHeaderReference.get()), is(requestHeaderValueReference.get()));
            assertThat(httpRequest.method(), is(httpMethodReference.get()));
            // make validation alter the thread context
            contextHeaderReference.set(randomAlphaOfLengthBetween(4, 8));
            contextHeaderValueReference.set(randomAlphaOfLengthBetween(4, 8));
            threadPool.getThreadContext().putHeader(contextHeaderReference.get(), contextHeaderValueReference.get());
            threadPool.getThreadContext().putTransient(contextHeaderReference.get(), contextHeaderValueReference.get());
            // validate successfully
            validationListener.onResponse(null);
        };
        try (
            Netty4HttpServerTransport transport = getTestNetty4HttpServerTransport(
                dispatcher,
                httpValidator,
                (restRequest, threadContext) -> {
                    // assert the thread context does not yet contain anything that validation set in
                    assertThat(threadPool.getThreadContext().getHeader(contextHeaderReference.get()), nullValue());
                    assertThat(threadPool.getThreadContext().getTransient(contextHeaderReference.get()), nullValue());
                    ThreadContext.StoredContext storedAuthenticatedContext = HttpHeadersAuthenticatorUtils.extractAuthenticationContext(
                        restRequest.getHttpRequest()
                    );
                    assertThat(storedAuthenticatedContext, notNullValue());
                    // restore validation context
                    storedAuthenticatedContext.restore();
                    // assert that now, after restoring the validation context, it does contain what validation put in
                    assertThat(
                        threadPool.getThreadContext().getHeader(contextHeaderReference.get()),
                        is(contextHeaderValueReference.get())
                    );
                    assertThat(
                        threadPool.getThreadContext().getTransient(contextHeaderReference.get()),
                        is(contextHeaderValueReference.get())
                    );
                }
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            for (HttpMethod httpMethod : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
                httpMethodReference.set(httpMethod);
                urlReference.set(
                    "/"
                        + randomAlphaOfLengthBetween(4, 8)
                        + "?X-"
                        + randomAlphaOfLengthBetween(4, 8)
                        + "="
                        + randomAlphaOfLengthBetween(4, 8)
                );
                requestHeaderReference.set("X-" + randomAlphaOfLengthBetween(4, 8));
                requestHeaderValueReference.set(randomAlphaOfLengthBetween(4, 8));
                try (Netty4HttpClient client = new Netty4HttpClient()) {
                    FullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        httpMethodReference.get(),
                        urlReference.get()
                    );
                    request.headers().set(requestHeaderReference.get(), requestHeaderValueReference.get());
                    FullHttpResponse response = client.send(remoteAddress.address(), request);
                    assertThat(response.status(), is(HttpResponseStatus.OK));
                }
            }
        }
    }

    public void testLargeRequestIsNeverDispatched() throws Exception {
        final String uri = "/"
            + randomAlphaOfLengthBetween(4, 8)
            + "?X-"
            + randomAlphaOfLengthBetween(4, 8)
            + "="
            + randomAlphaOfLengthBetween(4, 8);
        final Settings settings = createBuilderWithPort().put(HttpTransportSettings.SETTING_HTTP_MAX_CONTENT_LENGTH.getKey(), "1mb")
            .build();
        final String requestString = randomAlphaOfLength(2 * 1024 * 1024); // request size is twice the limit
        final HttpServerTransport.Dispatcher dispatcher = new AggregatingDispatcher() {
            @Override
            public void dispatchAggregatedRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                throw new AssertionError("Request dispatched but shouldn't");
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                throw new AssertionError("Request dispatched but shouldn't");
            }
        };
        try (
            Netty4HttpServerTransport transport = getTestNetty4HttpServerTransport(
                settings,
                dispatcher,
                (r, c, l) -> l.onResponse(null),
                (restRequest, threadContext) -> {
                    throw new AssertionError("Request dispatched but shouldn't");
                }
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                Collection<FullHttpResponse> response = client.post(remoteAddress.address(), List.of(Tuple.tuple(uri, requestString)));
                assertThat(response, hasSize(1));
                assertThat(response.stream().findFirst().get().status(), is(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE));
            }
        }
    }

    public void testHttpHeadersFailedValidation() throws InterruptedException {
        final AtomicReference<HttpMethod> httpMethodReference = new AtomicReference<>();
        final AtomicReference<String> urlReference = new AtomicReference<>();
        final AtomicReference<String> headerReference = new AtomicReference<>();
        final AtomicReference<String> headerValueReference = new AtomicReference<>();
        final AtomicReference<Exception> validationResultExceptionReference = new AtomicReference<>();
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {
            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                throw new AssertionError("Request that failed validation should not be dispatched");
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                assertThat(cause, instanceOf(HttpHeadersValidationException.class));
                assertThat(((ElasticsearchWrapperException) cause).getCause(), is(validationResultExceptionReference.get()));
                assertThat(channel.request().getHttpRequest().uri(), is(urlReference.get()));
                assertThat(channel.request().getHttpRequest().header(headerReference.get()), is(headerValueReference.get()));
                assertThat(channel.request().getHttpRequest().method(), is(translateRequestMethod(httpMethodReference.get())));
                // assert content is dropped
                assertThat(channel.request().getHttpRequest().body().asFull().bytes().utf8ToString(), is(""));
                try {
                    channel.sendResponse(new RestResponse(channel, (Exception) ((ElasticsearchWrapperException) cause).getCause()));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
        final HttpValidator failureHeadersValidator = (httpRequest, channel, validationResultListener) -> {
            // assert that the validator sees the request unaltered
            assertThat(httpRequest.uri(), is(urlReference.get()));
            assertThat(httpRequest.headers().get(headerReference.get()), is(headerValueReference.get()));
            assertThat(httpRequest.method(), is(httpMethodReference.get()));
            // failed validation
            validationResultListener.onFailure(validationResultExceptionReference.get());
        };
        try (
            Netty4HttpServerTransport transport = getTestNetty4HttpServerTransport(
                dispatcher,
                failureHeadersValidator,
                (restRequest, threadContext) -> {
                    throw new AssertionError("Request that failed validation should not be dispatched");
                }
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            for (HttpMethod httpMethod : List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
                httpMethodReference.set(httpMethod);
                urlReference.set(
                    "/"
                        + randomAlphaOfLengthBetween(4, 8)
                        + "?X-"
                        + randomAlphaOfLengthBetween(4, 8)
                        + "="
                        + randomAlphaOfLengthBetween(4, 8)
                );
                validationResultExceptionReference.set(new ElasticsearchSecurityException("Boom", UNAUTHORIZED));
                try (Netty4HttpClient client = new Netty4HttpClient()) {
                    ByteBuf content = Unpooled.copiedBuffer(randomAlphaOfLengthBetween(1, 32), StandardCharsets.UTF_8);
                    FullHttpRequest request = new DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        httpMethodReference.get(),
                        urlReference.get(),
                        content
                    );
                    // submit the request with some header custom header
                    headerReference.set("X-" + randomAlphaOfLengthBetween(4, 8));
                    headerValueReference.set(randomAlphaOfLengthBetween(4, 8));
                    request.headers().set(headerReference.get(), headerValueReference.get());
                    FullHttpResponse response = client.send(remoteAddress.address(), request);
                    assertThat(response.status(), is(HttpResponseStatus.UNAUTHORIZED));
                }
            }
        }
    }

    public void testMultipleValidationsOnTheSameChannel() throws InterruptedException {
        // ensure that there is a single channel active
        final Settings settings = createBuilderWithPort().put(Netty4Plugin.SETTING_HTTP_WORKER_COUNT.getKey(), 1).build();
        final Set<String> okURIs = ConcurrentHashMap.newKeySet();
        final Set<String> nokURIs = ConcurrentHashMap.newKeySet();
        final SetOnce<Channel> channelSetOnce = new SetOnce<>();
        final HttpServerTransport.Dispatcher dispatcher = new HttpServerTransport.Dispatcher() {
            @Override
            public void dispatchRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                assertThat(request.uri(), in(okURIs));
                // assert validated request is dispatched
                okURIs.remove(request.uri());
                channel.sendResponse(new RestResponse(OK, RestResponse.TEXT_CONTENT_TYPE, new BytesArray("dispatch OK")));
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                // assert unvalidated request is NOT dispatched
                assertThat(channel.request().uri(), in(nokURIs));
                nokURIs.remove(channel.request().uri());
                try {
                    channel.sendResponse(new RestResponse(channel, (Exception) ((ElasticsearchWrapperException) cause).getCause()));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
        final HttpValidator headersValidator = (httpPreRequest, channel, validationListener) -> {
            // assert all validations run on the same channel
            channelSetOnce.trySet(channel);
            assertThat(channelSetOnce.get(), is(channel));
            // some requests are validated while others are not
            if (httpPreRequest.uri().contains("X-Auth=OK")) {
                randomFrom(EsExecutors.DIRECT_EXECUTOR_SERVICE, channel.eventLoop()).execute(() -> validationListener.onResponse(null));
            } else if (httpPreRequest.uri().contains("X-Auth=NOK")) {
                randomFrom(EsExecutors.DIRECT_EXECUTOR_SERVICE, channel.eventLoop()).execute(
                    () -> validationListener.onFailure(new ElasticsearchSecurityException("Boom", UNAUTHORIZED))
                );
            } else {
                throw new AssertionError("Unrecognized URI");
            }
        };
        try (
            Netty4HttpServerTransport transport = getTestNetty4HttpServerTransport(
                settings,
                dispatcher,
                headersValidator,
                (restRequest, threadContext) -> {}
            )
        ) {
            transport.start();
            final TransportAddress remoteAddress = randomFrom(transport.boundAddress().boundAddresses());
            final int totalRequestCount = randomIntBetween(64, 128);
            for (int requestId = 0; requestId < totalRequestCount; requestId++) {
                String uri = "/" + randomAlphaOfLengthBetween(4, 8) + "?Request-Id=" + requestId;
                if (randomBoolean()) {
                    uri = uri + "&X-Auth=OK";
                    okURIs.add(uri);
                } else {
                    uri = uri + "&X-Auth=NOK";
                    nokURIs.add(uri);
                }
            }
            List<String> allURIs = new ArrayList<>();
            allURIs.addAll(okURIs);
            allURIs.addAll(nokURIs);
            Collections.shuffle(allURIs, getRandom());
            assertThat(allURIs.size(), is(totalRequestCount));
            try (Netty4HttpClient client = new Netty4HttpClient()) {
                client.get(remoteAddress.address(), allURIs.toArray(new String[0]));
                // assert all validations have been dispatched (or not) correctly
                assertThat(okURIs.size(), is(0));
                assertThat(nokURIs.size(), is(0));
            }
        }
    }

    public void testRespondAfterServiceCloseWithClientCancel() throws Exception {
        runRespondAfterServiceCloseTest(true);
    }

    public void testRespondAfterServiceCloseWithServerCancel() throws Exception {
        runRespondAfterServiceCloseTest(false);
    }

    private void runRespondAfterServiceCloseTest(boolean clientCancel) throws Exception {
        final String url = "/" + randomIdentifier();
        final CountDownLatch responseReleasedLatch = new CountDownLatch(1);
        final SubscribableListener<Void> transportClosedFuture = new SubscribableListener<>();
        final CountDownLatch handlingRequestLatch = new CountDownLatch(1);

        final HttpServerTransport.Dispatcher dispatcher = new AggregatingDispatcher() {
            @Override
            public void dispatchAggregatedRequest(final RestRequest request, final RestChannel channel, final ThreadContext threadContext) {
                assertEquals(request.uri(), url);
                final var response = RestResponse.chunked(
                    OK,
                    ChunkedRestResponseBodyPart.fromTextChunks(RestResponse.TEXT_CONTENT_TYPE, Collections.emptyIterator()),
                    responseReleasedLatch::countDown
                );
                transportClosedFuture.addListener(ActionListener.running(() -> channel.sendResponse(response)));
                handlingRequestLatch.countDown();
            }

            @Override
            public void dispatchBadRequest(final RestChannel channel, final ThreadContext threadContext, final Throwable cause) {
                fail(cause, "--> Unexpected bad request [%s]", FakeRestRequest.requestToString(channel.request()));
            }
        };

        try (
            Netty4HttpServerTransport transport = new Netty4HttpServerTransport(
                clientCancel
                    ? Settings.EMPTY
                    : Settings.builder().put(SETTING_HTTP_SERVER_SHUTDOWN_GRACE_PERIOD.getKey(), TimeValue.timeValueMillis(1)).build(),
                networkService,
                threadPool,
                xContentRegistry(),
                dispatcher,
                clusterSettings,
                new SharedGroupFactory(Settings.EMPTY),
                TelemetryProvider.NOOP,
                TLSConfig.noTLS(),
                null,
                randomFrom((httpPreRequest, channel, listener) -> listener.onResponse(null), null)
            )
        ) {
            transport.start();
            final var address = randomFrom(transport.boundAddress().boundAddresses()).address();
            try (var client = RestClient.builder(new HttpHost(address.getAddress(), address.getPort())).build()) {
                final var responseExceptionFuture = new PlainActionFuture<Exception>();
                final var cancellable = client.performRequestAsync(
                    new Request("GET", url),
                    ActionTestUtils.wrapAsRestResponseListener(ActionTestUtils.assertNoSuccessListener(responseExceptionFuture::onResponse))
                );
                safeAwait(handlingRequestLatch);
                if (clientCancel) {
                    threadPool.generic().execute(cancellable::cancel);
                }
                transport.close();
                transportClosedFuture.onResponse(null);
                safeAwait(responseReleasedLatch);
                final var responseException = safeGet(responseExceptionFuture);
                if (clientCancel) {
                    assertThat(responseException, instanceOf(CancellationException.class));
                } else {
                    assertThat(responseException, instanceOf(ConnectionClosedException.class));
                }
            }
        }
    }

    private Netty4HttpServerTransport getTestNetty4HttpServerTransport(
        HttpServerTransport.Dispatcher dispatcher,
        HttpValidator httpValidator,
        BiConsumer<RestRequest, ThreadContext> populatePerRequestContext
    ) {
        return getTestNetty4HttpServerTransport(createSettings(), dispatcher, httpValidator, populatePerRequestContext);
    }

    private Netty4HttpServerTransport getTestNetty4HttpServerTransport(
        Settings settings,
        HttpServerTransport.Dispatcher dispatcher,
        HttpValidator httpValidator,
        BiConsumer<RestRequest, ThreadContext> populatePerRequestContext
    ) {
        return new Netty4HttpServerTransport(
            settings,
            networkService,
            threadPool,
            xContentRegistry(),
            dispatcher,
            clusterSettings,
            new SharedGroupFactory(settings),
            TelemetryProvider.NOOP,
            TLSConfig.noTLS(),
            null,
            httpValidator
        ) {
            @Override
            protected void populatePerRequestThreadContext(RestRequest restRequest, ThreadContext threadContext) {
                populatePerRequestContext.accept(restRequest, threadContext);
            }
        };
    }

    private Settings createSettings() {
        return createBuilderWithPort().build();
    }

    private Settings.Builder createBuilderWithPort() {
        return Settings.builder().put(HttpTransportSettings.SETTING_HTTP_PORT.getKey(), getPortRange());
    }

    private static RestRequest.Method translateRequestMethod(HttpMethod httpMethod) {
        if (httpMethod == HttpMethod.GET) return RestRequest.Method.GET;

        if (httpMethod == HttpMethod.POST) return RestRequest.Method.POST;

        if (httpMethod == HttpMethod.PUT) return RestRequest.Method.PUT;

        if (httpMethod == HttpMethod.DELETE) return RestRequest.Method.DELETE;

        if (httpMethod == HttpMethod.PATCH) {
            return RestRequest.Method.PATCH;
        }

        throw new IllegalArgumentException("Unexpected http method: " + httpMethod);
    }

    private static void tamperThreadContext(ThreadContext threadContext) {
        boolean tampered = false;
        if (randomBoolean()) {
            threadContext.putHeader(randomAlphaOfLength(16), "tampered with request header");
            tampered = true;
        }
        if (randomBoolean()) {
            threadContext.putTransient(randomAlphaOfLength(16), "tampered with transient request header");
            tampered = true;
        }
        if (randomBoolean() || tampered == false) {
            threadContext.addResponseHeader(randomAlphaOfLength(8), "tampered with response header");
        }
    }

    private static void assertThreadContextNotTampered(ThreadContext threadContext) {
        if (false == threadContext.isDefaultContext()) {
            throw new AssertionError("tampered thread context");
        }
        Transports.assertTransportThread();
    }
}
