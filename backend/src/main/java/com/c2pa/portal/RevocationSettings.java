package com.c2pa.portal;
import jakarta.persistence.*;
@Entity public class RevocationSettings { @Id public Long id; @Version public Long revision; public String draftVersion; public String activeVersion; }
