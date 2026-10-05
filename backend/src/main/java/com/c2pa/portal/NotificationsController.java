package com.c2pa.portal;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
@RestController
@RequestMapping("/api/v1/notifications")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class NotificationsController {
 private final NotificationRepository notifications;
 public NotificationsController(NotificationRepository notifications){this.notifications=notifications;}
 public record Notification(String id,String jobId,String outcome,Instant createdAt,Instant readAt){}
 public record Page(long unread,List<Notification> items){}
 private Notification view(JobNotification n){return new Notification(n.id,n.jobId,n.outcome,n.createdAt,n.readAt);}
 private String owner(HttpServletRequest request){return (String)request.getAttribute("portal.actor");}
 @GetMapping public Page list(@RequestParam(defaultValue="0") int page,HttpServletRequest request){if(page<0 || page>10000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);String owner=owner(request);return new Page(notifications.countByWorkspaceIdAndOwnerAndReadAtIsNull(WorkspaceContext.id(),owner),notifications.findByWorkspaceIdAndOwnerOrderByCreatedAtDesc(WorkspaceContext.id(),owner,org.springframework.data.domain.PageRequest.of(page,50)).stream().map(this::view).toList());}
 @PostMapping("/{id}/read") @Transactional public Notification read(@PathVariable String id,HttpServletRequest request){var n=notifications.lockById(id).filter(v->v.workspaceId.equals(WorkspaceContext.id()) && v.owner.equals(owner(request))).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(n.readAt==null){n.readAt=Instant.now();notifications.saveAndFlush(n);}return view(n);}
}
