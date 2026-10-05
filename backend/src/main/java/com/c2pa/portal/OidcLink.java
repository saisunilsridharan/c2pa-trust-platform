package com.c2pa.portal;
import jakarta.persistence.*;
@Entity @Table(uniqueConstraints=@UniqueConstraint(columnNames={"issuer","subject"})) public class OidcLink {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
 public Long userId;
 @Column(nullable=false,length=2000) public String issuer;
 @Column(nullable=false) public String subject;
 public OidcLink(){}
}
