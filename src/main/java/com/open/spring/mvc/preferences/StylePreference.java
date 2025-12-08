package com.open.spring.mvc.preferences;

import java.util.HashMap;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;


@Entity
public class StylePreference {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long personId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> styleData = new HashMap<>();

    public StylePreference(Long personId, Integer fontSize, String fontFamily, 
                          String textColor, String backgroundColor, String accentColor) {
        this.personId = personId;
        this.styleData = new HashMap<>();
        this.styleData.put("fontSize", fontSize);
        this.styleData.put("fontFamily", fontFamily);
        this.styleData.put("textColor", textColor);
        this.styleData.put("backgroundColor", backgroundColor);
        this.styleData.put("accentColor", accentColor);
    }


    public StylePreference() {
    }

    public Long getId() {
        return id;
    }
    public void setId(Long id) {
        this.id = id;
    }
    
    public Long getPersonId() {
        return personId;
    }
    public void setPersonId(Long personId) {
        this.personId = personId;
    }
    
    public Map<String, Object> getStyleData() {
        return styleData;
    }
    public void setStyleData(Map<String, Object> styleData) {
        this.styleData = styleData;
    }
}

