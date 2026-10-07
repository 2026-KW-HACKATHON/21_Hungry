package com.kw.knowone.storage;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.kw.knowone.common.web.ApiException;

@Service
public class FileValidationService {
    private final StoragePort storage;
    private final long documentMaxBytes;
    private final int pdfMaxPages;
    private final long audioMaxBytes;
    private final Duration audioMaxDuration;
    private final List<String> supportedAudioTypes;

    public FileValidationService(StoragePort storage,
            @Value("${app.encounter.document-max-bytes:10000000}")long documentMaxBytes,
            @Value("${app.encounter.document-max-pdf-pages:10}")int pdfMaxPages,
            @Value("${app.encounter.audio-max-bytes:25000000}")long audioMaxBytes,
            @Value("${app.encounter.audio-max-duration:PT20M}")Duration audioMaxDuration,
            @Value("${app.encounter.supported-audio-types:audio/wav,audio/x-wav}")List<String> supportedAudioTypes){
        this.storage=storage;this.documentMaxBytes=documentMaxBytes;this.pdfMaxPages=pdfMaxPages;
        this.audioMaxBytes=audioMaxBytes;this.audioMaxDuration=audioMaxDuration;this.supportedAudioTypes=supportedAudioTypes;
    }

    public ValidatedUpload document(MultipartFile file){return validate(file,false);}
    public ValidatedUpload audio(MultipartFile file){return validate(file,true);}

    private ValidatedUpload validate(MultipartFile file,boolean audio){
        StoragePort.StagedObject staged=null;
        try{
            staged=storage.stage(file.getInputStream());long size=staged.byteSize();
            if(size<1)throw invalid(audio?"INVALID_AUDIO":"INVALID_DOCUMENT","빈 파일은 등록할 수 없습니다.");
            long max=audio?audioMaxBytes:documentMaxBytes;
            if(size>max)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE","파일 크기 제한을 초과했습니다.");
            byte[] header=readHeader(staged,16);String type=detect(header);
            Integer pages=null,duration=null;boolean image=false;
            if(audio){
                if(!"audio/wav".equals(type)||supportedAudioTypes.stream().noneMatch(v->v.equals(type)||v.equals("audio/x-wav")))
                    throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_MEDIA_TYPE","지원하지 않는 실제 음성 형식입니다.");
                duration=validateWav(staged);
            }else if("application/pdf".equals(type)){
                pages=validatePdf(staged);
            }else if(List.of("image/jpeg","image/png","image/webp").contains(type)){
                validateImage(staged,type);image=true;
            }else throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_MEDIA_TYPE","지원하지 않는 실제 문서 형식입니다.");
            return new ValidatedUpload(staged,safeName(file.getOriginalFilename()),type,size,sha256(staged),pages,duration,image);
        }catch(ApiException failure){storage.discard(staged);throw failure;}
        catch(Exception failure){storage.discard(staged);throw invalid(audio?"INVALID_AUDIO":"INVALID_DOCUMENT","파일을 읽을 수 없습니다.");}
    }

    private int validatePdf(StoragePort.StagedObject staged)throws IOException{
        try(PDDocument document=Loader.loadPDF(staged.path().toFile())){
            if(document.isEncrypted())throw invalid("INVALID_DOCUMENT","암호화 PDF는 등록할 수 없습니다.");
            int pages=document.getNumberOfPages();if(pages<1)throw invalid("INVALID_DOCUMENT","빈 PDF는 등록할 수 없습니다.");
            if(pages>pdfMaxPages)throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF_PAGE_LIMIT_EXCEEDED","PDF 페이지 제한을 초과했습니다.");
            return pages;
        }
    }

    private void validateImage(StoragePort.StagedObject staged,String type)throws IOException{
        try(InputStream in=Files.newInputStream(staged.path())){
            BufferedImage image=ImageIO.read(in);
            if(image==null||image.getWidth()<1||image.getHeight()<1)throw invalid("INVALID_DOCUMENT","손상된 이미지입니다.");
        }
        if("image/webp".equals(type)&&!ImageIO.getImageReadersByMIMEType("image/webp").hasNext())
            throw invalid("INVALID_DOCUMENT","현재 서버가 WEBP를 디코딩할 수 없습니다.");
    }

    private int validateWav(StoragePort.StagedObject staged)throws Exception{
        try(AudioInputStream in=AudioSystem.getAudioInputStream(staged.path().toFile())){
            long frames=in.getFrameLength();float rate=in.getFormat().getFrameRate();
            if(frames<=0||rate<=0)throw invalid("INVALID_AUDIO","음성 길이를 확인할 수 없습니다.");
            long seconds=(long)Math.ceil(frames/rate);
            if(seconds>audioMaxDuration.toSeconds())throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"AUDIO_DURATION_LIMIT_EXCEEDED","음성 길이 제한을 초과했습니다.");
            return Math.toIntExact(seconds);
        }
    }

    private byte[] readHeader(StoragePort.StagedObject staged,int length)throws IOException{
        try(InputStream in=new BufferedInputStream(Files.newInputStream(staged.path()))){return in.readNBytes(length);}
    }
    private String detect(byte[] b){
        if(b.length>=4&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F')return "application/pdf";
        if(b.length>=8&&(b[0]&255)==0x89&&b[1]=='P'&&b[2]=='N'&&b[3]=='G'&&(b[4]&255)==13&&(b[5]&255)==10&&(b[6]&255)==26&&(b[7]&255)==10)return "image/png";
        if(b.length>=3&&(b[0]&255)==0xff&&(b[1]&255)==0xd8&&(b[2]&255)==0xff)return "image/jpeg";
        if(b.length>=12&&new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))return "image/webp";
        if(b.length>=12&&new String(b,0,4,java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE"))return "audio/wav";
        return "application/octet-stream";
    }
    private byte[] sha256(StoragePort.StagedObject staged)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(staged.path())){byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))>0;)digest.update(buffer,0,n);}return digest.digest();
    }
    private String safeName(String name){if(name==null||name.isBlank())return "file";String value=PathName.basename(name).replaceAll("[\\r\\n\\\"]","_");return value.length()>255?value.substring(value.length()-255):value;}
    private ApiException invalid(String code,String message){return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,code,message);}

    private static final class PathName {static String basename(String name){String normalized=name.replace('\\','/');int index=normalized.lastIndexOf('/');return index<0?normalized:normalized.substring(index+1);}}
}
