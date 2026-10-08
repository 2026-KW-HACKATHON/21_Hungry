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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.SourceText;

class OpenAiProcessingAdapterTests {
    private final AtomicReference<String> requestBody=new AtomicReference<>();
    @Test void rateLimitIsRetryable()throws Exception{withServer(429,"{}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_RATE_LIMITED",e.code());assertTrue(e.retryable());assertEquals(Duration.ofSeconds(7),e.retryAfter());});}
    @Test void providerInputLimitIsNotRetried()throws Exception{withServer(413,"{\"error\":{\"code\":\"file_too_large\"}}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_INPUT_LIMIT_EXCEEDED",e.code());assertFalse(e.retryable());});}
    @Test void responsesKeepProductionV10DisableStorageAndSeparateTrustedInstructions()throws Exception{String modelJson="""
            {"schemaVersion":"1.0","summary":null,"details":{"symptoms":[],"tests":[],"medicationMentions":[],"precautions":[],"followUps":[]},"evidence":[],"items":[]}
            """;String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+new ObjectMapper().writeValueAsString(modelJson)+"}]}]}";withServer(200,response,0,adapter->{var result=adapter.analyze(input());assertEquals("1.0",result.promptVersion());String request=requestBody.get();assertTrue(request.contains("\"store\":false"));assertTrue(request.contains("\"role\":\"developer\""));assertTrue(request.contains("SERVER_CONTEXT_JSON"));assertTrue(request.contains("You extract only facts explicitly supported"));assertFalse(request.contains("Reorganize the conversation by clinical meaning"));assertTrue(request.contains("Medication items always require user review"));String providerSchema=new ObjectMapper().readTree(request).at("/text/format/schema").toString();assertTrue(providerSchema.contains("\"title\":{\"type\":[\"string\",\"null\"]}"));assertFalse(providerSchema.contains("uniqueItems"));assertFalse(providerSchema.contains("\"format\":\"date\""));assertFalse(providerSchema.contains("\"format\":\"time\""));assertFalse(providerSchema.contains("\"format\":\"uuid\""));assertFalse(providerSchema.contains("minLength"));assertFalse(providerSchema.contains("maxLength"));assertFalse(providerSchema.contains("minimum"));assertFalse(providerSchema.contains("maximum"));assertFalse(providerSchema.contains("https://json-schema.org"));});}
    @Test void summaryV11RequiresExplicitVersionSelection()throws Exception{
        String modelJson="""
                {"schemaVersion":"1.0","summary":null,"details":{"symptoms":[],"tests":[],"medicationMentions":[],"precautions":[],"followUps":[]},"evidence":[],"items":[]}
                """;
        String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+new ObjectMapper().writeValueAsString(modelJson)+"}]}]}";
        withServer(200,response,0,Duration.ofSeconds(2),"1.1",adapter->{
            assertEquals("1.1",adapter.analyze(input()).promptVersion());
            String request=requestBody.get();
            assertTrue(request.contains("Reorganize the conversation by clinical meaning"));
            assertTrue(request.contains("Patient statements are not clinician findings"));
            assertTrue(request.contains("Medication items always require user review"));
        });
    }
    @Test void selectedV181UsesSemanticSchemaPromptAndParser()throws Exception{
        var sources=new MutablePropertySources();
        new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yaml")).forEach(sources::addLast);
        var configured=new PropertySourcesPropertyResolver(sources);
        String protocol=configured.getProperty("app.ai.openai.analysis-protocol");
        String promptVersion=configured.getProperty("app.ai.openai.analysis-prompt-version");
        assertEquals("semantic-facts-v2",protocol);
        assertEquals("",promptVersion);
        String modelJson="""
                {"schemaVersion":"1.8.1","facts":[],"summaryFactIds":[]}
                """;
        String response="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":"+new ObjectMapper().writeValueAsString(modelJson)+"}]}]}";
        withServer(200,response,0,Duration.ofSeconds(2),protocol,promptVersion,adapter->{
            var result=adapter.analyze(input());
            assertEquals("1.8.1",result.promptVersion());
            assertTrue(result.items().isEmpty());
            var request=new ObjectMapper().readTree(requestBody.get());
            assertTrue(request.at("/input/0/content/0/text").asText().contains("Internal semantic-facts-v2 protocol"));
            assertTrue(request.at("/input/1/content/0/text").asText().contains("utterances"));
            assertEquals("1.8.1",request.at("/text/format/schema/properties/schemaVersion/const").asText());
            assertTrue(request.at("/text/format/schema/properties/facts").isObject());
            assertTrue(request.at("/text/format/schema/properties/items").isMissingNode());
        });
    }
    @Test void refusalIsNotStoredAsSuccess()throws Exception{String response="""
            {"status":"completed","output":[{"content":[{"type":"refusal","refusal":"cannot"}]}]}
            """;withServer(200,response,0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_REFUSAL",e.code());assertFalse(e.retryable());});}
    @Test void incompleteIsNotRetriedWithTheSameBoundedInput()throws Exception{withServer(200,"{\"status\":\"incomplete\",\"output\":[]}",0,adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_OUTPUT_INCOMPLETE",e.code());assertFalse(e.retryable());});}
    @Test void requestTimeoutHasSafeCode()throws Exception{withServer(200,"{}",250,Duration.ofMillis(50),adapter->{AiProviderException e=assertThrows(AiProviderException.class,()->adapter.analyze(input()));assertEquals("AI_TIMEOUT",e.code());assertTrue(e.retryable());});}
    @Test void missingKeyDoesNotUseFakeFallback(){ObjectMapper json=new ObjectMapper();var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://localhost:1/v1","","gpt-transcribe","gpt-5.4-mini","gpt-5.4-mini",Duration.ofSeconds(1),100,HttpClient.newHttpClient());assertEquals("AI_NOT_CONFIGURED",assertThrows(AiProviderException.class,()->adapter.analyze(input())).code());}

    private void withServer(int status,String response,long delay,CheckedConsumer action)throws Exception{withServer(status,response,delay,Duration.ofSeconds(2),action);}
    private void withServer(int status,String response,long delay,Duration timeout,CheckedConsumer action)throws Exception{withServer(status,response,delay,timeout,"",action);}
    private void withServer(int status,String response,long delay,Duration timeout,String promptVersion,CheckedConsumer action)throws Exception{withServer(status,response,delay,timeout,"legacy",promptVersion,action);}
    private void withServer(int status,String response,long delay,Duration timeout,String protocol,String promptVersion,CheckedConsumer action)throws Exception{HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool());server.createContext("/v1/responses",exchange->{try{if(delay>0)Thread.sleep(delay);byte[] body=response.getBytes(StandardCharsets.UTF_8);requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));if(status==429)exchange.getResponseHeaders().add("Retry-After","7");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();try{ObjectMapper json=new ObjectMapper();var adapter=new OpenAiProcessingAdapter(json,new AnalysisOutputValidator(json),"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-key","gpt-transcribe","gpt-5.4-mini","gpt-5.4-mini",timeout,100,HttpClient.newBuilder().connectTimeout(timeout).build(),protocol,promptVersion);action.accept(adapter);}finally{server.stop(0);}}
    private AnalysisInput input(){String id="44444444-4444-4444-8444-444444444444";return new AnalysisInput(LocalDate.of(2026,10,7),Instant.parse("2026-10-07T00:00:00Z"),"Asia/Seoul",List.of(new SourceText(id,1,"AUDIO","audio/wav","가상 원문")),List.of());}
    @FunctionalInterface private interface CheckedConsumer{void accept(OpenAiProcessingAdapter adapter)throws Exception;}
}
