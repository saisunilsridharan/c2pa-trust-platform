package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
@Service public class AuthenticationRateLimiter {
 public record Decision(boolean allowed,int retryAfter){}
 private final AuthenticationRateSettingsRepository settings;private final AuthenticationRateBucketRepository buckets;private final Clock clock;
 @org.springframework.beans.factory.annotation.Autowired public AuthenticationRateLimiter(AuthenticationRateSettingsRepository settings,AuthenticationRateBucketRepository buckets){this(settings,buckets,Clock.systemUTC());}
 AuthenticationRateLimiter(AuthenticationRateSettingsRepository settings,AuthenticationRateBucketRepository buckets,Clock clock){this.settings=settings;this.buckets=buckets;this.clock=clock;}
 @Transactional public Decision admit(String peer,String forwarded){
  var global=buckets.lockGlobal().orElseThrow();var policy=settings.findById(1L).orElseThrow();Instant now=clock.instant();String address=ProxyAddresses.client(peer,forwarded,ProxyAddresses.networks(policy.trustedProxyCidrs));String key=AccountService.hash("authentication-address:"+address);
  reset(global,now,policy.windowSeconds);if(global.attempts>=policy.globalLimit)return denied(global,now,policy.windowSeconds);
  var existing=buckets.findById(key);if(existing.isEmpty() && buckets.count()>=50001)return new Decision(false,policy.windowSeconds);
  var bucket=existing.orElseGet(()->{var b=new AuthenticationRateBucket();b.id=key;return b;});reset(bucket,now,policy.windowSeconds);
  if(bucket.attempts>=policy.perAddressLimit)return denied(bucket,now,policy.windowSeconds);
  bucket.attempts++;global.attempts++;buckets.saveAndFlush(bucket);buckets.saveAndFlush(global);return new Decision(true,0);
 }
 private void reset(AuthenticationRateBucket b,Instant now,int seconds){if(b.startedAt==null || !now.isBefore(b.startedAt.plusSeconds(seconds))){b.startedAt=now;b.attempts=0;}}
 private Decision denied(AuthenticationRateBucket b,Instant now,int seconds){return new Decision(false,(int)Math.max(1,Duration.between(now,b.startedAt.plusSeconds(seconds)).toSeconds()+1));}
 @Transactional public void cleanup(){buckets.lockGlobal().orElseThrow();buckets.removeOld(clock.instant().minusSeconds(600));}
}
