package com.c2pa.portal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
@Service
public class WorkspaceService {
 private final WorkspaceRepository workspaces;private final MembershipRepository members;private final UserRepository users;private final JdbcTemplate jdbc;
 public WorkspaceService(WorkspaceRepository workspaces,MembershipRepository members,UserRepository users,JdbcTemplate jdbc){this.workspaces=workspaces;this.members=members;this.users=users;this.jdbc=jdbc;}
 @EventListener(ApplicationReadyEvent.class) @Transactional public void initialize(){
  if(!workspaces.existsById(WorkspaceContext.DEFAULT))jdbc.update("insert into workspace(id,name,slug) values(?,?,?)",WorkspaceContext.DEFAULT,"Default workspace","default");
  var legacy=workspaces.findById(WorkspaceContext.DEFAULT).orElseThrow();if(!legacy.legacyMembershipsImported){
  for(var user:users.findAll())if(!members.existsByUserId(user.id)){WorkspaceMembership m=new WorkspaceMembership();m.userId=user.id;m.workspaceId=WorkspaceContext.DEFAULT;m.role=user.role;members.save(m);}
  legacy.legacyMembershipsImported=true;workspaces.save(legacy);}
 }
 public record Selection(Long id,String role){}
 public Selection select(PortalUser user,Long requested){
  Long id=requested;
  if(id==null)id=user.role.equals("ADMIN")?WorkspaceContext.DEFAULT:members.findByUserIdOrderByWorkspaceIdAsc(user.id).stream().findFirst().map(m->m.workspaceId).orElse(null);
  if(id==null || !workspaces.existsById(id))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Workspace access required");
  if(user.role.equals("ADMIN"))return new Selection(id,"ADMIN");
  final Long selected=id;return members.findByUserIdAndWorkspaceId(user.id,id).map(m->new Selection(selected,m.role)).orElseThrow(()->new ResponseStatusException(HttpStatus.FORBIDDEN,"Workspace access required"));
 }
 public record View(Long id,String name,String role,Long revision){}
 public List<View> list(PortalUser user){if(user==null)return List.of(new View(1L,"Default workspace","ADMIN",0L));if(user.role.equals("ADMIN"))return workspaces.findAll().stream().map(w->new View(w.id,w.name,"ADMIN",w.revision)).toList();return members.findByUserIdOrderByWorkspaceIdAsc(user.id).stream().map(m->workspaces.findById(m.workspaceId).map(w->new View(w.id,w.name,m.role,w.revision)).orElse(null)).filter(Objects::nonNull).toList();}
 public void requireAdmin(PortalUser user,Long workspaceId){if(user==null || !select(user,workspaceId).role().equals("ADMIN"))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Workspace administrator required");}
}
