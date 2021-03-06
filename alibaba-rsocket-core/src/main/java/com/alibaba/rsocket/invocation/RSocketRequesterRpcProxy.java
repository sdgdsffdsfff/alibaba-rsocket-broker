package com.alibaba.rsocket.invocation;


import com.alibaba.rsocket.MutableContext;
import com.alibaba.rsocket.encoding.RSocketEncodingFacade;
import com.alibaba.rsocket.metadata.MessageMimeTypeMetadata;
import com.alibaba.rsocket.metadata.MessageTagsMetadata;
import com.alibaba.rsocket.metadata.RSocketCompositeMetadata;
import com.alibaba.rsocket.metadata.RSocketMimeType;
import com.alibaba.rsocket.upstream.UpstreamCluster;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Metrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.frame.FrameType;
import io.rsocket.util.ByteBufPayload;
import org.jetbrains.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

import javax.cache.annotation.CacheResult;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


/**
 * RSocket requester RPC proxy for remote service
 *
 * @author leijuan
 */
public class RSocketRequesterRpcProxy implements InvocationHandler {
    private RSocket rsocket;
    /**
     * service interface
     */
    private Class<?> serviceInterface;
    /**
     * cached methods
     */
    private Map<Method, CacheResult> cachedMethods = new HashMap<>();
    /**
     * group, such as datacenter name, region name
     */
    private String group;
    /**
     * service name
     */
    private String service;
    /**
     * service version
     */
    private String version;
    /**
     * endpoint of service
     */
    private String endpoint;
    /**
     * encoding type
     */
    private RSocketMimeType encodingType;
    /**
     * accept encoding types
     */
    private RSocketMimeType[] acceptEncodingTypes;
    /**
     * '
     * timeout for request/response
     */
    private Duration timeout;
    /**
     * encoding facade
     */
    private RSocketEncodingFacade encodingFacade = RSocketEncodingFacade.getInstance();
    /**
     * cache for Request/Response result
     */
    public static final Cache<String, Mono<Object>> rpcCache = Caffeine.newBuilder()
            .maximumSize(500_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    /**
     * java method metadata map cache for performance
     */
    private Map<Method, ReactiveMethodMetadata> methodMetadataMap = new ConcurrentHashMap<>();
    /**
     * interface default method handlers
     */
    private Map<Method, MethodHandle> defaultMethodHandles = new HashMap<>();

    public RSocketRequesterRpcProxy(UpstreamCluster upstream,
                                    String group, Class<?> serviceInterface, @Nullable String service, String version,
                                    RSocketMimeType encodingType, @Nullable RSocketMimeType acceptEncodingType,
                                    Duration timeout, @Nullable String endpoint) {
        this.rsocket = upstream.getLoadBalancedRSocket();
        this.serviceInterface = serviceInterface;
        this.service = serviceInterface.getCanonicalName();
        if (service != null && !service.isEmpty()) {
            this.service = service;
        }
        this.group = group;
        this.version = version;
        this.endpoint = endpoint;
        this.encodingType = encodingType;
        if (acceptEncodingType == null) {
            this.acceptEncodingTypes = defaultAcceptEncodingTypes();
        } else {
            this.acceptEncodingTypes = new RSocketMimeType[]{acceptEncodingType};
        }
        this.timeout = timeout;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        //interface default method validation
        if (method.isDefault()) {
            return getMethodHandle(method, serviceInterface).bindTo(proxy).invokeWithArguments(args);
        }
        MutableContext mutableContext = new MutableContext();
        if (!methodMetadataMap.containsKey(method)) {
            methodMetadataMap.put(method, new ReactiveMethodMetadata(group, service, version,
                    method, encodingType, this.acceptEncodingTypes, endpoint));
            if (method.getAnnotation(CacheResult.class) != null) {
                cachedMethods.put(method, method.getAnnotation(CacheResult.class));
            }
        }
        ReactiveMethodMetadata methodMetadata = methodMetadataMap.get(method);
        //metadata data content
        ByteBuf compositeMetadataBuf = methodMetadata.getCompositeMetadataByteBuf().retainedDuplicate();
        //----- return type deal------
        if (methodMetadata.getRsocketFrameType() == FrameType.REQUEST_CHANNEL) {
            metrics(methodMetadata);
            Payload routePayload;
            Flux<Object> source;
            //1 param or 2 params
            if (args.length == 1) {
                routePayload = ByteBufPayload.create(Unpooled.EMPTY_BUFFER, compositeMetadataBuf);
                source = (Flux<Object>) args[0];
            } else {
                ByteBuf bodyBuffer = encodingFacade.encodingResult(args[0], methodMetadata.getParamEncoding());
                routePayload = ByteBufPayload.create(bodyBuffer, compositeMetadataBuf);
                source = (Flux<Object>) args[1];
            }
            Flux<Payload> payloadFlux = source.startWith(routePayload).map(obj -> ByteBufPayload.create(encodingFacade.encodingResult(obj, encodingType), compositeMetadataBuf));
            Flux<Payload> payloads = rsocket.requestChannel(payloadFlux);
            return payloads.concatMap(payload -> {
                try {
                    RSocketCompositeMetadata compositeMetadata = RSocketCompositeMetadata.from(payload.metadata());
                    return Mono.justOrEmpty(encodingFacade.decodeResult(extractPayloadDataMimeType(compositeMetadata, encodingType), payload.data(), methodMetadata.getInferredClassForReturn()));
                } catch (Exception e) {
                    return Flux.error(e);
                } finally {
                    ReferenceCountUtil.safeRelease(payload);
                }
            }).subscriberContext(mutableContext::putAll);
        } else {
            //body content
            ByteBuf bodyBuffer = encodingFacade.encodingParams(args, methodMetadata.getParamEncoding());
            Class<?> returnType = method.getReturnType();
            if (methodMetadata.getRsocketFrameType() == FrameType.REQUEST_RESPONSE) {
                metrics(methodMetadata);
                final boolean cachedMethod = cachedMethods.containsKey(method);
                if (cachedMethod) {
                    CacheResult cacheResult = cachedMethods.get(method);
                    String key = cacheResult.cacheName() + ":" + generateCacheKey(args);
                    Mono<Object> cachedMono = rpcCache.getIfPresent(key);
                    if (cachedMono != null) {
                        return cachedMono;
                    }
                }
                Mono<Payload> payloadMono = rsocket.requestResponse(ByteBufPayload.create(bodyBuffer, compositeMetadataBuf)).timeout(timeout);
                Mono<Object> result = payloadMono.handle((payload, sink) -> {
                    try {
                        RSocketCompositeMetadata compositeMetadata = RSocketCompositeMetadata.from(payload.metadata());
                        Object obj = encodingFacade.decodeResult(extractPayloadDataMimeType(compositeMetadata, encodingType), payload.data(), methodMetadata.getInferredClassForReturn());
                        if (obj != null) {
                            sink.next(obj);
                            injectContext(compositeMetadata, sink);
                        }
                        sink.complete();
                    } catch (Exception e) {
                        sink.error(e);
                    } finally {
                        ReferenceCountUtil.safeRelease(payload);
                    }
                });
                //cache result
                if (cachedMethod) {
                    result = result.doOnNext(obj -> {
                        CacheResult cacheResult = cachedMethods.get(method);
                        String key = cacheResult.cacheName() + ":" + generateCacheKey(args);
                        rpcCache.put(key, Mono.just(obj).cache());
                    });
                }
                return methodMetadata.getReactiveAdapter().fromPublisher(result, returnType, mutableContext);
            } else if (methodMetadata.getRsocketFrameType() == FrameType.REQUEST_FNF) {
                metrics(methodMetadata);
                return rsocket.fireAndForget(ByteBufPayload.create(bodyBuffer, compositeMetadataBuf));
            } else if (methodMetadata.getRsocketFrameType() == FrameType.REQUEST_STREAM) {
                metrics(methodMetadata);
                Flux<Payload> flux = rsocket.requestStream(ByteBufPayload.create(bodyBuffer, compositeMetadataBuf));
                Flux<Object> result = flux.concatMap((payload) -> {
                    try {
                        RSocketCompositeMetadata compositeMetadata = RSocketCompositeMetadata.from(payload.metadata());
                        return Mono.justOrEmpty(encodingFacade.decodeResult(extractPayloadDataMimeType(compositeMetadata, encodingType), payload.data(), methodMetadata.getInferredClassForReturn()));
                    } catch (Exception e) {
                        return Mono.error(e);
                    } finally {
                        ReferenceCountUtil.safeRelease(payload);
                    }
                });
                return methodMetadata.getReactiveAdapter().fromPublisher(result, returnType, mutableContext);
            } else {
                return Mono.error(new Exception("Unknown RSocket Frame type"));
            }
        }
    }

    protected void metrics(ReactiveMethodMetadata methodMetadata) {
        Metrics.counter(this.service, methodMetadata.getMetricsTags());
    }

    /**
     * Invalidate RPC cache
     *
     * @param key cache key
     */
    public static void invalidateCache(String key) {
        rpcCache.invalidate(key);
    }

    /**
     * Generate cache key, compatible with Spring SimpleKeyGenerator
     *
     * @param params params
     * @return hashcode
     */
    public static Integer generateCacheKey(Object... params) {
        if (params == null || params.length == 0) {
            return 0;
        } else if (params.length == 1) {
            Object param = params[0];
            if (param != null && !param.getClass().isArray()) {
                return param.hashCode();
            }
        }
        return Arrays.deepHashCode(params);
    }

    void injectContext(RSocketCompositeMetadata compositeMetadata, SynchronousSink<?> sink) throws Exception {
        //message tags
        if (compositeMetadata.contains(RSocketMimeType.MessageTags)) {
            MessageTagsMetadata tagsMetadata = MessageTagsMetadata.from(compositeMetadata.getMetadata(RSocketMimeType.MessageTags));
            Map<String, String> tags = tagsMetadata.getTags();
            sink.currentContext().put("_tags", tags);
        }
    }

    private RSocketMimeType extractPayloadDataMimeType(RSocketCompositeMetadata compositeMetadata, RSocketMimeType defaultEncodingType) {
        if (compositeMetadata.contains(RSocketMimeType.MessageMimeType)) {
            MessageMimeTypeMetadata mimeTypeMetadata = MessageMimeTypeMetadata.from(compositeMetadata.getMetadata(RSocketMimeType.MessageMimeType));
            return mimeTypeMetadata.getRSocketMimeType();
        }
        return defaultEncodingType;
    }

    public MethodHandle getMethodHandle(Method method, Class<?> serviceInterface) throws Exception {
        MethodHandle methodHandle = this.defaultMethodHandles.get(method);
        if (methodHandle == null) {
            String version = System.getProperty("java.version");
            if (version.startsWith("1.8.")) {
                Constructor<MethodHandles.Lookup> lookupConstructor = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, Integer.TYPE);
                if (!lookupConstructor.isAccessible()) {
                    lookupConstructor.setAccessible(true);
                }
                methodHandle = lookupConstructor.newInstance(method.getDeclaringClass(), MethodHandles.Lookup.PRIVATE)
                        .unreflectSpecial(method, method.getDeclaringClass());
            } else {
                methodHandle = MethodHandles.lookup().findSpecial(
                        method.getDeclaringClass(),
                        method.getName(),
                        MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                        serviceInterface);
            }
            this.defaultMethodHandles.put(method, methodHandle);
        }
        return methodHandle;
    }

    public RSocketMimeType[] defaultAcceptEncodingTypes() {
        return new RSocketMimeType[]{this.encodingType, RSocketMimeType.Hessian, RSocketMimeType.Java_Object,
                RSocketMimeType.Json, RSocketMimeType.Protobuf, RSocketMimeType.Avor, RSocketMimeType.CBOR,
                RSocketMimeType.Text, RSocketMimeType.Binary};
    }
}
