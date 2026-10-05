package com.c2pa.portal;
import jakarta.persistence.*;
@Entity
@Table(uniqueConstraints=@UniqueConstraint(columnNames={"workspace_id","user_id"}))
public class WorkspaceMembership {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 @Version public Long revision;
 @Column(name="workspace_id",nullable=false) public Long workspaceId;
 @Column(name="user_id",nullable=false) public Long userId;
 @Column(nullable=false) public String role;
 public WorkspaceMembership(){}
}
