package com.c2pa.portal;
import org.springframework.web.context.request.*;
public final class WorkspaceContext {
 private WorkspaceContext(){}
 public static final Long DEFAULT=1L;
 public static Long id(){var a=RequestContextHolder.getRequestAttributes();Object v=a==null?null:a.getAttribute("portal.workspaceId",RequestAttributes.SCOPE_REQUEST);return v instanceof Long id?id:DEFAULT;}
}
