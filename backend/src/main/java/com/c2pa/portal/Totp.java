package com.c2pa.portal;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Locale;
public final class Totp {
 private Totp(){}
 public static String encode(byte[] bytes){String alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";StringBuilder out=new StringBuilder();int bits=0,value=0;for(byte b:bytes){value=(value<<8)|Byte.toUnsignedInt(b);bits+=8;while(bits>=5){bits-=5;out.append(alphabet.charAt((value>>bits)&31));}}if(bits>0)out.append(alphabet.charAt((value<<(5-bits))&31));return out.toString();}
 public static String code(byte[] secret,long step)throws Exception {Mac mac=Mac.getInstance("HmacSHA1");mac.init(new SecretKeySpec(secret,"HmacSHA1"));byte[] hash=mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());int offset=hash[hash.length-1]&15;int binary=ByteBuffer.wrap(hash,offset,4).getInt()&0x7fffffff;return String.format(Locale.ROOT,"%06d",binary%1000000);}
}
