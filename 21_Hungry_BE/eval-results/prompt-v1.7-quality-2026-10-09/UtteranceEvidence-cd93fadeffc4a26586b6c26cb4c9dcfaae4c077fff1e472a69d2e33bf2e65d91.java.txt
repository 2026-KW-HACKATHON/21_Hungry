package com.kw.knowone.encounter.processing;

import com.kw.knowone.encounter.processing.AiProcessingPort.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.*;

/** Internal provider protocol. IDs identify immutable source spans, NOT verified medical facts. */
final class UtteranceEvidence {
    static final String PROTOCOL="utterance-id-v1";
    static final String GROUNDED_PROTOCOL="utterance-id-v2";
    static final List<String> DETAIL_FIELDS=List.of("symptoms","tests","medicationMentions","precautions","followUps");
    // Only explicit source labels are recognized. Undiarized transcription remains UNKNOWN.
    private static final Pattern INLINE_SPEAKER=Pattern.compile("(?<!\\S)(?:\\[\\d+\\]\\s*)?(?:의사|환자|doctor|patient):\\s*",Pattern.CASE_INSENSITIVE);
    private final ObjectMapper json;
    private final LinkedHashMap<String,Span> spans=new LinkedHashMap<>();
    record Span(String id,SourceText source,Integer page,int charStart,int charEnd,String speaker) {
        String quote(){return source.text().substring(charStart,charEnd);}
    }

