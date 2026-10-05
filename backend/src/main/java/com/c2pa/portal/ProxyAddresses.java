package com.c2pa.portal;
import java.net.*;
import java.util.*;
public final class ProxyAddresses {
 private ProxyAddresses(){}
 public static byte[] literal(String value){
  if(value==null || value.length()>64)throw new IllegalArgumentException();
  try{
   if(value.contains(":")){if(!value.matches("[0-9a-fA-F:.]+"))throw new IllegalArgumentException();return InetAddress.getByName(value).getAddress();}
   String[] pieces=value.split("\\.",-1);if(pieces.length!=4)throw new IllegalArgumentException();byte[] bytes=new byte[4];for(int i=0;i<4;i++){if(!pieces[i].matches("0|[1-9][0-9]{0,2}"))throw new IllegalArgumentException();int n=Integer.parseInt(pieces[i]);if(n>255)throw new IllegalArgumentException();bytes[i]=(byte)n;}return bytes;
  }catch(Exception e){throw new IllegalArgumentException("An IP literal is required");}
 }
 public record Network(byte[] address,int bits){
  boolean includes(byte[] candidate){if(address.length!=candidate.length)return false;for(int i=0;i<bits;i++)if((address[i/8] & (1 << (7-i%8)))!=(candidate[i/8] & (1 << (7-i%8))))return false;return true;}
 }
 public static List<Network> networks(String configuration){
  if(configuration==null || configuration.isBlank())return List.of();if(configuration.length()>8000)throw new IllegalArgumentException();var result=new ArrayList<Network>();
  for(String entry:configuration.split("[\\s,]+")){if(result.size()>=64)throw new IllegalArgumentException();String[] parts=entry.split("/",-1);if(parts.length!=2 || !parts[1].matches("[0-9]{1,3}"))throw new IllegalArgumentException();byte[] address=literal(parts[0]);int bits=Integer.parseInt(parts[1]);if(bits<1 || bits>address.length*8)throw new IllegalArgumentException();result.add(new Network(address,bits));}return result;
 }
 public static String client(String peer,String forwarded,List<Network> trusted){
  byte[] current=literal(peer);byte[] initial=current;
  if(forwarded!=null && forwarded.length()<=2048 && trusted.stream().anyMatch(n->n.includes(initial))){
   String[] hops=forwarded.split(",",-1);if(hops.length<=16){try{var addresses=new ArrayList<byte[]>();for(String hop:hops)addresses.add(literal(hop.trim()));for(int i=addresses.size()-1;i>=0;i--){byte[] candidate=current;if(trusted.stream().noneMatch(n->n.includes(candidate)))break;current=addresses.get(i);}}catch(IllegalArgumentException ignored){current=literal(peer);}}
  }
  return java.util.HexFormat.of().formatHex(current);
 }
}
