package com.c2pa.portal;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:authentication-rate-tests;DB_CLOSE_DELAY=-1")
class AuthenticationRateTest {
 @Autowired AuthenticationRateSettingsRepository settings;
 @Autowired AuthenticationRateBucketRepository buckets;
 @Autowired PlatformTransactionManager manager;
 TransactionTemplate tx;Clock clock;AtomicReference<Instant> now;
 @BeforeEach void reset(){tx=new TransactionTemplate(manager);now=new AtomicReference<>(Instant.parse("2026-10-05T00:00:00Z"));clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now.get();}};tx.execute(status->{buckets.deleteAll();var global=new AuthenticationRateBucket();global.id="GLOBAL";buckets.saveAndFlush(global);var policy=settings.findById(1L).orElseThrow();policy.windowSeconds=10;policy.perAddressLimit=5;policy.globalLimit=20;policy.trustedProxyCidrs="";settings.saveAndFlush(policy);return null;});}
 AuthenticationRateLimiter.Decision admit(AuthenticationRateLimiter instance,String peer,String forwarded){return tx.execute(status->instance.admit(peer,forwarded));}
 @Test void multipleInstancesShareAtomicCountersAndDeniedPeersCannotConsumeGlobalBudget()throws Exception{
  var first=new AuthenticationRateLimiter(settings,buckets,clock);var second=new AuthenticationRateLimiter(settings,buckets,clock);
  var pool=Executors.newFixedThreadPool(8);try{List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<32;i++){var instance=i%2==0?first:second;results.add(pool.submit(()->admit(instance,"198.51.100.1",null).allowed()));}long allowed=0;for(var result:results)if(result.get(15,TimeUnit.SECONDS))allowed++;assertEquals(5,allowed);}finally{pool.shutdownNow();}
  assertEquals(5,buckets.findById("GLOBAL").orElseThrow().attempts);
  for(int i=2;i<=16;i++)assertTrue(admit(second,"198.51.100."+i,null).allowed());
  assertFalse(admit(first,"198.51.100.17",null).allowed());
  now.set(now.get().plusSeconds(10));assertTrue(admit(second,"198.51.100.1",null).allowed());
  assertTrue(buckets.findAll().stream().allMatch(b->b.id.equals("GLOBAL") || b.id.matches("[a-f0-9]{64}")));
 }
 @Test void proxySpoofingAndCanonicalIpv6CannotEvadeClientLimits(){
  var instance=new AuthenticationRateLimiter(settings,buckets,clock);
  for(int i=1;i<=5;i++)assertTrue(admit(instance,"198.51.100.1","203.0.113."+i).allowed());
  assertFalse(admit(instance,"198.51.100.1","203.0.113.99").allowed());
  assertTrue(admit(instance,"2001:db8::1",null).allowed());
  assertEquals(ProxyAddresses.client("2001:db8::1",null,List.of()),ProxyAddresses.client("2001:0db8:0000:0000:0000:0000:0000:0001",null,List.of()));
  var trusted=ProxyAddresses.networks("10.0.0.0/8, 2001:db8:1::/48");
  assertEquals(ProxyAddresses.client("198.51.100.2",null,List.of()),ProxyAddresses.client("10.0.0.1","1.2.3.4, 198.51.100.2, 10.0.0.2",trusted));
  assertEquals(ProxyAddresses.client("10.0.0.1",null,List.of()),ProxyAddresses.client("10.0.0.1","not-an-address",trusted));
  assertThrows(IllegalArgumentException.class,()->ProxyAddresses.networks("example.com/24"));assertThrows(IllegalArgumentException.class,()->ProxyAddresses.networks("0.0.0.0/0"));
 }
 @Test void oldCountersExpireAndCleanupDoesNotRemoveActiveWindow(){
  var instance=new AuthenticationRateLimiter(settings,buckets,clock);assertTrue(admit(instance,"198.51.100.3",null).allowed());now.set(now.get().plusSeconds(601));assertTrue(admit(instance,"198.51.100.4",null).allowed());tx.execute(status->{instance.cleanup();return null;});assertEquals(2,buckets.count());assertEquals(1,buckets.findById("GLOBAL").orElseThrow().attempts);
 }
}