    UtteranceEvidence(ObjectMapper json,AnalysisInput input) {
        this.json=json;var sourceIds=new HashSet<String>();
        for(var source:input.sources()) {
            require(source.sourceId()!=null&&source.textVersion()>0&&source.text()!=null&&sourceIds.add(source.sourceId()),"AI_INPUT_INVALID");
            String text=source.text();int page=1,start=0,ordinal=0;
            for(int end=0;end<=text.length();end++) {
                if(end<text.length()&&text.charAt(end)!='\n'&&text.charAt(end)!='\r'&&text.charAt(end)!='\f')continue;
                var boundaries=new TreeSet<Integer>();boundaries.add(start);boundaries.add(end);
                var matches=INLINE_SPEAKER.matcher(text).region(start,end);
                while(matches.find())boundaries.add(matches.start());
                var points=new ArrayList<>(boundaries);
                for(int n=0;n+1<points.size();n++) {
                    int a=points.get(n),b=points.get(n+1);
                    while(a<b&&Character.isWhitespace(text.charAt(a)))a++;
                    while(b>a&&Character.isWhitespace(text.charAt(b-1)))b--;
                    if(a==b)continue;
                    String id="s"+digest(source.sourceId()).substring(0,12)+"v"+source.textVersion()+"u"+String.format(Locale.ROOT,"%04d",++ordinal);
                    String quote=text.substring(a,b);String speaker="UNKNOWN";
                    String label=quote.replaceFirst("^\\[\\d+\\]\\s*","").toLowerCase(Locale.ROOT);
                    if(label.startsWith("의사:")||label.startsWith("doctor:"))speaker="CLINICIAN";
                    else if(label.startsWith("환자:")||label.startsWith("patient:"))speaker="PATIENT";
                    Integer sourcePage="DOCUMENT".equals(source.sourceType())&&"application/pdf".equals(source.mediaType())?page:null;
                    require(spans.putIfAbsent(id,new Span(id,source,sourcePage,a,b,speaker))==null,"AI_INPUT_INVALID");
                }
                if(end<text.length()&&text.charAt(end)=='\f')page++;
                start=end+1;
            }
        }
        // Provider enum has a documented global 1,000-value limit; leave room for the payload enums.
        require(!spans.isEmpty()&&spans.size()<=900&&spans.keySet().stream().mapToInt(String::length).sum()<=14000,"AI_INPUT_LIMIT_EXCEEDED");
    }
    Collection<Span> spans(){return Collections.unmodifiableCollection(spans.values());}
    ArrayNode context() {
        var result=json.createArrayNode();
        for(var s:spans.values()) {
            var row=result.addObject();row.put("id",s.id()).put("speaker",s.speaker()).put("sourceType",s.source().sourceType())
                .put("sourceId",s.source().sourceId()).put("textVersion",s.source().textVersion()).put("text",s.quote());
            if(s.page()==null)row.putNull("page");else row.put("page",s.page());
        }
        return result;
    }
    JsonNode schema(JsonNode external) {
        return schema(external,false);
    }
    JsonNode schema(JsonNode external,boolean idsFirst) {
        ObjectNode root=(ObjectNode)external.deepCopy(),properties=(ObjectNode)root.get("properties"),defs=(ObjectNode)root.get("$defs");
        root.put("$id","https://knowone.local/schemas/analysis-utterance-id-v1.json");
        root.put("title","Internal utterance-linked analysis");
        properties.remove("evidence");root.set("required",json.valueToTree(List.of("schemaVersion","summary","details","items")));
        var refs=json.createObjectNode().put("type","array");refs.set("items",json.createObjectNode().put("$ref","#/$defs/utteranceId"));
        var id=json.createObjectNode().put("type","string");id.set("enum",json.valueToTree(spans.keySet()));defs.set("utteranceId",id);
        var claim=json.createObjectNode().put("type","object").put("additionalProperties",false);
        claim.set("required",json.valueToTree(List.of("text","utteranceIds")));
        var claimProperties=claim.putObject("properties");claimProperties.putObject("text").put("type","string");claimProperties.set("utteranceIds",refs);
        defs.set("claim",claim);
        var claims=json.createObjectNode().put("type","array");claims.putObject("items").put("$ref","#/$defs/claim");
        properties.set("summary",claims);defs.set("details",claims);
        ObjectNode item=(ObjectNode)properties.at("/items/items");
        ((ObjectNode)item.get("properties")).remove("evidence");((ObjectNode)item.get("properties")).set("utteranceIds",refs);
        item.set("required",json.valueToTree(List.of("itemKey","itemType","payload","utteranceIds","uncertaintyCodes","isConditional")));
        defs.remove(List.of("evidenceList","evidence"));
        if(idsFirst) {
            // Choose support before wording the claim, and before extracting any actionable payload.
            ObjectNode ordered=json.createObjectNode();ordered.set("utteranceIds",refs);ordered.set("text",claimProperties.get("text"));
            claim.set("properties",ordered);
            var old=(ObjectNode)item.get("properties");var itemOrdered=json.createObjectNode();itemOrdered.set("utteranceIds",old.get("utteranceIds"));
            old.properties().forEach(e->{if(!e.getKey().equals("utteranceIds"))itemOrdered.set(e.getKey(),e.getValue());});item.set("properties",itemOrdered);
        }
        return root;
    }
    String resolve(String raw) {
        return resolve(raw,false);
    }
    String resolve(String raw,boolean strictNumeric) {
        try {
            JsonNode in=json.readTree(raw);exact(in,Set.of("schemaVersion","summary","details","items"));
            require("1.0".equals(in.path("schemaVersion").asText())&&in.path("summary").isArray()&&in.path("items").isArray(),"AI_SCHEMA_INVALID");
            exact(in.path("details"),new HashSet<>(DETAIL_FIELDS));
            var out=json.createObjectNode();out.put("schemaVersion","1.0");
            var evidence=out.putArray("evidence");var rootIndexes=new LinkedHashMap<String,Integer>();
            var summary=new ArrayList<String>();
            for(var claim:in.get("summary")){validateClaim(claim);indexes(claim.get("utteranceIds"),evidence,rootIndexes);summary.add(claim.get("text").asText());}
            if(summary.isEmpty())out.putNull("summary");else out.put("summary",String.join(" ",summary));
            var details=out.putObject("details");
            for(String field:DETAIL_FIELDS) {
                require(in.path("details").path(field).isArray(),"AI_SCHEMA_INVALID");var list=details.putArray(field);
                for(var claim:in.path("details").get(field)) {
                    validateClaim(claim);var entry=list.addObject();entry.set("text",claim.get("text"));
                    entry.set("evidenceIndexes",indexes(claim.get("utteranceIds"),evidence,rootIndexes));
                }
            }
            var items=out.putArray("items");
            for(var item:in.get("items")) {
                exact(item,Set.of("itemKey","itemType","payload","utteranceIds","uncertaintyCodes","isConditional"));
                if(strictNumeric) {
                    var p=item.path("payload");
                    checkDuration(p.path("durationMinutes"),true);
                    for(var plan:p.path("schedulePlans"))checkDuration(plan.path("durationMinutes"),false);
                }
                ObjectNode copy=(ObjectNode)item.deepCopy();copy.remove("utteranceIds");
                var itemEvidence=copy.putArray("evidence");
                for(var span:selected(item.get("utteranceIds")))itemEvidence.add(evidence(span));
                items.add(copy);
            }
            return json.writeValueAsString(out);
        }catch(AiProviderException e){throw e;}catch(RuntimeException e){throw new AiProviderException("AI_SCHEMA_INVALID",false,e);}
    }
    private void validateClaim(JsonNode claim){exact(claim,Set.of("text","utteranceIds"));require(claim.path("text").isTextual()&&!claim.path("text").asText().isBlank(),"AI_SCHEMA_INVALID");}
    private ArrayNode indexes(JsonNode ids,ArrayNode evidence,Map<String,Integer> rootIndexes) {
        var indexes=json.createArrayNode();
        for(var s:selected(ids)) {
            if(!rootIndexes.containsKey(s.id())){rootIndexes.put(s.id(),evidence.size());evidence.add(evidence(s));}
            indexes.add(rootIndexes.get(s.id()));
        }
        return indexes;
    }
    private List<Span> selected(JsonNode ids) {
        require(ids!=null&&ids.isArray(),"AI_SCHEMA_INVALID");
        require(!ids.isEmpty(),"AI_EVIDENCE_INVALID");
        var result=new ArrayList<Span>();var seen=new HashSet<String>();
        for(var id:ids) {
            require(id.isTextual(),"AI_SCHEMA_INVALID");
            require(spans.containsKey(id.asText())&&seen.add(id.asText()),"AI_EVIDENCE_INVALID");
            result.add(spans.get(id.asText()));
        }
        return result;
    }
    private ObjectNode evidence(Span span) {
        String text=span.source().text();int base=span.page()==null?0:text.lastIndexOf('\f',span.charStart()-1)+1;
        // Same non-overlapping nth-substring semantics as the existing external validator.
        int from=base,occurrence=0;
        while(true) {
            int found=text.indexOf(span.quote(),from);
            require(found>=0&&found<=span.charStart(),"AI_EVIDENCE_INVALID");
            if(found==span.charStart())break;
            occurrence++;from=found+span.quote().length();
        }
        var value=json.createObjectNode().put("sourceId",span.source().sourceId()).put("textVersion",span.source().textVersion());
        if(span.page()==null)value.putNull("page");else value.put("page",span.page());
        return value.put("quote",span.quote()).put("occurrenceIndex",occurrence);
    }
    private static String digest(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void exact(JsonNode n,Set<String> fields){require(n!=null&&n.isObject()&&n.size()==fields.size()&&fields.stream().allMatch(n::has),"AI_SCHEMA_INVALID");}
    private static void checkDuration(JsonNode n,boolean nullable){
        if(nullable&&(n.isNull()||n.isMissingNode()))return;
        require(n.isIntegralNumber()&&n.asLong()>=1&&n.asLong()<=1440,"AI_SCHEMA_INVALID");
    }
    private static void require(boolean ok,String code){if(!ok)throw new AiProviderException(code,false);}
}
