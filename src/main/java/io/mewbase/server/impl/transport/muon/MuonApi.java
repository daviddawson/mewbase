package io.mewbase.server.impl.transport.muon;

import io.mewbase.bson.BsonObject;
import io.mewbase.common.SubDescriptor;
import io.mewbase.server.Log;
import io.mewbase.server.LogReadStream;
import io.mewbase.server.impl.Protocol;
import io.mewbase.server.impl.ServerImpl;
import io.muoncore.Muon;
import io.muoncore.MuonBuilder;
import io.muoncore.codec.Codecs;
import io.muoncore.config.AutoConfiguration;
import io.muoncore.config.MuonConfigBuilder;
import io.muoncore.protocol.event.server.EventServerProtocolStack;
import io.muoncore.protocol.reactivestream.messages.ReactiveStreamSubscriptionRequest;
import io.muoncore.protocol.reactivestream.server.PublisherLookup;
import io.muoncore.protocol.reactivestream.server.ReactiveStreamServer;
import io.muoncore.protocol.rpc.server.RpcServer;
import io.reactivex.BackpressureStrategy;
import io.reactivex.subjects.PublishSubject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static io.muoncore.protocol.rpc.server.HandlerPredicates.path;

public class MuonApi {

    public MuonApi(ServerImpl server) {

        AutoConfiguration config = MuonConfigBuilder.withServiceIdentifier("mewbase")
                .withTags("eventstore")
                .build();

        Muon muon = MuonBuilder.withConfig(config).build();

        EventServerProtocolStack stack = new EventServerProtocolStack(eventWrapper -> {
            server.createChannel(eventWrapper.getEvent().getStreamName()).join();
            Log log = server.getLog(eventWrapper.getEvent().getStreamName());

            //transcode to bson. dirty dirty for POC...

            Codecs.EncodingResult encode = muon.getCodecs().encode(eventWrapper.getEvent(), new String[]{"application/json"});

            Map decode = muon.getCodecs().decode(encode.getPayload(), encode.getContentType(), Map.class);

            decode.put(Protocol.RECEV_TIMESTAMP, System.currentTimeMillis());
            decode.put("event-time", decode.get(Protocol.RECEV_TIMESTAMP));

            Long orderId = log.append(new BsonObject(decode)).join();

            eventWrapper.persisted(orderId, (Long) decode.get(Protocol.RECEV_TIMESTAMP));
        }, muon.getCodecs(), muon.getDiscovery());

        muon.getProtocolStacks().registerServerProtocol(stack);

        RpcServer rpc = new RpcServer(muon);
        ReactiveStreamServer rx = new ReactiveStreamServer(muon);

        rx.publishGeneratedSource("/stream", PublisherLookup.PublisherType.HOT_COLD, subscriber -> {

//            long from = Long.valueOf(request.args["from"] ?: 0 )
//
//            def stream = request.args["stream-name"]
//            def subName = request.args["sub-name"]
//            def streamType = request.args["stream-type"]
//            if (!streamType) streamType = "hot-cold"

            long from = Long.valueOf(subscriber.getArgs().getOrDefault("from", "0"));
            String streamName = subscriber.getArgs().getOrDefault("stream-name", "unknown");

            PublishSubject<Object> stream = PublishSubject.create();

            server.createChannel(streamName).join();
            Log log = server.getLog(streamName);

            LogReadStream subscribe = log.subscribe(new SubDescriptor().setStartEventNum(from));

            subscribe.handler((aLong, entries) -> {
                Map<String, Object> map = entries.getMap();
                map.put("order-id", aLong);
                stream.onNext(map);
            });

            //TODO, detect stream end for cold replay. detect te stream-type above and close on stream completion.... how do we get this from persistence?

            new Thread(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                subscribe.start();
            }).start();

            return stream.toFlowable(BackpressureStrategy.BUFFER);
        });
    }
}
