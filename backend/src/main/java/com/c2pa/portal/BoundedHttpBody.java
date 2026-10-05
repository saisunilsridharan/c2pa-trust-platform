package com.c2pa.portal;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.io.*;
/** Cancels oversized bodies before buffering them; response timeout covers the complete body. */
final class BoundedHttpBody implements HttpResponse.BodySubscriber<byte[]> {
 private final int limit;private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private final CompletableFuture<byte[]> result=new CompletableFuture<>();private Flow.Subscription subscription;
 BoundedHttpBody(int limit){this.limit=limit;}
 public CompletionStage<byte[]> getBody(){return result;}
 public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
 public void onNext(List<ByteBuffer> buffers){try{for(var buffer:buffers){int size=buffer.remaining();if(size>limit-bytes.size())throw new IOException("Response exceeds limit");byte[] block=new byte[size];buffer.get(block);bytes.write(block);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(e);}}
 public void onError(Throwable failure){result.completeExceptionally(failure);}
 public void onComplete(){result.complete(bytes.toByteArray());}
}
