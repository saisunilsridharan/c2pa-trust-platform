package com.c2pa.portal;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuthenticationRateFilterTest {
 @Test void throttledRequestsDoNotReachAuthenticationAndExposeBoundedRetryTime()throws Exception{
  var limiter=mock(AuthenticationRateLimiter.class);when(limiter.admit("198.51.100.1",null)).thenReturn(new AuthenticationRateLimiter.Decision(false,10));
  var request=new MockHttpServletRequest("POST","/api/v1/auth/%6cogin");request.setServletPath("/api/v1/auth/login");request.setRemoteAddr("198.51.100.1");var response=new MockHttpServletResponse();boolean[] reached={false};new AuthenticationRateFilter(limiter).doFilter(request,response,(a,b)->reached[0]=true);
  assertFalse(reached[0]);assertEquals(429,response.getStatus());assertEquals("10",response.getHeader("Retry-After"));assertEquals("no-store",response.getHeader("Cache-Control"));
 }
 @Test void databaseFailureClosesAuthenticationButHealthRemainsAvailable()throws Exception{
  var limiter=mock(AuthenticationRateLimiter.class);when(limiter.admit(anyString(),isNull())).thenThrow(new IllegalStateException("Database unavailable"));var filter=new AuthenticationRateFilter(limiter);var request=new MockHttpServletRequest("POST","/api/v1/auth/oidc/start");var response=new MockHttpServletResponse();filter.doFilter(request,response,(a,b)->fail("Authentication must not bypass unavailable counters"));assertEquals(503,response.getStatus());
  boolean[] reached={false};filter.doFilter(new MockHttpServletRequest("GET","/api/v1/health"),new MockHttpServletResponse(),(a,b)->reached[0]=true);assertTrue(reached[0]);
 }
}
