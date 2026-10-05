package com.c2pa.portal;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
public class ConfigurationVersion {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long configurationRevision;
    public Instant createdAt;
    public String action;
    @Column(length=16000) public String settings;
    public ConfigurationVersion() {}
}
