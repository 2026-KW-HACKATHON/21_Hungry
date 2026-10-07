package com.kw.knowone.encounter.processing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisResult;
import com.kw.knowone.encounter.processing.AiProcessingPort.TextResult;

@Component
@Profile("!test")
public class OpenAiProcessingAdapter implements AiProcessingPort {
    private static final String PROVIDER="OPENAI",PROMPT_VERSION="1.0";
    private final ObjectMapper json;private final AnalysisOutputValidator validator;private final HttpClient http;
    private final Duration timeout;private final String baseUrl,key,transcribeModel,ocrModel,analysisModel,analysisPrompt,ocrPrompt;
    private final JsonNode analysisSchema,ocrSchema;private final int maxOutputTokens;

    public OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,
            @Value("${app.ai.openai.base-url:https://api.openai.com/v1}")String baseUrl,
            @Value("${app.ai.openai.api-key:}")String key,
            @Value("${app.ai.openai.transcribe-model:gpt-transcribe}")String transcribeModel,
            @Value("${app.ai.openai.ocr-model:gpt-5.4-mini-2026-03-17}")String ocrModel,
            @Value("${app.ai.openai.analysis-model:gpt-5.4-mini-2026-03-17}")String analysisModel,
            @Value("${app.ai.openai.timeout:PT90S}")Duration timeout,
            @Value("${app.ai.openai.max-output-tokens:12000}")int maxOutputTokens) {
        this(json,validator,baseUrl,key,transcribeModel,ocrModel,analysisModel,timeout,maxOutputTokens,
                HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,String baseUrl,String key,
            String transcribeModel,String ocrModel,String analysisModel,Duration timeout,int maxOutputTokens,HttpClient http){
        this.json=json;this.validator=validator;this.baseUrl=baseUrl.replaceAll("/+$","");this.key=key;this.timeout=timeout;
        this.transcribeModel=transcribeModel;this.ocrModel=ocrModel;this.analysisModel=analysisModel;
        this.maxOutputTokens=maxOutputTokens;this.http=http;this.analysisPrompt=resource("ai/analysis-prompt-v1.0.txt");
        this.ocrPrompt=resource("ai/ocr-prompt-v1.0.txt");this.analysisSchema=json.readTree(resource("ai/analysis-output.schema.json"));
        this.ocrSchema=json.readTree(resource("ai/ocr-output.schema.json"));
    }

    @Override public TextResult transcribe(byte[] bytes,String mediaType){
        configured();String boundary="----knowone-"+UUID.randomUUID();byte[] body=multipart(boundary,bytes,mediaType);
        JsonNode root=send(baseRequest("/audio/transcriptions").header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());String text=root.path("text").asText(null);
        if(text==null||text.isBlank())throw new AiProviderException("AI_OUTPUT_INVALID",false);
        return new TextResult(text,PROVIDER,transcribeModel,null,longValue(root,"usage","input_tokens"),longValue(root,"usage","output_tokens"));
    }

    @Override public TextResult ocr(byte[] bytes,String mediaType){
        configured();String encoded=Base64.getEncoder().encodeToString(bytes);Map<String,Object> file="application/pdf".equals(mediaType)
                ?Map.of("type","input_file","filename","document.pdf","file_data","data:application/pdf;base64,"+encoded)
                :Map.of("type","input_image","image_url","data:"+mediaType+";base64,"+encoded,"detail","high");
        JsonNode root=sendJson("/responses",responsesBody(ocrModel,ocrPrompt,"Extract the supplied document.",List.of(file),"ocr_output",ocrSchema));
        String text=validator.validateOcr(outputText(root));return new TextResult(text,PROVIDER,ocrModel,PROMPT_VERSION,
                longValue(root,"usage","input_tokens"),longValue(root,"usage","output_tokens"));
    }

    @Override public AnalysisResult analyze(AnalysisInput input){
        configured();List<Map<String,Object>> sourceValues=new ArrayList<>();for(var source:input.sources()){
            Map<String,Object> value=new LinkedHashMap<>();value.put("sourceId",source.sourceId());value.put("textVersion",source.textVersion());
            value.put("sourceType",source.sourceType());value.put("mediaType",source.mediaType());value.put("text",source.text());sourceValues.add(value);}
        Map<String,Object> context=new LinkedHashMap<>();context.put("occurredOn",input.occurredOn());context.put("analyzedAt",input.analyzedAt());
        context.put("timezone",input.timezone());context.put("sources",sourceValues);context.put("knownMedications",input.knownMedications());
        String inputText="SERVER_CONTEXT_JSON:\n"+json.writeValueAsString(context);
        JsonNode root=sendJson("/responses",responsesBody(analysisModel,analysisPrompt,inputText,List.of(),"encounter_analysis",analysisSchema));
        return validator.validate(outputText(root),input,PROVIDER,analysisModel,PROMPT_VERSION,
                longValue(root,"usage","input_tokens"),longValue(root,"usage","output_tokens"));
    }

    private Map<String,Object> responsesBody(String model,String instructions,String inputText,List<Map<String,Object>> extra,String name,JsonNode schema){
        List<Map<String,Object>> content=new ArrayList<>();content.add(Map.of("type","input_text","text",inputText));content.addAll(extra);
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("input",List.of(
                Map.of("role","developer","content",List.of(Map.of("type","input_text","text",instructions))),
                Map.of("role","user","content",content)));
        body.put("text",Map.of("format",Map.of("type","json_schema","name",name,"strict",true,"schema",providerSchema(schema))));
        body.put("max_output_tokens",maxOutputTokens);body.put("store",false);return body;
    }
    private JsonNode providerSchema(JsonNode schema){JsonNode copy=schema.deepCopy();stripProviderUnsupportedKeywords(copy);return copy;}
    private void stripProviderUnsupportedKeywords(JsonNode value){if(value instanceof ObjectNode object){object.remove("$schema");object.remove("$id");object.remove("title");object.remove("uniqueItems");object.forEach(this::stripProviderUnsupportedKeywords);}else if(value.isArray())value.forEach(this::stripProviderUnsupportedKeywords);}
    private JsonNode sendJson(String path,Object body){return send(baseRequest(path).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build());}
    private HttpRequest.Builder baseRequest(String path){return HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(timeout)
            .header("Authorization","Bearer "+key).header("Accept","application/json");}
    private JsonNode send(HttpRequest request){try{HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));int status=response.statusCode();
        if(status==429)throw new AiProviderException("AI_RATE_LIMITED",true,retryAfter(response));if(status==408||status>=500)throw new AiProviderException(status==408?"AI_TIMEOUT":"AI_PROVIDER_UNAVAILABLE",true);
        if(status<200||status>=300){String providerCode=safeProviderCode(response.body());
            if(status==413||isInputLimit(providerCode))throw new AiProviderException("AI_INPUT_LIMIT_EXCEEDED",false);
            throw new AiProviderException(status==401||status==403?"AI_NOT_CONFIGURED":"AI_PROVIDER_REJECTED",false);}return json.readTree(response.body());
    }catch(java.net.http.HttpTimeoutException e){throw new AiProviderException("AI_TIMEOUT",true,e);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AiProviderException("AI_PROVIDER_UNAVAILABLE",true,e);}
    catch(IOException e){throw new AiProviderException("AI_PROVIDER_UNAVAILABLE",true,e);}catch(AiProviderException e){throw e;}catch(RuntimeException e){throw new AiProviderException("AI_OUTPUT_INVALID",false,e);}}
    private String outputText(JsonNode root){if("incomplete".equals(root.path("status").asText()))throw new AiProviderException("AI_OUTPUT_INCOMPLETE",false);
        JsonNode output=root.get("output");if(output!=null&&output.isArray())for(JsonNode entry:output){JsonNode content=entry.get("content");if(content==null)continue;for(JsonNode part:content){
            if("refusal".equals(part.path("type").asText())||part.has("refusal"))throw new AiProviderException("AI_REFUSAL",false);
            if("output_text".equals(part.path("type").asText())&&part.has("text"))return part.get("text").asText();}}throw new AiProviderException("AI_OUTPUT_INVALID",false);}
    private byte[] multipart(String boundary,byte[] bytes,String mediaType){try{ByteArrayOutputStream out=new ByteArrayOutputStream();
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"+transcribeModel+"\r\n");
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\nko\r\n");
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n한국어 진료 대화입니다. 약명, 숫자, 소수, 단위를 원문대로 전사하세요.\r\n");
        write(out,"--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: "+mediaType+"\r\n\r\n");
        out.write(bytes);write(out,"\r\n--"+boundary+"--\r\n");return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    private void write(ByteArrayOutputStream out,String value)throws IOException{out.write(value.getBytes(StandardCharsets.UTF_8));}
    private long longValue(JsonNode root,String parent,String child){JsonNode node=root.path(parent).path(child);return node.isNumber()?node.asLong():0;}
    private String safeProviderCode(String body){try{JsonNode value=json.readTree(body);return value.path("error").path("code").asText("");}catch(RuntimeException ignored){return "";}}
    private boolean isInputLimit(String code){String value=code==null?"":code.toLowerCase(java.util.Locale.ROOT);return value.contains("context_length")||value.contains("file_too_large")||value.contains("image_too_large")||value.contains("invalid_image");}
    private Duration retryAfter(HttpResponse<?> response){String value=response.headers().firstValue("Retry-After").orElse(null);if(value==null)return null;try{return Duration.ofSeconds(Math.max(1,Long.parseLong(value)));}catch(NumberFormatException ignored){try{return Duration.between(ZonedDateTime.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME)).isNegative()?Duration.ofSeconds(1):Duration.between(ZonedDateTime.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME));}catch(RuntimeException invalid){return null;}}}
    private void configured(){if(key==null||key.isBlank())throw new AiProviderException("AI_NOT_CONFIGURED",false);}
    private String resource(String path){try{return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Missing AI resource: "+path,e);}}
}
