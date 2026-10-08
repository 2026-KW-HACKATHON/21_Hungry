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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisInput;
import com.kw.knowone.encounter.processing.AiProcessingPort.AnalysisResult;
import com.kw.knowone.encounter.processing.AiProcessingPort.TextResult;

@Component
@Profile("!test")
public class OpenAiProcessingAdapter implements AiProcessingPort {
    private static final String PROVIDER="OPENAI",ANALYSIS_PROMPT_VERSION="1.0",OCR_PROMPT_VERSION="1.0";
    private static final Logger log=LoggerFactory.getLogger(OpenAiProcessingAdapter.class);
    private final ObjectMapper json;private final AnalysisOutputValidator validator;private final HttpClient http;
    private final Duration timeout;private final String baseUrl,key,transcribeModel,ocrModel,analysisModel,analysisPrompt,ocrPrompt;
    private final JsonNode analysisSchema,ocrSchema;private final int maxOutputTokens;
    private final String analysisProtocol,analysisPromptVersion;

    @Autowired
    public OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,
            @Value("${app.ai.openai.base-url:https://api.openai.com/v1}")String baseUrl,
            @Value("${app.ai.openai.api-key:}")String key,
            @Value("${app.ai.openai.transcribe-model:gpt-transcribe}")String transcribeModel,
            @Value("${app.ai.openai.ocr-model:gpt-5.4-mini-2026-03-17}")String ocrModel,
            @Value("${app.ai.openai.analysis-model:gpt-5.4-mini-2026-03-17}")String analysisModel,
            @Value("${app.ai.openai.timeout:PT90S}")Duration timeout,
            @Value("${app.ai.openai.max-output-tokens:12000}")int maxOutputTokens,
            @Value("${app.ai.openai.analysis-protocol:legacy}")String analysisProtocol,
            @Value("${app.ai.openai.analysis-prompt-version:}")String promptVersion) {
        this(json,validator,baseUrl,key,transcribeModel,ocrModel,analysisModel,timeout,maxOutputTokens,
                HttpClient.newBuilder().connectTimeout(timeout).build(),analysisProtocol,promptVersion);
    }

    OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,String baseUrl,String key,
            String transcribeModel,String ocrModel,String analysisModel,Duration timeout,int maxOutputTokens,HttpClient http){
        this(json,validator,baseUrl,key,transcribeModel,ocrModel,analysisModel,timeout,maxOutputTokens,http,"legacy");
    }
    OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,String baseUrl,String key,
            String transcribeModel,String ocrModel,String analysisModel,Duration timeout,int maxOutputTokens,HttpClient http,String analysisProtocol){
        this(json,validator,baseUrl,key,transcribeModel,ocrModel,analysisModel,timeout,maxOutputTokens,http,analysisProtocol,"");
    }
    OpenAiProcessingAdapter(ObjectMapper json,AnalysisOutputValidator validator,String baseUrl,String key,
            String transcribeModel,String ocrModel,String analysisModel,Duration timeout,int maxOutputTokens,HttpClient http,String analysisProtocol,String promptVersion){
        if(!List.of("legacy",UtteranceEvidence.PROTOCOL,UtteranceEvidence.GROUNDED_PROTOCOL,SemanticAnalysis.PROTOCOL,SemanticAnalysisV2.PROTOCOL).contains(analysisProtocol))throw new IllegalArgumentException("Unsupported analysis protocol");
        String defaultVersion=switch(analysisProtocol){case "utterance-id-v1" -> "1.5";case "utterance-id-v2" -> "1.6";case "semantic-facts-v1" -> "1.8";case "semantic-facts-v2" -> "1.8.1";default -> ANALYSIS_PROMPT_VERSION;};
        String selected=promptVersion==null||promptVersion.isBlank()?defaultVersion:promptVersion;
        if(!selected.equals(defaultVersion)&&!("legacy".equals(analysisProtocol)&&"1.1".equals(selected))
                &&!(UtteranceEvidence.GROUNDED_PROTOCOL.equals(analysisProtocol)&&List.of("1.7","1.8-prompt").contains(selected)))
            throw new IllegalArgumentException("Unsupported analysis prompt/protocol combination");
        this.analysisProtocol=analysisProtocol;this.analysisPromptVersion=selected;
        this.json=json;this.validator=validator;this.baseUrl=baseUrl.replaceAll("/+$","");this.key=key;this.timeout=timeout;
        this.transcribeModel=transcribeModel;this.ocrModel=ocrModel;this.analysisModel=analysisModel;
        this.maxOutputTokens=maxOutputTokens;this.http=http;this.analysisPrompt=resource("ai/analysis-prompt-v"+analysisPromptVersion+".txt");
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
        String text=validator.validateOcr(outputText(root));return new TextResult(text,PROVIDER,ocrModel,OCR_PROMPT_VERSION,
                longValue(root,"usage","input_tokens"),longValue(root,"usage","output_tokens"));
    }

    @Override public AnalysisResult analyze(AnalysisInput input){
        return parseAnalysis(requestAnalysis(analysisRequest(input,analysisPrompt)),input,analysisPromptVersion);
    }

    // Shared with the DB-free, test-source evaluation runner. No alternative request/parser implementation.
    Map<String,Object> analysisRequest(AnalysisInput input,String prompt){
        // Experimental prompts/layouts have NOT passed the quality gate; keep the production layout.
        return analysisRequest(input,prompt,false);
    }
    Map<String,Object> analysisRequest(AnalysisInput input,String prompt,boolean evidenceFirst){
        if(!"legacy".equals(analysisProtocol)) {
            var catalog=new UtteranceEvidence(json,input);
            Map<String,Object> context=new LinkedHashMap<>();context.put("occurredOn",input.occurredOn());context.put("analyzedAt",input.analyzedAt());
            context.put("timezone",input.timezone());context.put("utterances",catalog.context());context.put("knownMedications",input.knownMedications());
            if(SemanticAnalysis.PROTOCOL.equals(analysisProtocol))return responsesBody(analysisModel,prompt,"SERVER_CONTEXT_JSON:\n"+json.writeValueAsString(context),List.of(),"encounter_analysis_semantic_facts",new SemanticAnalysis(json,catalog).schema());
            if(SemanticAnalysisV2.PROTOCOL.equals(analysisProtocol))return responsesBody(analysisModel,prompt,"SERVER_CONTEXT_JSON:\n"+json.writeValueAsString(context),List.of(),"encounter_analysis_semantic_facts",new SemanticAnalysisV2(json,catalog).schema());
            return responsesBody(analysisModel,prompt,"SERVER_CONTEXT_JSON:\n"+json.writeValueAsString(context),List.of(),"encounter_analysis_utterance_ids",catalog.schema(analysisSchema,UtteranceEvidence.GROUNDED_PROTOCOL.equals(analysisProtocol)));
        }
        List<Map<String,Object>> sourceValues=new ArrayList<>();for(var source:input.sources()){
            Map<String,Object> value=new LinkedHashMap<>();value.put("sourceId",source.sourceId());value.put("textVersion",source.textVersion());
            value.put("sourceType",source.sourceType());value.put("mediaType",source.mediaType());value.put("text",source.text());sourceValues.add(value);}
        Map<String,Object> context=new LinkedHashMap<>();context.put("occurredOn",input.occurredOn());context.put("analyzedAt",input.analyzedAt());
        context.put("timezone",input.timezone());context.put("sources",sourceValues);context.put("knownMedications",input.knownMedications());
        String inputText="SERVER_CONTEXT_JSON:\n"+json.writeValueAsString(context);
        return responsesBody(analysisModel,prompt,inputText,List.of(),"encounter_analysis",evidenceFirst?evidenceFirstSchema(analysisSchema):analysisSchema);
    }
    JsonNode requestAnalysis(Map<String,Object> request){configured();return sendJson("/responses",request);}
    AnalysisResult parseAnalysis(JsonNode root,AnalysisInput input,String promptVersion){
        if(longValue(root,"usage","output_tokens")>=maxOutputTokens)throw new AiProviderException("AI_OUTPUT_INCOMPLETE",false);
        String raw=outputText(root);
        if(SemanticAnalysis.PROTOCOL.equals(analysisProtocol))raw=new SemanticAnalysis(json,new UtteranceEvidence(json,input)).resolve(raw);
        else if(SemanticAnalysisV2.PROTOCOL.equals(analysisProtocol))raw=new SemanticAnalysisV2(json,new UtteranceEvidence(json,input)).resolve(raw);
        else if(!"legacy".equals(analysisProtocol))raw=new UtteranceEvidence(json,input).resolve(raw,UtteranceEvidence.GROUNDED_PROTOCOL.equals(analysisProtocol));
        return validator.validate(raw,input,PROVIDER,analysisModel,promptVersion,
                longValue(root,"usage","input_tokens"),longValue(root,"usage","output_tokens"));
    }

    // Same fields/types/required contract; emit supporting spans before their referencing indexes.
    static JsonNode evidenceFirstSchema(JsonNode schema){
        ObjectNode copy=(ObjectNode)schema.deepCopy();ObjectNode original=(ObjectNode)copy.get("properties");
        ObjectNode ordered=original.objectNode();ordered.set("schemaVersion",original.get("schemaVersion"));
        ordered.set("evidence",original.get("evidence"));original.properties().forEach(entry->{
            if(!ordered.has(entry.getKey()))ordered.set(entry.getKey(),entry.getValue());});
        copy.set("properties",ordered);return copy;
    }

    private Map<String,Object> responsesBody(String model,String instructions,String inputText,List<Map<String,Object>> extra,String name,JsonNode schema){
        List<Map<String,Object>> content=new ArrayList<>();content.add(Map.of("type","input_text","text",inputText));content.addAll(extra);
        Map<String,Object> body=new LinkedHashMap<>();body.put("model",model);body.put("input",List.of(
                Map.of("role","developer","content",List.of(Map.of("type","input_text","text",instructions))),
                Map.of("role","user","content",content)));
        body.put("text",Map.of("format",Map.of("type","json_schema","name",name,"strict",true,"schema",providerSchema(schema))));
        body.put("max_output_tokens",maxOutputTokens);body.put("store",false);return body;
    }
    private JsonNode providerSchema(JsonNode schema){JsonNode copy=schema.deepCopy();if(copy instanceof ObjectNode root){root.remove("$schema");root.remove("$id");root.remove("title");}stripProviderUnsupportedKeywords(copy,UtteranceEvidence.GROUNDED_PROTOCOL.equals(analysisProtocol)&&schema.path("$defs").has("utteranceId"));return copy;}
    private void stripProviderUnsupportedKeywords(JsonNode value,boolean numericBounds){if(value instanceof ObjectNode object){object.remove(List.of(
                "uniqueItems","format","minLength","maxLength","pattern",
                "multipleOf","minItems","maxItems"));if(!numericBounds)object.remove(List.of("minimum","maximum"));object.forEach(n->stripProviderUnsupportedKeywords(n,numericBounds));
        }else if(value.isArray())value.forEach(n->stripProviderUnsupportedKeywords(n,numericBounds));}
    private JsonNode sendJson(String path,Object body){return send(baseRequest(path).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body),StandardCharsets.UTF_8)).build());}
    private HttpRequest.Builder baseRequest(String path){return HttpRequest.newBuilder(URI.create(baseUrl+path)).timeout(timeout)
            .header("Authorization","Bearer "+key).header("Accept","application/json");}
    private JsonNode send(HttpRequest request){try{HttpResponse<String> response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));int status=response.statusCode();
        if(status==429)throw new AiProviderException("AI_RATE_LIMITED",true,retryAfter(response));if(status==408||status>=500)throw new AiProviderException(status==408?"AI_TIMEOUT":"AI_PROVIDER_UNAVAILABLE",true);
        if(status<200||status>=300){ProviderError provider=safeProviderError(response.body());String providerCode=provider.code();
            log.warn("OpenAI request rejected status={} requestId={} providerType={} providerCode={} providerParam={} schemaDetail={}",status,
                    response.headers().firstValue("x-request-id").orElse("-"),provider.type(),provider.code(),provider.param(),provider.schemaDetail());
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
    private ProviderError safeProviderError(String body){try{JsonNode error=json.readTree(body).path("error");String code=safe(error.path("code").asText(""),120);
        String detail="invalid_json_schema".equals(code)?safe(error.path("message").asText(""),300):"";
        return new ProviderError(safe(error.path("type").asText(""),120),code,safe(error.path("param").asText(""),120),detail);
    }catch(RuntimeException ignored){return new ProviderError("","","","");}}
    private String safe(String value,int limit){if(value==null)return "";String sanitized=value.replaceAll("[^A-Za-z0-9_.,:()'\\[\\]/$#=-]","_");return sanitized.substring(0,Math.min(limit,sanitized.length()));}
    private boolean isInputLimit(String code){String value=code==null?"":code.toLowerCase(java.util.Locale.ROOT);return value.contains("context_length")||value.contains("file_too_large")||value.contains("image_too_large")||value.contains("invalid_image");}
    private Duration retryAfter(HttpResponse<?> response){String value=response.headers().firstValue("Retry-After").orElse(null);if(value==null)return null;try{return Duration.ofSeconds(Math.max(1,Long.parseLong(value)));}catch(NumberFormatException ignored){try{return Duration.between(ZonedDateTime.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME)).isNegative()?Duration.ofSeconds(1):Duration.between(ZonedDateTime.now(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME));}catch(RuntimeException invalid){return null;}}}
    private void configured(){if(key==null||key.isBlank())throw new AiProviderException("AI_NOT_CONFIGURED",false);}
    private String resource(String path){try{return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException("Missing AI resource: "+path,e);}}
    private record ProviderError(String type,String code,String param,String schemaDetail){ }
}
