/*
 *  Copyright (c) 2017, salesforce.com, inc.
 *  All rights reserved.
 *  Licensed under the BSD 3-Clause license.
 *  For full license text, see LICENSE.txt file in the repo root  or https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.rxgrpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.reactivex.Flowable;
import io.reactivex.Single;
import io.reactivex.observers.TestObserver;
import io.reactivex.subscribers.TestSubscriber;
import org.junit.*;

import java.util.concurrent.TimeUnit;

@SuppressWarnings("Duplicates")
public class ChainedCallIntegrationTest {
    @Rule
    public UnhandledRxJavaErrorRule errorRule = new UnhandledRxJavaErrorRule().autoVerifyNoError();

    private Server server;
    private ManagedChannel channel;

    @Before
    public void setupServer() throws Exception {
        RxGreeterGrpc.GreeterImplBase svc = new RxGreeterGrpc.GreeterImplBase() {

            @Override
            public Single<HelloResponse> sayHello(Single<HelloRequest> rxRequest) {
                return rxRequest.map(protoRequest -> response("[" + protoRequest.getName() + "]"));
            }

            @Override
            public Flowable<HelloResponse> sayHelloRespStream(Single<HelloRequest> rxRequest) {
                return rxRequest
                        .map(HelloRequest::getName)
                        .flatMapPublisher(name -> Flowable.just(
                            response("{" + name + "}"),
                            response("/" + name + "/"),
                            response("\\" + name + "\\"),
                            response("(" + name + ")"))
                        );
            }

            @Override
            public Single<HelloResponse> sayHelloReqStream(Flowable<HelloRequest> rxRequest) {
                return rxRequest
                        .map(HelloRequest::getName)
                        .reduce((l, r) -> l + " :: " + r)
                        .toSingle("EMPTY")
                        .map(ChainedCallIntegrationTest::response);
            }

            @Override
            public Flowable<HelloResponse> sayHelloBothStream(Flowable<HelloRequest> rxRequest) {
                return rxRequest
                        .map(HelloRequest::getName)
                        .map(name -> "<" + name + ">")
                        .map(ChainedCallIntegrationTest::response);
            }
        };

        server = ServerBuilder.forPort(0).addService(svc).build().start();
        channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    }

    @After
    public void stopServer() throws InterruptedException {
        server.shutdownNow();
        channel.shutdownNow();

        server = null;
        channel = null;
    }

    @Test
    @Ignore
    public void servicesCanCallOtherServices() throws InterruptedException {
        RxGreeterGrpc.RxGreeterStub stub = RxGreeterGrpc.newRxStub(channel);

        Single<String> chain = Single.just(request("X"))
                // one -> one
                .compose(stub::sayHello)
                .map(ChainedCallIntegrationTest::bridge)
                .doOnSuccess(x -> System.out.println("OO " + x.getName()))

                // one -> many
                .as(stub::sayHelloRespStream)
                .map(ChainedCallIntegrationTest::bridge)
                .doOnNext(x -> System.out.println("OM " + x.getName()))

                // many -> many
                .compose(stub::sayHelloBothStream)
                .map(ChainedCallIntegrationTest::bridge)
                .doOnNext(x -> System.out.println("MM " + x.getName()))

                // many -> one
                .as(stub::sayHelloReqStream)
                .map(ChainedCallIntegrationTest::bridge)
                .doOnSuccess(x -> System.out.println("MO " + x.getName()))

                // one -> one
                .compose(stub::sayHello)
                .doOnSuccess(x -> System.out.println("OO " + x.getMessage()))

                .map(HelloResponse::getMessage);


        TestObserver<String> test = chain.test();

        test.awaitTerminalEvent(2, TimeUnit.SECONDS);
        test.assertComplete();
        test.assertValue("[<{X}> :: </X/> :: <\\X\\> :: <(X)>]");
    }

    private static HelloRequest bridge(HelloResponse response) {
        return request(response.getMessage());
    }

    private static HelloRequest request(String text) {
        return HelloRequest.newBuilder().setName(text).build();
    }

    private static HelloResponse response(String text) {
        return HelloResponse.newBuilder().setMessage(text).build();
    }
}
