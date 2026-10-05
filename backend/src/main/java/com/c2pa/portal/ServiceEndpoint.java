package com.c2pa.portal;
import java.net.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Administrator-selected private endpoints: TLS by default; loopback HTTP is explicit. */
public final class ServiceEndpoint {
 private ServiceEndpoint(){}
 public static URI validate(String value,boolean allowLoopbackHttp)throws Exception {
  try {
   URI uri=new URI(value);
   if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || (!uri.getPath().isEmpty() && !uri.getPath().equals("/")) || uri.getPort()==0 || uri.getPort()>65535)throw new IllegalArgumentException();
   boolean https=uri.getScheme().equals("https"),http=uri.getScheme().equals("http");
   if(!https && !http)throw new IllegalArgumentException();
   InetAddress[] addresses=InetAddress.getAllByName(uri.getHost());
   if(addresses.length==0)throw new IllegalArgumentException();
   for(InetAddress address:addresses){
    byte[] bytes=address.getAddress();
    // Includes cloud metadata, link-local, multicast and unspecified destinations.
    boolean metadata=bytes.length==4 && Byte.toUnsignedInt(bytes[0])==100 && Byte.toUnsignedInt(bytes[1])==100 && Byte.toUnsignedInt(bytes[2])==100 && Byte.toUnsignedInt(bytes[3])==200;
    if(address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress() || metadata || (http && (!allowLoopbackHttp || !address.isLoopbackAddress())))throw new IllegalArgumentException();
   }
   return uri;
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Use an HTTPS endpoint without credentials, paths or redirects; HTTP requires explicit loopback development mode");}
 }
}
