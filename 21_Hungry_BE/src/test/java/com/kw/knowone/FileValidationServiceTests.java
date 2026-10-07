package com.kw.knowone;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;import java.io.ByteArrayInputStream;import java.io.ByteArrayOutputStream;import java.nio.file.Path;import java.time.Duration;import java.util.List;
import javax.imageio.ImageIO;import javax.sound.sampled.AudioFileFormat;import javax.sound.sampled.AudioFormat;import javax.sound.sampled.AudioInputStream;import javax.sound.sampled.AudioSystem;
import org.apache.pdfbox.pdmodel.PDDocument;import org.apache.pdfbox.pdmodel.PDPage;import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;import org.springframework.mock.web.MockMultipartFile;
import com.kw.knowone.common.web.ApiException;import com.kw.knowone.storage.FileValidationService;import com.kw.knowone.storage.LocalPrivateStorage;import com.kw.knowone.storage.ValidatedUpload;

class FileValidationServiceTests {
    @TempDir Path root;
    private FileValidationService service()throws Exception{return new FileValidationService(new LocalPrivateStorage(root.toString()),10_000_000,10,25_000_000,Duration.ofMinutes(20),List.of("audio/wav","audio/x-wav"));}
    @Test void signatureAndDecoderRejectEmptySpoofedAndCorruptFiles()throws Exception{
        for(MockMultipartFile file:List.of(new MockMultipartFile("files","empty.png","image/png",new byte[0]),new MockMultipartFile("files","fake.png","image/png","not-png".getBytes()),new MockMultipartFile("files","bad.png","image/png",new byte[]{(byte)0x89,'P','N','G',13,10,26,10,1,2}))){ApiException error=assertThrows(ApiException.class,()->service().document(file));assertTrue(List.of("INVALID_DOCUMENT","UNSUPPORTED_MEDIA_TYPE").contains(error.code()));}
    }
    @Test void actualSignatureWinsOverClientMimeAndValidImageIsDecoded()throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);
        ValidatedUpload value=service().document(new MockMultipartFile("files","photo.jpg","text/plain",out.toByteArray()));assertEquals("image/png",value.mediaType());assertTrue(value.image());assertArrayEquals(java.security.MessageDigest.getInstance("SHA-256").digest(out.toByteArray()),value.sha256());
    }
    @Test void pdfTenPagesAcceptedAndElevenRejected()throws Exception{
        ValidatedUpload ten=service().document(new MockMultipartFile("files","ten.pdf","application/octet-stream",pdf(10)));assertEquals(10,ten.pageCount());
        ApiException error=assertThrows(ApiException.class,()->service().document(new MockMultipartFile("files","eleven.pdf","application/pdf",pdf(11))));assertEquals("PDF_PAGE_LIMIT_EXCEEDED",error.code());
    }
    @Test void documentByteLimitUsesReceivedBytes()throws Exception{
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",png);byte[] boundary=java.util.Arrays.copyOf(png.toByteArray(),10_000_000);assertEquals(10_000_000,service().document(new MockMultipartFile("files","boundary.png","image/png",boundary)).byteSize());
        byte[] tooLarge=java.util.Arrays.copyOf(boundary,10_000_001);ApiException error=assertThrows(ApiException.class,()->service().document(new MockMultipartFile("files","large.png","image/png",tooLarge)));assertEquals("FILE_TOO_LARGE",error.code());
    }
    @Test void validWebpIsDetectedAndDecoded()throws Exception{
        byte[] webp=java.util.Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89WAAAAA==");
        ValidatedUpload value=service().document(new MockMultipartFile("files","pixel.bin","application/octet-stream",webp));assertEquals("image/webp",value.mediaType());assertTrue(value.image());
    }
    @Test void audioByteAndDurationBoundariesUseParsedWav()throws Exception{
        byte[] byteBoundary=wav(new AudioFormat(22050,8,1,false,false),24_999_956);assertEquals(25_000_000,byteBoundary.length);assertEquals(1134,service().audio(new MockMultipartFile("file","max.wav","audio/wav",byteBoundary)).durationSeconds());
        ApiException size=assertThrows(ApiException.class,()->service().audio(new MockMultipartFile("file","too-large.wav","audio/wav",java.util.Arrays.copyOf(byteBoundary,25_000_001))));assertEquals("FILE_TOO_LARGE",size.code());
        byte[] durationBoundary=wav(new AudioFormat(8000,8,1,false,false),9_600_000);assertEquals(1200,service().audio(new MockMultipartFile("file","1200.wav","audio/wav",durationBoundary)).durationSeconds());
        ApiException duration=assertThrows(ApiException.class,()->service().audio(new MockMultipartFile("file","1201.wav","audio/wav",wav(new AudioFormat(8000,8,1,false,false),9_608_000))));assertEquals("AUDIO_DURATION_LIMIT_EXCEEDED",duration.code());
    }
    private byte[] pdf(int pages)throws Exception{try(PDDocument document=new PDDocument();ByteArrayOutputStream out=new ByteArrayOutputStream()){for(int i=0;i<pages;i++)document.addPage(new PDPage());document.save(out);return out.toByteArray();}}
    private byte[] wav(AudioFormat format,int pcmBytes)throws Exception{byte[] pcm=new byte[pcmBytes];try(AudioInputStream in=new AudioInputStream(new ByteArrayInputStream(pcm),format,pcm.length/format.getFrameSize());ByteArrayOutputStream out=new ByteArrayOutputStream()){AudioSystem.write(in,AudioFileFormat.Type.WAVE,out);return out.toByteArray();}}
}
