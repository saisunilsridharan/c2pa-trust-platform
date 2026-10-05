package com.c2pa.portal;
import org.springframework.web.context.request.*;
public final class WorkspaceContext {
 private WorkspaceContext(){}
 public static final Long DEFAULT=1L;
 private static final ThreadLocal<Long> background=new ThreadLocal<>();
 public static <T> T call(Long workspace,java.util.concurrent.Callable<T> action)throws Exception{if(workspace==null || workspace<1)throw new IllegalArgumentException();Long previous=background.get();background.set(workspace);try{return action.call();}finally{if(previous==null)background.remove();else background.set(previous);}}
 public static Long id(){var a=RequestContextHolder.getRequestAttributes();Object v=a==null?null:a.getAttribute("portal.workspaceId",RequestAttributes.SCOPE_REQUEST);return v instanceof Long id?id:background.get()==null?DEFAULT:background.get();}
}
