package com.epam.healenium.model.domain;

import com.epam.healenium.converter.RecordWrapperConverter;
import com.epam.healenium.model.Locator;
import com.epam.healenium.model.wrapper.RecordWrapper;
import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.hibernate.annotations.*;

import javax.persistence.*;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Represent report about healing during test.
 * Contains information about healed locators and value that was selected as healed.
 */

@Accessors(chain = true)
@Data
@Entity
@Table(name = "report")
@TypeDefs({
        @TypeDef(name = "jsonb", typeClass = JsonBinaryType.class)
})
public class Report {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid2")
    @Column(name = "uid")
    private String uid;

    @Column(name = "elements", columnDefinition = "jsonb")
    @Basic(fetch = FetchType.LAZY)
    @Convert(converter = RecordWrapperConverter.class)
    private RecordWrapper recordWrapper = new RecordWrapper();

    @Column(name = "create_date")
    @CreationTimestamp
    private LocalDateTime createdDate;

    /**
     * Add record to report
     * @param healing
     * @param healingResult
     */
    public void addRecord(Healing healing, HealingResult healingResult, String screenshotPath){
        Selector selector = healing.getSelector();

        Record record = new Record();
        record.setName(selector.getName());
        record.setClassName(selector.getClassName());
        record.setMethodName(selector.getMethodName());
        record.setFailedLocator(selector.getLocator());
        record.setHealedLocator(healingResult.getLocator());
        record.setScreenShotPath(screenshotPath);
        recordWrapper.getRecords().add(record);
    }

    @Data
    public static class Record implements Serializable {
        private String name;
        private String className;
        private String methodName;
        private Locator failedLocator;
        private Locator healedLocator;
        @ToString.Exclude
        private String screenShotPath;
    }
}
