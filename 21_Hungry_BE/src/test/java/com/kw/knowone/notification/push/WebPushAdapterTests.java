package com.kw.knowone.notification.push;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebPushAdapterTests {
    @Test void configuredAdapterRegistersCryptoProviderAndAcceptsMatchingP256Keys() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));var pair=generator.generateKeyPair();
        var publicKey=(ECPublicKey)pair.getPublic();var privateKey=(ECPrivateKey)pair.getPrivate();
        byte[] point=new byte[65];point[0]=4;copy32(publicKey.getW().getAffineX().toByteArray(),point,1);copy32(publicKey.getW().getAffineY().toByteArray(),point,33);
        byte[] scalar=new byte[32];copy32(privateKey.getS().toByteArray(),scalar,0);
        var properties=new PushProperties(true,true,1,Duration.ofSeconds(120),3,Duration.ofSeconds(2),Duration.ofSeconds(20),
                encoded(point),encoded(scalar),"mailto:operator@example.com",List.of("fcm.googleapis.com"));
        assertDoesNotThrow(()->new WebPushAdapter(properties,Clock.systemUTC()));
        assertNotNull(Security.getProvider("BC"));
    }

    private static String encoded(byte[] value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value);}
    private static void copy32(byte[] source,byte[] target,int offset){int start=Math.max(0,source.length-32);int length=Math.min(32,source.length);System.arraycopy(source,start,target,offset+32-length,length);}
}
