package com.c2pa.portal;
import jakarta.persistence.*;
@Entity
public class Workspace {
 @Id @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="workspace_ids") @SequenceGenerator(name="workspace_ids",sequenceName="workspace_ids",initialValue=2,allocationSize=1) public Long id;
 @Version @Column(nullable=false,columnDefinition="bigint default 0") public Long revision;
 @Column(nullable=false,length=120) public String name;
 @Column(nullable=false,unique=true) public String slug;
 @Column(nullable=false,columnDefinition="boolean default false") public boolean legacyMembershipsImported;
 public Workspace(){}
}
