package com.c2pa.portal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@RestController
@RequestMapping("/api/v1/workspaces")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="adminToken")
public class WorkspacesController {
 private final WorkspaceService service;private final WorkspaceRepository workspaces;private final MembershipRepository members;private final UserRepository users;private final AuditService audit;
 public WorkspacesController(WorkspaceService service,WorkspaceRepository workspaces,MembershipRepository members,UserRepository users,AuditService audit){this.service=service;this.workspaces=workspaces;this.members=members;this.users=users;this.audit=audit;}
 private PortalUser actor(HttpServletRequest r){Long id=(Long)r.getAttribute("portal.userId");return id==null?null:users.findById(id).orElseThrow();}
 @GetMapping public List<WorkspaceService.View> list(HttpServletRequest r){return service.list(actor(r));}
 public record Creation(@NotBlank @Size(max=120) String name){}
 @PostMapping @Transactional public WorkspaceService.View create(@Valid @RequestBody Creation body,HttpServletRequest r){PortalUser user=actor(r);if(user==null || !user.role.equals("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Platform administrator required");Workspace w=new Workspace();w.name=body.name();w.slug=UUID.randomUUID().toString();w=workspaces.saveAndFlush(w);WorkspaceMembership m=new WorkspaceMembership();m.workspaceId=w.id;m.userId=user.id;m.role="ADMIN";members.save(m);audit.record(w.id,"WORKSPACE_CREATED",String.valueOf(w.id));return new WorkspaceService.View(w.id,w.name,"ADMIN",w.revision);}
 public record Rename(@NotBlank @Size(max=120) String name,@NotNull Long revision){}
 @PutMapping("/{id}") @Transactional public WorkspaceService.View rename(@PathVariable Long id,@Valid @RequestBody Rename body,HttpServletRequest r){var actor=actor(r);service.requireAdmin(actor,id);var workspace=workspaces.lockById(id).orElseThrow();if(!Objects.equals(workspace.revision,body.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Workspace changed; reload");workspace.name=body.name();workspaces.saveAndFlush(workspace);audit.record(id,"WORKSPACE_RENAMED",String.valueOf(id));return new WorkspaceService.View(id,workspace.name,service.select(actor,id).role(),workspace.revision);}
 public record Member(Long userId,String username,String role,boolean enabled,Long revision){}
 @GetMapping("/{id}/members") public List<Member> members(@PathVariable Long id,HttpServletRequest r){service.requireAdmin(actor(r),id);return members.findByWorkspaceId(id).stream().map(m->{var u=users.findById(m.userId).orElseThrow();return new Member(u.id,u.username,m.role,u.enabled,m.revision);}).toList();}
 public record Access(@NotBlank String username,@NotNull @Pattern(regexp="ADMIN|SIGNER|VIEWER") String role,Long revision){}
 @PutMapping("/{id}/members") @Transactional public Member save(@PathVariable Long id,@Valid @RequestBody Access body,HttpServletRequest r){var actor=actor(r);service.requireAdmin(actor,id);workspaces.lockById(id).orElseThrow();var user=users.findByUsername(body.username()).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Account not found"));var old=members.findByUserIdAndWorkspaceId(user.id,id);var m=old.orElseGet(WorkspaceMembership::new);if(old.isPresent() && !Objects.equals(m.revision,body.revision()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Membership changed; reload");if(old.isEmpty() && body.revision()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Membership changed; reload");if(old.isPresent() && m.role.equals("ADMIN") && !body.role().equals("ADMIN"))keepAdministrator(id,actor);m.workspaceId=id;m.userId=user.id;m.role=body.role();m=members.saveAndFlush(m);audit.record(id,"WORKSPACE_MEMBER_CHANGED",id+":"+user.id);return new Member(user.id,user.username,m.role,user.enabled,m.revision);}
 private void keepAdministrator(Long id,PortalUser actor){if(!actor.role.equals("ADMIN") && members.findByWorkspaceId(id).stream().filter(m->m.role.equals("ADMIN")).filter(m->users.findById(m.userId).map(u->u.enabled).orElse(false)).count()<=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Keep an enabled workspace administrator");}
 @DeleteMapping("/{id}/members/{userId}") @Transactional public Map<String,Boolean> remove(@PathVariable Long id,@PathVariable Long userId,@RequestParam Long revision,HttpServletRequest r){var actor=actor(r);service.requireAdmin(actor,id);workspaces.lockById(id).orElseThrow();var m=members.findByUserIdAndWorkspaceId(userId,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!Objects.equals(m.revision,revision))throw new ResponseStatusException(HttpStatus.CONFLICT,"Membership changed; reload");if(m.role.equals("ADMIN"))keepAdministrator(id,actor);members.delete(m);audit.record(id,"WORKSPACE_MEMBER_REMOVED",id+":"+userId);return Map.of("removed",true);}
}
