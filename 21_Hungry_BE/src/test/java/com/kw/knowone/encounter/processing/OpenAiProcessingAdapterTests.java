package com.kw.knowone.encounter.processing;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.SourceText;

class OpenAiProcessingAdapterTests {
    private final AtomicReference<String> requestBody=new AtomicReference<>();
    @Test void rateLimitIsRetryable()throws Exception{withServer(429,"{}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_RATE_LIMITED",e.code());assertTrue(e.retryable());assertEquals(Duration.ofSeconds(7),e.retryAfter());});}
    @Test void providerInputLimitIsNotRetried()throws Exception{withServer(413,"{\"error\":{\"code\":\"file_too_large\"}}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_INPUT_LIMIT_EXCEEDED",e.code());assertFalse(e.retryable());});}
    @Test void responsesDisableStorageAndSeparateTrustedInstructions()throws Exception{String modelJson="""
            {"schemaVersion":"1.0","summary":null,"details":{"symptoms":[],"tests":[],"medicationMentions":[],"precautions":[],"followUps":[]},"evidence":[],"items":[]}
            """;String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+new ObjectMapper().writeValueAsString(modelJson)+"}]}]}";withServer(200,response,0,adapter->{adapter.analyze(input());assertTrue(requestBody.get().contains("\"store\":false"));assertTrue(requestBody.get().contains("\"role\":\"developer\""));assertTrue(requestBody.get().contains("SERVER_CONTEXT_JSON"));assertTrue(requestBody.get().contains("\"title\":{\"type\":[\"string\",\"null\"]}"));assertFalse(requestBody.get().contains("uniqueItems"));assertFalse(requestBody.get().contains("\"format\":\"date\""));assertFalse(requestBody.get().contains("\"format\":\"time\""));assertFalse(requestBody.get().contains("\"format\":\"uuid\""));assertFalse(requestBody.get().contains("minLength"));assertFalse(requestBody.get().contains("maxLength"));assertFalse(requestBody.get().contains("minimum"));assertFalse(requestBody.get().contains("maximum"));assertFalse(requestBody.get().contains("https://json-schema.org"));});}
    @Test void refusalIsNotStoredAsSuccess()throws Exception{String response="""
            {"status":"completed","output":[{"content":[{"type":"refusal","refusal":"cannot"}]}]}
            """;withServer(200,response,0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_REFUSAL",e.code());assertFalse(e.retryable());});}
    @Test void incompleteIsNotRetriedWithTheSameBoundedInput()throws Exception{withServer(200,"{\"status\":\"incomplete\",\"output\":[]}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_OUTPUT_INCOMPLETE",e.code());assertFalse(e.retryable());});}
    @Test void requestTimeoutHasSafeCode()throws Exception{withServer(200,"{}",250,Duration.ofMillis(50),adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_TIMEOUT",e.code());assertTrue(e.retryable());});}
    @Test void missingKeyDoesNotUseFakeFallback(){ObjectMapper json=new ObjectMapper();var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1/v1","","gpt-transcribe","gpt-5.4-mini","gpt-5.4-mini",Duration.ofSeconds(1),100,HttpClient.newHttpClient());assertEquals("AI_NOT_CONFIGURED",assertThrows(AiProviderException.class,()->adapter.analyze(input())).code());}

    private void withServer(int status,String response,long delay,CheckedConsumer action)throws Exception{withServer(status,response,delay,Duration.ofSeconds(2),action);}
    private void withServer(int status,String response,long delay,Duration timeout,CheckedConsumer action)throws Exception{HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool());server.createContext("/v1/responses",exchange->{try{if(delay>0)Thread.sleep(delay);byte[] body=response.getBytes(StandardCharsets.UTF_8);requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(status==429)exchange.getResponseHeaders().add("Retry-After","7");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();try{ObjectMapper json=new ObjectMapper();var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-key","gpt-transcribe","gpt-5.4-mini","gpt-5.4-mini",timeout,100,HttpClient.newBuilder().connectTimeout(timeout).build());action.accept(adapter);}finally{server.stop(0);}}
    private AnalysisInput input(){String id="44444444-4444-4444-8444-444444444444";return new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-07T00:00:00Z"),"Asia/Seoul",List.of(new SourceText(id,1,"AUDIO","audio/wav","가상 원문")),List.of());}
    @FunctionalInterface private interface CheckedConsumer{void accept(OpenAiProcessingAdapter adapter)throws Exception;}
}
