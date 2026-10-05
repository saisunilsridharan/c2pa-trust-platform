package com.c2pa.portal;
@org.springframework.stereotype.Component public class AuthenticationRateCleanup {
 private final AuthenticationRateLimiter limiter;
 public AuthenticationRateCleanup(AuthenticationRateLimiter limiter){this.limiter=limiter;}
 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000) public void cleanup(){limiter.cleanup();}
}
