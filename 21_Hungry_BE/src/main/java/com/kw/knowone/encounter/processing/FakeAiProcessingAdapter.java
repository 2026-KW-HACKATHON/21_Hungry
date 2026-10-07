package com.kw.knowone.encounter.processing;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
@Component @Profile("test")
public class FakeAiProcessingAdapter implements AiProcessingPort {
    public TextResult transcribe(byte[] bytes,String mediaType){return new TextResult("가상 전사 원문","FAKE","fake-transcribe",null,0,0);}
    public TextResult ocr(byte[] bytes,String mediaType){return new TextResult("가상 문서 원문","FAKE","fake-ocr","1.0",0,0);}
    public AnalysisResult analyze(AnalysisInput input){return new AnalysisResult("가상 입력을 정리했습니다.","{\"symptoms\":[],\"tests\":[],\"medicationMentions\":[],\"precautions\":[],\"followUps\":[]}","[]",List.of(),"FAKE","fake-analyze","1.0",0,0);}
}
