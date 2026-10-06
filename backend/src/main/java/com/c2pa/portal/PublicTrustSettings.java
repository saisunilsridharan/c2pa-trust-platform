package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class PublicTrustSettings { @Id public Long id;@Version public Long revision;public String draftVersion,activeVersion,highWaterVersion; }
